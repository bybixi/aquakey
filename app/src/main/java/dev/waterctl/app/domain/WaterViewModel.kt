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
    val lastSession: LastSession? = null,
    val rememberedName: String? = null,
    /** 蓝牙未开启或没有蓝牙硬件 */
    val bluetoothUnavailable: Boolean = false,
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
    private var pendingDevice: WaterDevice? = null
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
        closePicker()
        pendingDevice = device
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    control = it.control.copy(
                        stage = ControlStage.CONNECTING,
                        deviceName = device.name,
                        steps = ConnectSteps(),
                        elapsedSeconds = 0L,
                    ),
                )
            }
            try {
                ble.startSession(device.bluetoothDevice, device.name)
                prefs.setLastDevice(device.name, device.address)
                pendingDevice = null
            } catch (e: CancellationException) {
                // 用户取消 / 主动断开 —— 不是错误，必须原样抛出，否则会误报给用户
                pendingDevice = null
                throw e
            } catch (e: Throwable) {
                pendingDevice = null
                if (e !is WaterCtlException) {
                    onError(WaterCtlException(WaterCtlErrorKind.UNHANDLED, e.message, e))
                }
            }
        }
    }

    fun endSession() {
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            val started = sessionStartedAt
            try {
                ble.endSession()
            } catch (e: CancellationException) {
                ble.abort()
                throw e
            } catch (_: Throwable) {
                ble.abort()
            }
            val duration = if (started > 0L) {
                ((System.currentTimeMillis() - started) / 1000L).toInt()
            } else {
                0
            }
            val name = _state.value.control.deviceName
            sessionStartedAt = 0L
            stopTimer()
            if (duration > 0 && name != null) {
                dao.insert(WaterRecord(startedAt = started, durationSeconds = duration, deviceName = name))
            }
            _state.update {
                it.copy(
                    control = it.control.copy(
                        stage = ControlStage.IDLE,
                        deviceName = null,
                        steps = ConnectSteps(),
                        elapsedSeconds = 0L,
                    ),
                )
            }
        }
    }

    fun cancelConnecting() {
        sessionJob?.cancel()
        ble.abort()
        sessionStartedAt = 0L
        stopTimer()
        _state.update {
            it.copy(control = it.control.copy(stage = ControlStage.IDLE, deviceName = null, steps = ConnectSteps()))
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
    fun tryAutoReconnect() {
        if (autoReconnectTried) return
        autoReconnectTried = true
        val s = _state.value.settings
        if (!s.autoReconnect || !s.rememberDevice) return
        if (s.lastDeviceAddress == null) return
        if (_state.value.control.stage != ControlStage.IDLE) return
        beginSession()
    }

    fun openPicker() {
        if (!ble.hasBluetooth() || !ble.bluetoothReady()) {
            _state.update { it.copy(control = it.control.copy(bluetoothUnavailable = true)) }
            return
        }
        _state.update { it.copy(scanning = true, scanResults = emptyList()) }
        ble.startScan(
            onUpdate = { list -> _state.update { it.copy(scanResults = list) } },
            onFailed = { _state.update { it.copy(scanning = false) } },
        )
    }

    fun closePicker() {
        ble.stopScan()
        _state.update { it.copy(scanning = false) }
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
        startTimer()
    }

    override fun onSessionEnded(durationSeconds: Int) {
        stopTimer()
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
            stopTimer()
            sessionStartedAt = 0L
            _state.update {
                it.copy(
                    control = it.control.copy(
                        stage = ControlStage.IDLE,
                        deviceName = null,
                        steps = ConnectSteps(),
                        elapsedSeconds = 0L,
                    ),
                )
            }
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
