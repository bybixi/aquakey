package dev.waterctl.app.core.protocol

import java.util.Calendar
import java.util.TimeZone

/**
 * 报文求解器，逐行移植自原项目 `solvers.ts`。
 */
internal object Solvers {

    /**
     * 十进制数转 BCD 字节：`42 => 0x42`
     * 与原实现一致地按位递归，负数和小数部分不做保护（调用方保证非负整数）。
     */
    fun decAsHex(n: Int): Byte = if (n <= 0) 0 else ((n % 10) or (decAsHex(n / 10).toInt() shl 4)).toByte()

    /** 随机用户号：`XYZW`（十进制）=> `[0xXY, 0xZW]` */
    fun randomUserId(): ByteArray {
        val n = (1..9999).random()
        return byteArrayOf(decAsHex(n shr 8), decAsHex(n and 0xFF))
    }

    /**
     * 时间戳：`2013/1/11 12:34:56 => [0x13, 0x01, 0x11, 0x12, 0x34, 0x56]`
     *
     * 原实现用 `toLocaleString("zh-CN", { timeZone: "Asia/Shanghai" })`，
     * 这里显式固定到 Asia/Shanghai，保证任何时区下发的都是北京时间。
     */
    fun datetimeArray(): ByteArray {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        val year = cal.get(Calendar.YEAR) % 100
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)
        return byteArrayOf(
            decAsHex(year), decAsHex(month), decAsHex(day),
            decAsHex(hour), decAsHex(minute), decAsHex(second),
        )
    }

    /**
     * 启动帧（B2）—— 真正开启会话的命令。
     *
     * @param deviceName 蓝牙设备名，校验和取其后 5 位
     * @param isKeyAuthPresent 新固件通过密钥认证后传 true，此时魔数 mn 为 0x0B（老固件为 0xFF）
     */
    fun makeStartEpilogue(deviceName: String, isKeyAuthPresent: Boolean = false): ByteArray {
        val checksum = Crc.changGong(deviceName.takeLast(5))
        val mn = if (isKeyAuthPresent) 0x0B else 0xFF
        val ri = randomUserId()
        val dt = datetimeArray()
        return byteArrayOf(
            0xFE.toByte(), 0xFE.toByte(), 0x09, 0xB2.toByte(),
            0x01,
            (checksum and 0xFF).toByte(), ((checksum shr 8) and 0xFF).toByte(),
            mn.toByte(),
            0x00,
            ri[0], ri[1],
            dt[0], dt[1], dt[2], dt[3], dt[4], dt[5],
            0x0F, 0x27, 0x00,
        )
    }

    /**
     * 密钥认证响应（AF）—— 只在新固件收到 AE 时使用。
     *
     * 布局：`[unknownByte][newNonce:2][key:4][FE 87 00 00][00 00 00 00]`，前面再加 1 字节校验和。
     */
    fun makeUnlockResponse(unlockRequest: ByteArray, deviceName: String): ByteArray {
        require(unlockRequest.size >= 10) { "WATERCTL INTERNAL Bad unlock request" }

        val unknownByte = unlockRequest[5].toInt() and 0xFF
        val nonceHi = unlockRequest[6].toInt() and 0xFF
        val nonceLo = unlockRequest[7].toInt() and 0xFF
        val macHi = unlockRequest[8].toInt() and 0xFF
        val macLo = unlockRequest[9].toInt() and 0xFF

        // nonce 自增 1；0xFFFF 时回绕成 0x0100 —— 原实现的 bug-for-bug 兼容行为
        val nonce = (nonceHi shl 8) or nonceLo
        val newNonceHi: Int
        val newNonceLo: Int
        if (nonce == 0xFFFF) {
            newNonceHi = 0x01
            newNonceLo = 0x00
        } else {
            val next = nonce + 1
            newNonceHi = (next shr 8) and 0xFF
            newNonceLo = next and 0xFF
        }

        val rawKey = Deputy.makeKey(nonceHi, nonceLo, macHi, macLo)

        // 掩码 = 设备名后 4 位字符各减去 0x30（与原实现一致，非数字字符不做校验）
        val name = deviceName.takeLast(4)
        val key = ByteArray(4) { i ->
            val mask = if (i < name.length) name[i].code - 0x30 else 0
            (rawKey[i].toInt() xor mask).toByte()
        }

        val checksumInput = ByteArray(15)
        checksumInput[0] = unknownByte.toByte()
        checksumInput[1] = newNonceHi.toByte()
        checksumInput[2] = newNonceLo.toByte()
        checksumInput[3] = key[0]
        checksumInput[4] = key[1]
        checksumInput[5] = key[2]
        checksumInput[6] = key[3]
        checksumInput[7] = 0xFE.toByte()
        checksumInput[8] = 0x87.toByte()
        // 9..14 保持 0x00

        val checksum = Crc.cgAeAf(checksumInput)

        val out = ByteArray(20)
        out[0] = 0xFE.toByte()
        out[1] = 0xFE.toByte()
        out[2] = 0x09
        out[3] = 0xAF.toByte()
        out[4] = checksum.toByte()
        System.arraycopy(checksumInput, 0, out, 5, 15)
        return out
    }
}
