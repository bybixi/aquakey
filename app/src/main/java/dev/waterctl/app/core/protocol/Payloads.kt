package dev.waterctl.app.core.protocol

/**
 * 固定报文，逐字节取自原项目 `payloads.ts`。
 */
internal object Payloads {

    /** 最先发送；随后等待 B0 / B1（老固件）或 AE（新固件密钥认证请求）。 */
    val START_PROLOGUE = byteArrayOf(
        0xFE.toByte(), 0xFE.toByte(), 0x09, 0xB0.toByte(),
        0x01, 0x01, 0x00, 0x00,
    )

    /** 结束会话：发送后等待 B3。 */
    val END_PROLOGUE = byteArrayOf(
        0xFE.toByte(), 0xFE.toByte(), 0x09, 0xB3.toByte(),
        0x00, 0x00,
    )

    /** 收到 B3 后发送，然后断开。 */
    val END_EPILOGUE = byteArrayOf(
        0xFE.toByte(), 0xFE.toByte(), 0x09, 0xB4.toByte(),
        0x00, 0x00,
    )

    /** 收到 BC（上一次离线会话残留）时发送，用于清理。 */
    val OFFLINE_BOMB_FIX = byteArrayOf(
        0xFE.toByte(), 0xFE.toByte(), 0x09, 0xBC.toByte(),
        0x00, 0x00,
    )

    /** 收到 BA（用户信息上传请求）时回执；我们不会真的上传任何东西。 */
    val BA_ACK = byteArrayOf(
        0xFE.toByte(), 0xFE.toByte(), 0x09, 0xBA.toByte(),
        0x00, 0x00,
    )
}
