package dev.waterctl.app.core.protocol

/**
 * 两种 CRC-16 变体。参数、初值、多项式、输出异或值都与原项目 `algorithms.ts` 完全一致。
 */
internal object Crc {

    /**
     * CRC-16/ChangGong
     * width=16 poly=0x8005 init=0xe808 refin=true refout=true xorout=0x0000
     *
     * 注意 refIn/refOut 为 true，所以实现里把初值预反射成 0x1017 后做右移。
     */
    fun changGong(s: String): Int {
        var crc = 0x1017
        for (ch in s) {
            crc = crc xor ch.code
            for (j in 0 until 8) {
                crc = if (crc and 1 == 1) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }

    /**
     * CRC-16/CGAEAF（ChangGong AE/AF 专用）
     * width=16 poly=0x8005 init=0xf856 refin=true refout=true xorout=0x0075，截断为低 8 位。
     */
    fun cgAeAf(array: ByteArray): Int {
        var crc = 0x6A1F
        for (b in array) {
            crc = crc xor (b.toInt() and 0xFF)
            for (j in 0 until 8) {
                crc = if (crc and 1 == 1) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return (crc xor 0x75) and 0xFF
    }
}
