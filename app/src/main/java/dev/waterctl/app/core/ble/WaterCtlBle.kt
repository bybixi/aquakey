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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

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
    private var scanOnUpdate: ((List<WaterDevice>) -> Unit)? = null
    private val found = LinkedHashMap<String, WaterDevice>()
    /** 设备首次被发现的次序。 */
    private val firstSeen = HashMap<String, Int>()

    /**
     * 排序键：**首次**发现该设备时的 RSSI。
     *
     * 刻意冻结而不是用实时 RSSI —— 实时值每秒都在抖，哪怕只差 1 dBm 也会让两行换位，
     * 用户手指落下的瞬间目标就跑了。冻结之后列表顺序一旦确定就不再变化，
     * 新设备只会在末尾追加，行不会移动。实时 RSSI 仍然显示在行里，只是不参与排序。
     */
    private val sortKey = HashMap<String, Int>()

    /**
     * 开始扫描。
     *
     * 原版用 `namePrefix` 覆盖全部字母数字来筛选设备（而不是按服务 UUID，
     * 因为不少水控器并不广播 0xF1F0）。这里沿用同样的策略。
     */
    @SuppressLint("MissingPermission")
    fun startScan(onUpdate: (List<WaterDevice>) -> Unit, onFailed: (Int) -> Unit) {
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
                if (firstSeen[address] == null) {
                    firstSeen[address] = firstSeen.size
                    sortKey[address] = result.rssi
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
    }

    // =====================================================================
    // 结束会话
    // =====================================================================

    suspend fun endSession() {
        val d = CompletableDeferred<Unit>()
        endDeferred = d
        write(Payloads.END_PROLOGUE)
        armTimeout()
        withTimeoutOrNull(END_WAIT_TIMEOUT_MS) { d.await() }
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
        if (ok == null) {
            teardown()
            throw WaterCtlException(WaterCtlErrorKind.UNSTABLE, "连接超时")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun prepareCharacteristics() {
        val services = withTimeoutOrNull(SERVICE_TIMEOUT_MS) { servicesDeferred?.await() }
        if (services == null) {
            teardown()
            throw WaterCtlException(WaterCtlErrorKind.UNSTABLE, "服务发现超时")
        }

        val g = gatt ?: throw WaterCtlException(WaterCtlErrorKind.UNSTABLE)
        val service = g.getService(SERVICE_UUID)
            ?: run {
                teardown()
                throw WaterCtlException(WaterCtlErrorKind.UNSUPPORTED_MODEL)
            }

        txd = service.getCharacteristic(TXD_UUID)
        rxd = service.getCharacteristic(RXD_UUID)
        if (txd == null || rxd == null) {
            teardown()
            throw WaterCtlException(WaterCtlErrorKind.UNSUPPORTED_MODEL)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableNotifications() {
        val g = gatt ?: throw WaterCtlException(WaterCtlErrorKind.UNSTABLE)
        val r = rxd ?: throw WaterCtlException(WaterCtlErrorKind.UNSUPPORTED_MODEL)

        if (!g.setCharacteristicNotification(r, true)) {
            teardown()
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
                teardown()
                throw WaterCtlException(WaterCtlErrorKind.UNSUPPORTED_MODEL)
            }
            withTimeoutOrNull(DESCRIPTOR_TIMEOUT_MS) { d.await() }
            descriptorDeferred = null
            // 描述符写失败不致命：部分机型仍能收到通知，原版也没有这一步。
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
            var payload = raw
            if (payload.isEmpty()) return

            // 固件 bug：有时会主动发一条 AT 命令，直接忽略
            if (payload.size >= 3 &&
                payload[0].toInt() == 0x41 &&
                payload[1].toInt() == 0x54 &&
                payload[2].toInt() == 0x2b
            ) {
                return
            }

            val first = payload[0].toInt() and 0xFF
            if (first != 0xFD && first != 0x09) {
                throw WaterCtlException(WaterCtlErrorKind.UNKNOWN_RX)
            }

            // 固件 bug：偶尔丢掉前 1~2 个字节，这里补回来
            // （原版靠 JS 越界读 undefined 自然跳过，Kotlin 里必须显式判长）
            if (payload.size >= 2 && (payload[1].toInt() and 0xFF) == 0x09) {
                payload = byteArrayOf(0xFD.toByte()) + payload
            }
            if ((payload[0].toInt() and 0xFF) == 0x09) {
                payload = byteArrayOf(0xFD.toByte(), 0xFD.toByte()) + payload
            }

            if (payload.size < 4) return

            when (payload[3].toInt() and 0xFF) {
                // 启动前导已被接受。老固件到此为止；新固件会在 500ms 内发 AE，
                // 所以这里的延迟发送必须可被取消（与原版 pendingStartEpilogue 同理）。
                0xB0, 0xB1 -> {
                    startEpilogueJob?.cancel()
                    startEpilogueJob = scope.launch {
                        delay(500)
                        setStage(ConnectionStage.STARTING)
                        write(Solvers.makeStartEpilogue(deviceName))
                    }
                }

                // 新固件的密钥认证请求
                0xAE -> {
                    startEpilogueJob?.cancel()
                    setStage(ConnectionStage.STARTING)
                    write(Solvers.makeUnlockResponse(payload, deviceName))
                }

                // 密钥认证结果
                0xAF -> {
                    val code = if (payload.size > 5) payload[5].toInt() and 0xFF else -1
                    when (code) {
                        0x55 -> write(Solvers.makeStartEpilogue(deviceName, isKeyAuthPresent = true))
                        0x01, 0x02, 0x04 -> throw WaterCtlException(WaterCtlErrorKind.REFUSED)
                        else -> {
                            write(Solvers.makeStartEpilogue(deviceName, isKeyAuthPresent = true))
                            throw WaterCtlException(WaterCtlErrorKind.UNKNOWN_RX)
                        }
                    }
                }

                // 启动成功
                0xB2 -> {
                    startEpilogueJob?.cancel()
                    cancelTimeout()
                    startedAt = System.currentTimeMillis()
                    setStage(ConnectionStage.ACTIVE)
                    listener?.onSessionStarted(startedAt)
                    sessionDeferred?.complete(Unit)
                }

                // 结束前导已被接受：回 endEpilogue 然后断开
                0xB3 -> {
                    runCatching { write(Payloads.END_EPILOGUE) }
                    intentionalDisconnect = true
                    teardown()
                    endDeferred?.complete(Unit)
                    listener?.onSessionEnded(0)
                }

                // 遥测 / 温度设置 / 未知 —— 均无需回应
                0xAA, 0xB5, 0xB8 -> Unit

                // 用户信息上传请求：回执但我们永远不会真的上传
                0xBA -> write(Payloads.BA_ACK)

                // 上一次未完成的离线会话残留
                0xBC -> write(Payloads.OFFLINE_BOMB_FIX)

                // 启动被拒
                0xC8 -> throw WaterCtlException(WaterCtlErrorKind.REFUSED)

                else -> throw WaterCtlException(WaterCtlErrorKind.UNKNOWN_RX)
            }
        } catch (e: WaterCtlException) {
            fail(e)
        } catch (e: CancellationException) {
            // 会话被主动取消：不是故障，原样抛出，交由上层处理
            throw e
        } catch (e: Throwable) {
            fail(WaterCtlException(WaterCtlErrorKind.UNHANDLED, e.message, e))
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

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val SERVICE_TIMEOUT_MS = 8_000L
        private const val DESCRIPTOR_TIMEOUT_MS = 5_000L
        private const val WRITE_TIMEOUT_MS = 5_000L
        private const val RESPONSE_TIMEOUT_MS = 15_000L
        private const val SESSION_WAIT_TIMEOUT_MS = 40_000L
        private const val END_WAIT_TIMEOUT_MS = 20_000L
    }
}
