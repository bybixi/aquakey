package dev.waterctl.app.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import dev.waterctl.app.core.protocol.Payloads
import dev.waterctl.app.core.protocol.Solvers
import dev.waterctl.app.core.protocol.WaterCtlErrorKind
import dev.waterctl.app.core.protocol.WaterCtlException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 水控器使用的固定 GATT 标识。 */
private val SERVICE_UUID: UUID = UUID.fromString("0000f1f0-0000-1000-8000-00805f9b34fb")
private val TXD_UUID: UUID = UUID.fromString("0000f1f1-0000-1000-8000-00805f9b34fb")
private val RXD_UUID: UUID = UUID.fromString("0000f1f2-0000-1000-8000-00805f9b34fb")
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** 会话所处阶段，用于驱动「连接中」那屏的三步进度卡。 */
enum class ConnectionStage {
    IDLE,
    CONNECTING,
    HANDSHAKING,
    STARTING,
    ACTIVE,
}

data class WaterDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val bluetoothDevice: BluetoothDevice,
)

interface WaterCtlListener {
    fun onStageChanged(stage: ConnectionStage)
    /** 已连接的水控器名（用于状态胶囊） */
    fun onDeviceNameChanged(name: String)
    fun onSessionStarted(startedAt: Long)
    fun onSessionEnded(durationSeconds: Int)
    fun onLog(line: String)
    fun onError(error: WaterCtlException)
}

/**
 * 蓝牙水控器客户端。
 *
 * 状态机逐条对应原项目 `bluetooth.ts` 的 `handleRxdNotifications`，
 * 包括几个固件怪癖（丢失前导字节、会主动发 AT 命令、B0/B1 后需延迟 500ms 等）。
 */
class WaterCtlBle(private val context: Context) {

    var listener: WaterCtlListener? = null

    private val scope = CoroutineScope(SupervisorJob())

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // ---- 连接态 ----
    private var gatt: BluetoothGatt? = null
    private var txd: BluetoothGattCharacteristic? = null
    private var rxd: BluetoothGattCharacteristic? = null
    private var deviceName: String = ""
    private var startedAt: Long = 0L
    private var intentionalDisconnect = false

    // ---- 协程同步原语 ----
    private var connectedDeferred: CompletableDeferred<Unit>? = null
    private var servicesDeferred: CompletableDeferred<Unit>? = null
    private var descriptorDeferred: CompletableDeferred<Unit>? = null
    private var writeDeferred: CompletableDeferred<Unit>? = null
    private var sessionDeferred: CompletableDeferred<Unit>? = null
    private var endDeferred: CompletableDeferred<Unit>? = null

    private val writeMutex = Mutex()
    private val handlerMutex = Mutex()

    private var startEpilogueJob: Job? = null
    private var timeoutJob: Job? = null

    // =====================================================================
    // 权限 / 适配器状态
    // =====================================================================

    fun bluetoothReady(): Boolean = adapter?.isEnabled == true

    fun hasBluetooth(): Boolean = adapter != null

    // =====================================================================
    // 扫描
    // =====================================================================

    private var scanCallback: ScanCallback? = null
    private var scanTicker: Job? = null
    private var scanTimeoutJob: Job? = null
    private var scanOnUpdate: ((List<WaterDevice>) -> Unit)? = null

    /**
     * 下面三个集合会被两个线程同时访问：
     * - **写**：`onScanResult` 由系统在 binder 线程回调
     * - **读**：`snapshot()` 由 scanTicker 在 Dispatchers.Default 上执行
     *
     * 所以必须用并发容器。用普通 HashMap 的话，并发读写会导致
     * ConcurrentModificationException，极端情况下（扩容期间被读到半迁移的桶链）
     * 会陷入死循环把 CPU 打满。
     */
    private val found = ConcurrentHashMap<String, WaterDevice>()

    /** 设备首次被发现的次序。 */
    private val firstSeen = ConcurrentHashMap<String, Int>()

    /**
     * 排序键：**首次**发现该设备时的 RSSI。
     *
     * 刻意冻结而不是用实时 RSSI —— 实时值每秒都在抖，哪怕只差 1 dBm 也会让两行换位，
     * 用户手指落下的瞬间目标就跑了。冻结之后列表顺序一旦确定就不再变化，
     * 新设备只会在末尾追加，行不会移动。实时 RSSI 仍然显示在行里，只是不参与排序。
     */
    private val sortKey = ConcurrentHashMap<String, Int>()

    /**
     * 开始扫描。
     *
     * 原版用 `namePrefix` 覆盖全部字母数字来筛选设备（而不是按服务 UUID，
     * 因为不少水控器并不广播 0xF1F0）。这里沿用同样的策略。
     */
    @SuppressLint("MissingPermission")
    fun startScan(
        onUpdate: (List<WaterDevice>) -> Unit,
        onFailed: (Int) -> Unit,
        /** 扫描自然结束（超时）时回调；用户主动关闭面板不会触发。 */
        onStopped: () -> Unit,
    ) {
        if (!bluetoothReady()) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        stopScan()
        found.clear()
        firstSeen.clear()
        sortKey.clear()
        scanOnUpdate = onUpdate

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                if (name.isEmpty()) return
                if (!name[0].isLetterOrDigit()) return
                val address = result.device.address
                // 上限保护：异常环境（比如教学楼里几百个蓝牙设备）下避免列表无限膨胀
                if (found.size >= MAX_SCAN_RESULTS && !found.containsKey(address)) return
                // putIfAbsent 而非 "读-判断-写"：并发下后者会让多个设备拿到同一个序号
                if (firstSeen.putIfAbsent(address, firstSeen.size) == null) {
                    sortKey.putIfAbsent(address, result.rssi)
                }
                found[address] = WaterDevice(name, address, result.rssi, result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                onFailed(errorCode)
            }
        }
        scanCallback = callback
        scanner.startScan(null, settings, callback)

        // 按固定节奏推送列表，而不是每来一个广播就推一次。
        // 一个设备一秒可以广播十几次，之前列表会被反复按 RSSI 重排，
        // 手指还没落下目标就跳走了 —— 现在至少间隔 SCAN_UI_INTERVAL_MS 才刷新一次。
        onUpdate(emptyList())
        scanTicker = scope.launch {
            while (isActive) {
                delay(SCAN_UI_INTERVAL_MS)
                scanOnUpdate?.invoke(snapshot())
            }
        }

        // 超时自动停止。不这么做的话面板开着会一直扫 —— 既耗电，
        // 而且 Android 大约 30 分钟后会强制停掉未停止的扫描，
        // 那时界面还在转圈但列表早就冻住了，用户会以为"搜不到"。
        scanTimeoutJob = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            scanOnUpdate?.invoke(snapshot())   // 先推一次最终结果，再停
            stopScanInternal()
            onStopped()
        }
    }

    /**
     * 列表快照。排序键全部是不可变的历史值，所以同一个设备列表中，
     * 顺序一旦确定就**永远不会再变**，只会往末尾追加新设备。
     */
    private fun snapshot(): List<WaterDevice> = found.values.sortedWith(
        compareByDescending<WaterDevice> { sortKey[it.address] ?: Int.MIN_VALUE }
            .thenBy { firstSeen[it.address] ?: Int.MAX_VALUE },
    )

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        stopScanInternal()
    }

    /** 停止扫描但**不**取消超时任务 —— 超时任务自己收尾时必须用这个，否则会取消掉自己。 */
    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        scanTicker?.cancel()
        scanTicker = null
        scanOnUpdate = null
        scanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
        scanCallback = null
    }

    @SuppressLint("MissingPermission")
    fun deviceForAddress(address: String): BluetoothDevice? =
        runCatching { adapter?.getRemoteDevice(address) }.getOrNull()

    // =====================================================================
    // 开启会话
    // =====================================================================

    /**
     * 连接并开启一次用水。协程在收到 B2（启动成功）后正常返回；
     * 任何失败都以 [WaterCtlException] 抛出。
     */
    @SuppressLint("MissingPermission")
    suspend fun startSession(device: BluetoothDevice, nameHint: String? = null) {
        try {
            val name = device.name ?: nameHint ?: device.address
            deviceName = name
            startedAt = 0L
            intentionalDisconnect = false
            listener?.onDeviceNameChanged(name)

            setStage(ConnectionStage.CONNECTING)
            connect(device)
            listener?.onLog("GATT connected: $name")

            setStage(ConnectionStage.HANDSHAKING)
            prepareCharacteristics()
            enableNotifications()

            val result = CompletableDeferred<Unit>()
            sessionDeferred = result

            write(Payloads.START_PROLOGUE)
            armTimeout()

            val ok = withTimeoutOrNull(SESSION_WAIT_TIMEOUT_MS) { result.await() }
            sessionDeferred = null
            if (ok == null && !result.isCompleted) {
                throw WaterCtlException(WaterCtlErrorKind.TIMEOUT)
            }
        } catch (e: Throwable) {
            // 任何失败路径都必须释放 GATT。
            // 之前清理逻辑散落在各子步骤的"成功路径"上（每个 if-throw 前手动 teardown），
            // 异常路径会漏掉 —— 例如服务发现返回非 SUCCESS 时 servicesDeferred 被
            // completeExceptionally，await() 直接抛异常穿透出去，连接就永远不释放了。
            // 不要用 e.javaClass.simpleName —— release 经 R8 混淆后会变成单个字母
            // （日志里出现"启动失败: Y"这种毫无帮助的内容）。
            val brief = if (e is WaterCtlException) e.kind.name else (e.message ?: "未知异常")
            log("启动失败，已释放连接（$brief）")
            sessionDeferred = null
            teardown()
            throw e
        }
    }

    // =====================================================================
    // 结束会话
    // =====================================================================

    suspend fun endSession() {
        val d = CompletableDeferred<Unit>()
        endDeferred = d
        try {
            write(Payloads.END_PROLOGUE)
            // 这里刻意【不】调 armTimeout()。
            // 结束流程超时不应该报错 —— 用户已经按了结束，设备不回应时直接断开就行，
            // 弹一个"等待时间太长"只会让人以为没结束成功。
            val done = withTimeoutOrNull(END_WAIT_TIMEOUT_MS) { d.await() }
            if (done == null) log("结束指令未收到回应，直接断开")
        } catch (e: CancellationException) {
            endDeferred = null
            teardown()
            throw e
        } catch (e: WaterCtlException) {
            // fail() 已经上报过错误，这里只负责收尾
            log("结束流程异常（${e.kind}），直接断开")
        }
        endDeferred = null
        teardown()
    }

    /** 立刻断开并复位（用于用户取消、致命错误、Activity 销毁）。 */
    fun abort() {
        intentionalDisconnect = true
        sessionDeferred?.cancel()
        sessionDeferred = null
        endDeferred?.cancel()
        endDeferred = null
        teardown()
    }

    fun release() {
        startScanStop()
        abort()
        // 不取消 scope 的话，onCleared 之后在途的协程仍会存活并回调 listener
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun startScanStop() {
        runCatching { stopScan() }
    }

    // =====================================================================
    // GATT 内部实现
    // =====================================================================

    @SuppressLint("MissingPermission")
    private suspend fun connect(device: BluetoothDevice) {
        val connected = CompletableDeferred<Unit>()
        connectedDeferred = connected
        servicesDeferred = null
        descriptorDeferred = null

        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            ?: throw WaterCtlException(WaterCtlErrorKind.UNSTABLE)

        val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() }
        connectedDeferred = null
        // 清理统一由 startSession 的 catch 负责，这里只负责报错
        if (ok == null) throw WaterCtlException(WaterCtlErrorKind.UNSTABLE, "连接超时")
    }

    @SuppressLint("MissingPermission")
    private suspend fun prepareCharacteristics() {
        // 注意：await() 可能因为 onServicesDiscovered 返回非 SUCCESS 而【抛异常】，
        // 不只有"超时"这一种失败。清理统一由 startSession 的 catch 负责。
        val services = withTimeoutOrNull(SERVICE_TIMEOUT_MS) { servicesDeferred?.await() }
        if (services == null) throw WaterCtlException(WaterCtlErrorKind.UNSTABLE, "服务发现超时")

        val g = gatt ?: throw WaterCtlException(WaterCtlErrorKind.UNSTABLE)
        val service = g.getService(SERVICE_UUID)
            ?: throw WaterCtlException(WaterCtlErrorKind.UNSUPPORTED_MODEL)

        txd = service.getCharacteristic(TXD_UUID)
        rxd = service.getCharacteristic(RXD_UUID)
        if (txd == null || rxd == null) throw WaterCtlException(WaterCtlErrorKind.UNSUPPORTED_MODEL)
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableNotifications() {
        val g = gatt ?: throw WaterCtlException(WaterCtlErrorKind.UNSTABLE)
        val r = rxd ?: throw WaterCtlException(WaterCtlErrorKind.UNSUPPORTED_MODEL)

        if (!g.setCharacteristicNotification(r, true)) {
            throw WaterCtlException(WaterCtlErrorKind.UNSUPPORTED_MODEL)
        }

        val cccd = r.getDescriptor(CCCD_UUID)
        if (cccd != null) {
            val d = CompletableDeferred<Unit>()
            descriptorDeferred = d
            val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }
            if (!written) {
                descriptorDeferred = null
                throw WaterCtlException(WaterCtlErrorKind.UNSUPPORTED_MODEL)
            }
            withTimeoutOrNull(DESCRIPTOR_TIMEOUT_MS) { d.await() }
            descriptorDeferred = null
            // 这里超时也继续往下走 —— 不写 CCCD 是收不到通知的，
            // 但后续 15 秒的响应超时会统一兜底报错，不需要在这里额外处理。
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun write(data: ByteArray) {
        writeMutex.withLock {
            val g = gatt ?: throw WaterCtlException(WaterCtlErrorKind.UNSTABLE)
            val t = txd ?: throw WaterCtlException(WaterCtlErrorKind.UNSUPPORTED_MODEL)

            log("TXD: " + hex(data))

            val d = CompletableDeferred<Unit>()
            writeDeferred = d

            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(t, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    t.value = data
                    t.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    g.writeCharacteristic(t)
                }
            }

            if (!ok) {
                writeDeferred = null
                throw WaterCtlException(WaterCtlErrorKind.UNSTABLE, "写入失败")
            }

            val done = withTimeoutOrNull(WRITE_TIMEOUT_MS) { d.await() }
            writeDeferred = null
            if (done == null) throw WaterCtlException(WaterCtlErrorKind.UNSTABLE, "写入超时")
        }
    }

    /** 15 秒无响应即报超时 —— 与原版 `setupTimeoutMessage` 一致。 */
    private fun armTimeout() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(RESPONSE_TIMEOUT_MS)
            fail(WaterCtlException(WaterCtlErrorKind.TIMEOUT))
        }
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private fun setStage(stage: ConnectionStage) {
        listener?.onStageChanged(stage)
    }

    private fun log(line: String) {
        listener?.onLog(line)
    }

    private fun fail(error: WaterCtlException) {
        sessionDeferred?.completeExceptionally(error)
        // 结束会话时等的是 endDeferred。不一并 complete 的话，
        // 断开过程中的失败要让 endSession 一直挂到 20 秒超时才收尾。
        endDeferred?.completeExceptionally(error)
        listener?.onError(error)
    }

    @SuppressLint("MissingPermission")
    private fun teardown() {
        cancelTimeout()
        startEpilogueJob?.cancel()
        startEpilogueJob = null
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        txd = null
        rxd = null
        connectedDeferred = null
        servicesDeferred = null
        descriptorDeferred = null
        writeDeferred = null
        setStage(ConnectionStage.IDLE)
    }

    // =====================================================================
    // 通知处理 —— 与原版 handleRxdNotifications 一一对应
    // =====================================================================

    private suspend fun handleNotification(raw: ByteArray) {
        log("RXD: " + hex(raw))
        try {
            // 判定与执行分开：reactToNotification 是纯函数（见 RxReaction.kt），
            // 可以被单测完整覆盖；这里只负责把判定结果落到真实设备上。
            execute(
                reactToNotification(
                    raw = raw,
                    deviceName = deviceName,
                    startedAt = startedAt,
                    now = System.currentTimeMillis(),
                ),
            )
        } catch (e: WaterCtlException) {
            fail(e)
        } catch (e: CancellationException) {
            // 会话被主动取消：不是故障，原样抛出，交由上层处理
            throw e
        } catch (e: Throwable) {
            fail(WaterCtlException(WaterCtlErrorKind.UNHANDLED, e.message, e))
        }
    }

    /** 执行判定结果。这里只做副作用，不含任何分支判断。 */
    private suspend fun execute(rx: RxReaction) {
        when (rx) {
            RxReaction.Ignore, RxReaction.Malformed -> Unit

            is RxReaction.Write -> {
                rx.stage?.let { setStage(it) }
                write(rx.data)
            }

            is RxReaction.WriteStartEpilogue -> {
                startEpilogueJob?.cancel()
                if (rx.delayMs <= 0L) {
                    setStage(ConnectionStage.STARTING)
                    write(Solvers.makeStartEpilogue(deviceName, rx.keyAuth))
                } else {
                    // 延迟期间可能收到 AE（新固件），届时要能取消 —— 所以必须走 job
                    startEpilogueJob = scope.launch {
                        delay(rx.delayMs)
                        setStage(ConnectionStage.STARTING)
                        write(Solvers.makeStartEpilogue(deviceName, rx.keyAuth))
                    }
                }
            }

            is RxReaction.WriteStartEpilogueThenFail -> {
                startEpilogueJob?.cancel()
                setStage(ConnectionStage.STARTING)
                write(Solvers.makeStartEpilogue(deviceName, isKeyAuthPresent = true))
                throw WaterCtlException(rx.kind)
            }

            is RxReaction.Ready -> {
                startEpilogueJob?.cancel()
                cancelTimeout()
                startedAt = rx.startedAt
                setStage(ConnectionStage.ACTIVE)
                listener?.onSessionStarted(rx.startedAt)
                sessionDeferred?.complete(Unit)
            }

            is RxReaction.Ended -> {
                runCatching { write(Payloads.END_EPILOGUE) }
                // 会话可能是设备主动结束的（此时 endDeferred 为 null，
                // endSession() 根本没被调用），所以时长已在判定阶段算好并带过来。
                intentionalDisconnect = true
                teardown()
                endDeferred?.complete(Unit)
                listener?.onSessionEnded(rx.durationSeconds)
            }

            is RxReaction.Fail -> throw WaterCtlException(rx.kind)
        }
    }

    // =====================================================================
    // GATT 回调
    // =====================================================================

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDeferred?.complete(Unit)
                    val started = CompletableDeferred<Unit>()
                    servicesDeferred = started
                    if (!g.discoverServices()) {
                        servicesDeferred = null
                        fail(WaterCtlException(WaterCtlErrorKind.UNSUPPORTED_MODEL))
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (!intentionalDisconnect) {
                        fail(WaterCtlException(WaterCtlErrorKind.UNSTABLE, "连接已断开"))
                    }
                    connectedDeferred?.completeExceptionally(
                        WaterCtlException(WaterCtlErrorKind.UNSTABLE),
                    )
                    runCatching { g.close() }
                    if (gatt === g) gatt = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                servicesDeferred?.complete(Unit)
            } else {
                servicesDeferred?.completeExceptionally(
                    WaterCtlException(WaterCtlErrorKind.UNSUPPORTED_MODEL),
                )
                servicesDeferred = null
                fail(WaterCtlException(WaterCtlErrorKind.UNSUPPORTED_MODEL))
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descriptorDeferred?.complete(Unit)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                writeDeferred?.complete(Unit)
            } else {
                writeDeferred?.completeExceptionally(
                    WaterCtlException(WaterCtlErrorKind.UNSTABLE, "写入失败 status=$status"),
                )
                writeDeferred = null
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            dispatchNotification(value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            dispatchNotification(value)
        }
    }

    private fun dispatchNotification(value: ByteArray) {
        scope.launch {
            handlerMutex.withLock { handleNotification(value) }
        }
    }

    private fun hex(data: ByteArray): String =
        data.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    companion object {
        /** 扫描结果推送到界面的最小间隔 —— 太快会导致列表跳动、点不中目标。 */
        private const val SCAN_UI_INTERVAL_MS = 1_000L

        /** 扫描最长持续时间，到点自动停止（省电，也避免被系统强制中断后界面假死）。 */
        private const val SCAN_TIMEOUT_MS = 30_000L

        /** 扫描列表上限，异常环境（教学楼里几百个蓝牙设备）下防止无限膨胀。 */
        private const val MAX_SCAN_RESULTS = 50

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val SERVICE_TIMEOUT_MS = 8_000L
        private const val DESCRIPTOR_TIMEOUT_MS = 5_000L
        private const val WRITE_TIMEOUT_MS = 5_000L
        private const val RESPONSE_TIMEOUT_MS = 15_000L
        private const val SESSION_WAIT_TIMEOUT_MS = 40_000L
        private const val END_WAIT_TIMEOUT_MS = 20_000L
    }
}
