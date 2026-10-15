package dev.waterctl.app.core.ble

import dev.waterctl.app.core.protocol.Payloads
import dev.waterctl.app.core.protocol.Solvers
import dev.waterctl.app.core.protocol.WaterCtlErrorKind
import dev.waterctl.app.core.protocol.WaterCtlException

/**
 * 一帧通知应该引发的动作 —— **纯数据，不含任何副作用**。
 *
 * 把"该怎么反应"与"怎么执行"分开，是为了让这套状态机能脱离
 * `BluetoothGatt` 单独测试。之前它和 GATT 回调缠在一起，
 * 导致 `B0/B1`、`AE`、`AF` 各分支、`0xC8` 这些最需要覆盖的路径一个测试都没有。
 */
internal sealed interface RxReaction {

    /** 忽略：固件偶发的 AT 命令，或已知道但确实无需回应的类型（`AA` / `B5` / `B8`）。 */
    data object Ignore : RxReaction

    /** 帧长不足以判断类型（< 4 字节），不当作错误。 */
    data object Malformed : RxReaction

    /**
     * 立即写出这段数据。
     *
     * @param stage 若非 null，写之前先把连接阶段推进到它（用于驱动三步进度卡）
     */
    class Write(
        val data: ByteArray,
        val stage: ConnectionStage? = null,
    ) : RxReaction {
        override fun equals(other: Any?): Boolean =
            other is Write && data.contentEquals(other.data) && stage == other.stage

        override fun hashCode(): Int = 31 * data.contentHashCode() + (stage?.hashCode() ?: 0)

        override fun toString(): String =
            "Write(${data.joinToString("") { "%02X".format(it) }}${stage?.let { ", $it" } ?: ""})"
    }

    /**
     * 写启动帧。
     *
     * @param delayMs 老固件需要延迟 500ms 再发（这段时间内可能收到 `AE`，届时必须能取消）
     * @param keyAuth 新固件通过密钥认证后为 true（魔数 mn = 0x0B），老固件为 false（0xFF）
     */
    data class WriteStartEpilogue(val delayMs: Long, val keyAuth: Boolean) : RxReaction

    /** 写启动帧之后仍要报错（`AF` 返回了未知的结果码）。 */
    data class WriteStartEpilogueThenFail(val kind: WaterCtlErrorKind) : RxReaction

    /** 启动成功。 */
    data class Ready(val startedAt: Long) : RxReaction

    /** 设备侧结束了会话。 */
    data class Ended(val durationSeconds: Int) : RxReaction

    /** 失败。 */
    data class Fail(val kind: WaterCtlErrorKind) : RxReaction
}

/** 老固件在 `B0/B1` 之后需要等待的时长，取自原项目。 */
internal const val START_EPILOGUE_DELAY_MS = 500L

/**
 * 把一帧原始通知**规范化**成可以直接按 `payload[3]` 分派的形态。
 *
 * 处理两个固件 bug：
 * - 有时会主动发一条 `AT+` 命令 —— 返回 null 表示整帧忽略
 * - 偶尔丢掉前 1~2 个字节，需要把 `FD FD 09` 补回来
 *
 * @throws WaterCtlException 首字节不是 `FD` 也不是 `09`（UNKNOWN_RX）
 */
internal fun normaliseRx(raw: ByteArray): ByteArray? {
    if (raw.isEmpty()) return null

    if (raw.size >= 3 &&
        raw[0].toInt() == 0x41 && // 'A'
        raw[1].toInt() == 0x54 && // 'T'
        raw[2].toInt() == 0x2b    // '+'
    ) {
        return null
    }

    val first = raw[0].toInt() and 0xFF
    if (first != 0xFD && first != 0x09) {
        throw WaterCtlException(WaterCtlErrorKind.UNKNOWN_RX)
    }

    var payload = raw
    if (payload.size >= 2 && (payload[1].toInt() and 0xFF) == 0x09) {
        payload = byteArrayOf(0xFD.toByte()) + payload
    }
    if ((payload[0].toInt() and 0xFF) == 0x09) {
        payload = byteArrayOf(0xFD.toByte(), 0xFD.toByte()) + payload
    }
    return payload
}

/**
 * 判断一帧通知应该引发什么动作。
 *
 * 分支逐条对应原项目 `bluetooth.ts` 的 `handleRxdNotifications`，
 * 包括 `0xAF` 的四种结果码与 `nonce` 回绕等细节。
 *
 * @param raw        原始通知字节
 * @param deviceName 蓝牙设备名（参与校验和与密钥掩码计算）
 * @param startedAt  会话开始时刻；0 表示尚未开始
 * @param now        当前时刻，用于计算会话时长
 */
internal fun reactToNotification(
    raw: ByteArray,
    deviceName: String,
    startedAt: Long,
    now: Long,
): RxReaction {
    // 刻意让这个函数是"全函数"（不抛异常）—— 所有失败都通过 RxReaction.Fail 表达，
    // 调用方和测试都只需要看返回值。
    val payload = try {
        normaliseRx(raw) ?: return RxReaction.Ignore
    } catch (e: WaterCtlException) {
        return RxReaction.Fail(e.kind)
    }
    if (payload.size < 4) return RxReaction.Malformed

    return when (payload[3].toInt() and 0xFF) {
        // 启动前导已被接受。老固件到此为止；新固件会在 500ms 内发 AE，
        // 所以这里的延迟发送必须可被取消。
        0xB0, 0xB1 -> RxReaction.WriteStartEpilogue(
            delayMs = START_EPILOGUE_DELAY_MS,
            keyAuth = false,
        )

        // 新固件的密钥认证请求
        0xAE -> if (payload.size < 10) {
            // 短帧构造不出响应。当作未知数据，而不是让 require 抛出 IllegalArgumentException。
            RxReaction.Fail(WaterCtlErrorKind.UNKNOWN_RX)
        } else {
            RxReaction.Write(
                data = Solvers.makeUnlockResponse(payload, deviceName),
                stage = ConnectionStage.STARTING,
            )
        }

        // 密钥认证结果
        0xAF -> when (if (payload.size > 5) payload[5].toInt() and 0xFF else -1) {
            0x55 -> RxReaction.WriteStartEpilogue(delayMs = 0L, keyAuth = true)
            0x01, 0x02, 0x04 -> RxReaction.Fail(WaterCtlErrorKind.REFUSED)
            // 未知结果码：原版仍会发启动帧，然后报"未知数据"
            else -> RxReaction.WriteStartEpilogueThenFail(WaterCtlErrorKind.UNKNOWN_RX)
        }

        // 启动成功
        0xB2 -> RxReaction.Ready(now)

        // 结束前导已被接受
        0xB3 -> RxReaction.Ended(
            if (startedAt > 0L) ((now - startedAt) / 1000L).toInt() else 0,
        )

        // 遥测 / 温度设置 / 未知 —— 均无需回应
        0xAA, 0xB5, 0xB8 -> RxReaction.Ignore

        // 用户信息上传请求：回执，但我们永远不会真的上传
        0xBA -> RxReaction.Write(Payloads.BA_ACK)

        // 上一次未完成的离线会话残留
        0xBC -> RxReaction.Write(Payloads.OFFLINE_BOMB_FIX)

        // 启动被拒
        0xC8 -> RxReaction.Fail(WaterCtlErrorKind.REFUSED)

        else -> RxReaction.Fail(WaterCtlErrorKind.UNKNOWN_RX)
    }
}
