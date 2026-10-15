package dev.waterctl.app.domain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.waterctl.app.WaterCtlApp
import dev.waterctl.app.core.ble.ConnectionStage
import dev.waterctl.app.core.ble.WaterCtlBle
import dev.waterctl.app.core.ble.WaterCtlListener
import dev.waterctl.app.core.ble.WaterDevice
import dev.waterctl.app.core.protocol.ErrorResolver
import dev.waterctl.app.core.protocol.WaterCtlErrorInfo
import dev.waterctl.app.core.protocol.WaterCtlErrorKind
import dev.waterctl.app.core.protocol.WaterCtlException
import dev.waterctl.app.data.db.WaterRecord
import dev.waterctl.app.data.prefs.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** 主界面的三种形态。 */
enum class ControlStage { IDLE, CONNECTING, ACTIVE }

/** 「连接中」那屏的三步进度。 */
data class ConnectSteps(
    val bluetoothConnected: Boolean = false,
    val keyVerifying: Boolean = false,
    val keyVerified: Boolean = false,
    val sessionStarting: Boolean = false,
)

data class LastSession(
    val deviceName: String,
    val startedAt: Long,
    val durationSeconds: Int,
)

data class ControlUiState(
    val stage: ControlStage = ControlStage.IDLE,
    /** 连接中 / 使用中的设备名 */
    val deviceName: String? = null,
    val steps: ConnectSteps = ConnectSteps(),
    val elapsedSeconds: Long = 0L,
    /** 会话开始的绝对时刻，由 onSessionStarted 写入 —— UI 直接读，不要从 elapsed 反推 */
    val startedAt: Long = 0L,
    val lastSession: LastSession? = null,
    val rememberedName: String? = null,
)

data class RecordsSummary(
    val totalSeconds: Int = 0,
    val count: Int = 0,
) {
    val averageSeconds: Int get() = if (count == 0) 0 else totalSeconds / count
}

data class RecordGroup(val label: String, val records: List<WaterRecord>)

data class RecordsUiState(
    val summary: RecordsSummary = RecordsSummary(),
    val groups: List<RecordGroup> = emptyList(),
) {
    val isEmpty: Boolean get() = groups.isEmpty()
}

data class AppUiState(
    val control: ControlUiState = ControlUiState(),
    val records: RecordsUiState = RecordsUiState(),
    val settings: AppSettings = AppSettings(),
    val error: WaterCtlErrorInfo? = null,
    val debugLog: List<String> = emptyList(),
    /** 选择面板是否可见。与 scanning 分开 —— 扫描超时自动停止后面板要留着展示结果 */
    val pickerVisible: Boolean = false,
    val scanning: Boolean = false,
    val scanResults: List<WaterDevice> = emptyList(),
)

class WaterViewModel(application: Application) : AndroidViewModel(application), WaterCtlListener {

    private val app = application as WaterCtlApp
    private val dao = app.database.waterRecordDao()
    private val prefs = app.settings
    private val ble = WaterCtlBle(application).also { it.listener = this }

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private var timerJob: Job? = null
    private var sessionJob: Job? = null
    private var sessionStartedAt: Long = 0L
    /**
     * 本次会话的设备名。
     *
     * 单独存一份而不是从 `_state` 读 —— 会话中途出错时 `onError` 会把
     * `control.deviceName` 清掉，那之后记账就取不到名字了。
     */
    private var sessionDeviceName: String? = null
    /**
     * 本次会话是否已经记过账。
     *
     * 会话有两条结束路径：用户点「结束」（endSession）和设备主动断开（BLE 的 B3 分支）。
     * 两条都可能触发记账，必须防重。
     */
    private var sessionAccounted = false
    private var autoReconnectTried = false

    init {
        // 记录表既驱动「记录」页，也驱动主界面的「上次连接」卡片 ——
        // 一次用例结束立刻就能看到，不需要重启应用。
        viewModelScope.launch {
            dao.observeAll().collect { records ->
                val latest = records.firstOrNull()
                _state.update {
                    it.copy(
                        records = buildRecordsState(records),
                        control = it.control.copy(
                            lastSession = latest?.let { r ->
                                LastSession(r.deviceName, r.startedAt, r.durationSeconds)
                            },
                        ),
                    )
                }
            }
        }
        viewModelScope.launch {
            prefs.settings.collect { s ->
                _state.update {
                    it.copy(
                        settings = s,
                        control = it.control.copy(rememberedName = s.lastDeviceName),
                    )
                }
            }
        }
    }

    // =====================================================================
    // 主按钮
    // =====================================================================

    fun onMainAction() {
        when (_state.value.control.stage) {
            ControlStage.IDLE -> beginSession()
            ControlStage.ACTIVE -> endSession()
            ControlStage.CONNECTING -> Unit
        }
    }

    fun beginSession() {
        if (_state.value.control.stage != ControlStage.IDLE) return

        val rememberedAddress = _state.value.settings.lastDeviceAddress
        if (rememberedAddress != null) {
            val device = ble.deviceForAddress(rememberedAddress)
            if (device != null) {
                startWith(WaterDevice(device.name ?: rememberedAddress, rememberedAddress, 0, device))
                return
            }
        }
        // 没有记住的设备（或系统里已不存在）→ 让用户从扫描列表里选
        openPicker()
    }

    fun startWith(device: WaterDevice) {
        // 门控：会话进行中不允许再发起一次。
        // 少了这道门，用户在 ACTIVE 时从选择器再选一台设备，就会再次 connectGatt ——
        // gatt 字段被覆盖，旧的 BluetoothGatt 永远无法 close（连接泄漏），
        // 而且旧设备收不到结束协议，对它来说上一次会话还在计时。
        if (_state.value.control.stage != ControlStage.IDLE) return

        closePicker()
        sessionAccounted = false
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    control = it.control.copy(
                        stage = ControlStage.CONNECTING,
                        deviceName = device.name,
                        steps = ConnectSteps(),
                        elapsedSeconds = 0L,
                        startedAt = 0L,
                    ),
                )
            }
            try {
                ble.startSession(device.bluetoothDevice, device.name)
                prefs.setLastDevice(device.name, device.address)
            } catch (e: CancellationException) {
                // 用户取消 / 主动断开 —— 不是错误，必须原样抛出，否则会误报给用户
                throw e
            } catch (e: Throwable) {
                if (e !is WaterCtlException) {
                    onError(WaterCtlException(WaterCtlErrorKind.UNHANDLED, e.message, e))
                }
            }
        }
    }

    fun endSession() {
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            try {
                ble.endSession()
            } catch (e: CancellationException) {
                ble.abort()
                throw e
            } catch (_: Throwable) {
                ble.abort()
            }
            // 正常路径由 onSessionEnded 回调记账；
            // 这里兜底处理"设备没回应结束指令、BLE 层因此没有回调"的情况。
            accountSession(currentSessionSeconds())
        }
    }

    /** 连接中取消：立刻断开并复位，不上报错误。 */
    fun cancelConnecting() {
        if (_state.value.control.stage != ControlStage.CONNECTING) return
        sessionJob?.cancel()
        ble.abort()
        sessionStartedAt = 0L
        sessionDeviceName = null
        sessionAccounted = true // 会话从未真正开始，不记账
        stopTimer()
        _state.update {
            it.copy(
                control = it.control.copy(
                    stage = ControlStage.IDLE,
                    deviceName = null,
                    steps = ConnectSteps(),
                    elapsedSeconds = 0L,
                    startedAt = 0L,
                ),
            )
        }
    }

    // =====================================================================
    // 会话记账
    // =====================================================================

    /**
     * 会话记账的**唯一入口**。
     *
     * 结束有两条路径 —— 用户点「结束」（走 [endSession]）和设备主动断开（走 BLE 的 `0xB3` 分支），
     * 两条都汇聚到这里。之前只挂在 [endSession] 上，设备主动断开时记录就丢了。
     */
    private fun accountSession(durationSeconds: Int) {
        if (sessionAccounted) return
        sessionAccounted = true

        val started = sessionStartedAt
        val name = sessionDeviceName
        sessionStartedAt = 0L
        sessionDeviceName = null
        stopTimer()

        if (started > 0L && durationSeconds > 0 && name != null) {
            viewModelScope.launch {
                dao.insert(
                    WaterRecord(
                        startedAt = started,
                        durationSeconds = durationSeconds,
                        deviceName = name,
                    ),
                )
            }
        }

        _state.update {
            it.copy(
                control = it.control.copy(
                    stage = ControlStage.IDLE,
                    deviceName = null,
                    steps = ConnectSteps(),
                    elapsedSeconds = 0L,
                    startedAt = 0L,
                ),
            )
        }
    }

    private fun currentSessionSeconds(): Int {
        val started = sessionStartedAt
        return if (started > 0L) {
            ((System.currentTimeMillis() - started) / 1000L).toInt()
        } else {
            0
        }
    }

    // =====================================================================
    // 设备选择
    // =====================================================================

    /**
     * 应用启动后尝试一次自动重连（设置里打开「自动重连」时）。
     *
     * 由 UI 在确认蓝牙权限就绪后调用 —— 权限没给就贸然连接只会弹一个无意义的错误。
     * 只尝试一次，失败不重试、不打扰用户。
     */
    suspend fun tryAutoReconnect() {
        if (autoReconnectTried) return
        autoReconnectTried = true

        // 必须等 DataStore 的首值，而不是读 _state 里可能尚未加载的设置 ——
        // 冷启动时触发点完全可能早于首次读盘完成，那时 lastDeviceAddress 还是 null，
        // 而 autoReconnectTried 已经置位，结果就是"开了自动重连也永远不生效"。
        val s = prefs.settings.first()
        if (!s.autoReconnect || !s.rememberDevice) return
        if (s.lastDeviceAddress == null) return
        if (_state.value.control.stage != ControlStage.IDLE) return
        beginSession()
    }

    fun openPicker() {
        // 蓝牙未就绪时静默返回 —— 控制页本身受权限门控，
        // 能走到这里就说明蓝牙是可用的。
        if (!ble.hasBluetooth() || !ble.bluetoothReady()) return
        _state.update {
            it.copy(pickerVisible = true, scanning = true, scanResults = emptyList())
        }
        ble.startScan(
            onUpdate = { list -> _state.update { it.copy(scanResults = list) } },
            onFailed = { _state.update { it.copy(scanning = false) } },
            // 扫描超时自动停止：面板保持可见以展示已找到的结果，只是不再转圈
            onStopped = { _state.update { it.copy(scanning = false) } },
        )
    }

    fun closePicker() {
        ble.stopScan()
        _state.update { it.copy(pickerVisible = false, scanning = false) }
    }

    // =====================================================================
    // 设置
    // =====================================================================

    fun setRememberDevice(v: Boolean) = viewModelScope.launch {
        prefs.setRememberDevice(v)
        if (!v) prefs.clearLastDevice()
    }

    fun setAutoReconnect(v: Boolean) = viewModelScope.launch { prefs.setAutoReconnect(v) }

    fun setShowDebugLog(v: Boolean) = viewModelScope.launch { prefs.setShowDebugLog(v) }

    fun clearRecords() = viewModelScope.launch { dao.clearAll() }

    fun forgetDevice() = viewModelScope.launch { prefs.clearLastDevice() }

    fun dismissError() {
        _state.update { it.copy(error = null, debugLog = emptyList()) }
    }

    // =====================================================================
    // WaterCtlListener
    // =====================================================================

    override fun onStageChanged(stage: ConnectionStage) {
        _state.update { current ->
            val steps = when (stage) {
                ConnectionStage.IDLE -> ConnectSteps()
                ConnectionStage.CONNECTING -> ConnectSteps()
                ConnectionStage.HANDSHAKING -> ConnectSteps(bluetoothConnected = true, keyVerifying = true)
                ConnectionStage.STARTING -> ConnectSteps(bluetoothConnected = true, keyVerified = true, sessionStarting = true)
                ConnectionStage.ACTIVE -> ConnectSteps(bluetoothConnected = true, keyVerified = true, sessionStarting = true)
            }
            val uiStage = when (stage) {
                ConnectionStage.ACTIVE -> ControlStage.ACTIVE
                ConnectionStage.IDLE -> ControlStage.IDLE
                else -> ControlStage.CONNECTING
            }
            current.copy(control = current.control.copy(stage = uiStage, steps = steps))
        }
    }

    override fun onDeviceNameChanged(name: String) {
        _state.update { it.copy(control = it.control.copy(deviceName = name)) }
    }

    override fun onSessionStarted(startedAt: Long) {
        sessionStartedAt = startedAt
        sessionDeviceName = _state.value.control.deviceName
        sessionAccounted = false
        _state.update { it.copy(control = it.control.copy(startedAt = startedAt)) }
        startTimer()
    }

    override fun onSessionEnded(durationSeconds: Int) {
        accountSession(durationSeconds)
    }

    override fun onLog(line: String) {
        _state.update { current ->
            val logs = (current.debugLog + line).takeLast(LOG_LIMIT)
            current.copy(debugLog = logs)
        }
    }

    override fun onError(error: WaterCtlException) {
        // 协程取消不是故障，不该弹给用户
        if (error.cause is CancellationException) return
        val info = ErrorResolver.infoOf(error)
        _state.update { it.copy(error = info) }
        if (info.fatal) {
            sessionJob?.cancel()
            // 会话中途失败（例如设备掉线）时这次用水是真实发生过的，
            // 也要走记账 —— 否则用户"用了水但记录里没有"。
            accountSession(currentSessionSeconds())
        }
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                val elapsed = if (sessionStartedAt > 0L) {
                    (System.currentTimeMillis() - sessionStartedAt) / 1000L
                } else {
                    0L
                }
                _state.update { it.copy(control = it.control.copy(elapsedSeconds = elapsed)) }
                delay(500)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun buildRecordsState(records: List<WaterRecord>): RecordsUiState {
        val cal = Calendar.getInstance()
        val today = cal.startOfDay()
        val monthStart = cal.startOfMonth()

        val thisMonth = records.filter { it.startedAt >= monthStart }
        val summary = RecordsSummary(
            totalSeconds = thisMonth.sumOf { it.durationSeconds },
            count = thisMonth.size,
        )

        val yesterday = today - TimeUnit.DAYS.toMillis(1)
        val groups = records.groupBy { r ->
            when {
                r.startedAt >= today -> "今天"
                r.startedAt >= yesterday -> "昨天"
                else -> formatDay(r.startedAt)
            }
        }.map { (label, list) -> RecordGroup(label, list) }

        return RecordsUiState(summary = summary, groups = groups)
    }

    private fun Calendar.startOfDay(): Long {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        return timeInMillis
    }

    private fun Calendar.startOfMonth(): Long {
        set(Calendar.DAY_OF_MONTH, 1)
        return startOfDay()
    }

    private fun formatDay(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return "%d 月 %d 日".format(c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    override fun onCleared() {
        super.onCleared()
        stopTimer()
        ble.release()
    }

    private companion object {
        const val LOG_LIMIT = 40
    }
}
