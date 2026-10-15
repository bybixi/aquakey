package dev.waterctl.app.core.ble

import dev.waterctl.app.core.protocol.Payloads
import dev.waterctl.app.core.protocol.WaterCtlErrorKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 蓝牙通知状态机的单元测试。
 *
 * 这套状态机之前完全没有覆盖 —— 而代码审查发现的几个缺陷（会话中可重复发起、
 * 结束超时误报、设备主动结束时丢记录）恰好都落在这一带。
 *
 * 判定逻辑已抽成纯函数 [reactToNotification]，所以这里不需要 BluetoothGatt。
 */
class RxReactionTest {

    private val device = "Water33982"

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** 构造一帧标准的 RXD 通知：`FD FD 09 <type> <payload...>` */
    private fun frame(type: Int, vararg tail: Int) =
        bytes(0xFD, 0xFD, 0x09, type, *tail)

    private fun react(
        raw: ByteArray,
        startedAt: Long = 0L,
        now: Long = 0L,
    ) = reactToNotification(raw, device, startedAt, now)

    // =====================================================================
    // 帧规范化
    // =====================================================================

    @Test
    fun `空帧被忽略`() {
        assertEquals(RxReaction.Ignore, react(ByteArray(0)))
    }

    @Test
    fun `固件主动发来的 AT 命令被忽略`() {
        // 原项目里就有这个固件 bug：会突然发一条 "AT+" 开头的命令
        assertEquals(RxReaction.Ignore, react(bytes(0x41, 0x54, 0x2b, 0x01, 0x02)))
    }

    @Test
    fun `首字节既不是 FD 也不是 09 时报未知数据`() {
        assertEquals(
            RxReaction.Fail(WaterCtlErrorKind.UNKNOWN_RX),
            react(bytes(0x12, 0x34, 0x56, 0x78)),
        )
    }

    @Test
    fun `帧长不足 4 字节时判为 Malformed 而不是错误`() {
        assertEquals(RxReaction.Malformed, react(bytes(0xFD, 0xFD, 0x09)))
    }

    @Test
    fun `固件漏发 1 个前导字节时能补回来`() {
        val full = frame(0xB0)
        val dropped = full.copyOfRange(1, full.size) // 丢掉第一个 FD
        assertEquals(react(full), react(dropped))
    }

    @Test
    fun `固件漏发 2 个前导字节时能补回来`() {
        val full = frame(0xB0)
        val dropped = full.copyOfRange(2, full.size) // 丢掉 FD FD，只剩 09 开头
        assertEquals(react(full), react(dropped))
    }

    // =====================================================================
    // B0 / B1 —— 老固件路径
    // =====================================================================

    @Test
    fun `B0 与 B1 都要求延迟 500ms 后发老固件启动帧`() {
        val expected = RxReaction.WriteStartEpilogue(
            delayMs = START_EPILOGUE_DELAY_MS,
            keyAuth = false,
        )
        assertEquals(expected, react(frame(0xB0)))
        assertEquals(expected, react(frame(0xB1)))
    }

    @Test
    fun `延迟常量就是原项目的 500ms`() {
        // 这个数字改了会影响与老固件的时序配合，值得钉住
        assertEquals(500L, START_EPILOGUE_DELAY_MS)
    }

    // =====================================================================
    // AE / AF —— 新固件密钥认证
    // =====================================================================

    @Test
    fun `AE 请求触发密钥认证响应，且载荷与协议层向量逐字节一致`() {
        // 输入取自原项目 solvers.spec.ts 的第一个用例
        val request = bytes(
            0xFD, 0xFD, 0x09, 0xAE,
            0x38, 0x00, 0x00, 0x05, 0x95, 0x27,
            0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x10, 0x11, 0x12,
        )
        val expected = bytes(
            0xFE, 0xFE, 0x09, 0xAF,
            0x72, 0x00, 0x00, 0x06, 0xC3, 0x50, 0x9A, 0xC4,
            0xFE, 0x87, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )

        val rx = react(request)
        assertTrue("应为 Write，实际是 $rx", rx is RxReaction.Write)
        rx as RxReaction.Write
        assertArrayEquals(expected, rx.data)
        assertEquals(ConnectionStage.STARTING, rx.stage)
    }

    @Test
    fun `过短的 AE 帧报未知数据而不是崩溃`() {
        // 之前这里会抛 IllegalArgumentException，最终把
        // "WATERCTL INTERNAL Bad unlock request" 这种内部信息弹给用户
        assertEquals(
            RxReaction.Fail(WaterCtlErrorKind.UNKNOWN_RX),
            react(frame(0xAE, 0x00, 0x01, 0x02)),
        )
    }

    /**
     * 结果码位于 `payload[5]`（原版也是读这个下标）。
     *
     * 注意 `frame()` 会自动补上 `FD FD 09 <type>` 这 4 个字节，
     * 所以 `frame(0xAF, 0xAA, 0x55)` 得到的 payload 是：
     * `[0]=FD [1]=FD [2]=09 [3]=AF [4]=0xAA [5]=0x55`
     */
    @Test
    fun `AF 返回 0x55 时立即发新固件启动帧`() {
        assertEquals(
            RxReaction.WriteStartEpilogue(delayMs = 0L, keyAuth = true),
            react(frame(0xAF, 0xAA, 0x55)),
        )
    }

    @Test
    fun `AF 返回 0x01 0x02 0x04 都判为被拒绝`() {
        for (code in listOf(0x01, 0x02, 0x04)) {
            assertEquals(
                "结果码 0x%02X".format(code),
                RxReaction.Fail(WaterCtlErrorKind.REFUSED),
                react(frame(0xAF, 0xAA, code)),
            )
        }
    }

    @Test
    fun `AF 返回未知结果码时先发启动帧再报未知数据`() {
        assertEquals(
            RxReaction.WriteStartEpilogueThenFail(WaterCtlErrorKind.UNKNOWN_RX),
            react(frame(0xAF, 0xAA, 0x77)),
        )
    }

    @Test
    fun `AF 的结果码只认 payload5，第 4 位不参与判断`() {
        // payload[4] 换个值不应影响判定
        assertEquals(
            RxReaction.WriteStartEpilogue(delayMs = 0L, keyAuth = true),
            react(frame(0xAF, 0x00, 0x55)),
        )
        assertEquals(
            RxReaction.Fail(WaterCtlErrorKind.REFUSED),
            react(frame(0xAF, 0xFF, 0x01)),
        )
    }

    @Test
    fun `AF 帧缺少结果码时按未知处理`() {
        // 只有 4 字节，没有 payload[5]
        assertEquals(
            RxReaction.WriteStartEpilogueThenFail(WaterCtlErrorKind.UNKNOWN_RX),
            react(frame(0xAF)),
        )
    }

    // =====================================================================
    // B2 / B3 —— 会话生命周期
    // =====================================================================

    @Test
    fun `B2 表示启动成功并带出开始时刻`() {
        assertEquals(RxReaction.Ready(startedAt = 123_456L), react(frame(0xB2), now = 123_456L))
    }

    @Test
    fun `B3 算出本次会话时长`() {
        val started = 1_700_000_000_000L
        val now = started + 18_500L // 18.5 秒
        assertEquals(RxReaction.Ended(durationSeconds = 18), react(frame(0xB3), startedAt = started, now = now))
    }

    @Test
    fun `会话未开始时 B3 的时长为 0`() {
        // 启动前就收到 B3（例如上一轮会话的残留），不应算出负数或异常值
        assertEquals(RxReaction.Ended(durationSeconds = 0), react(frame(0xB3), startedAt = 0L, now = 999_999L))
    }

    // =====================================================================
    // 无需回应 / 回执 / 拒绝
    // =====================================================================

    @Test
    fun `AA B5 B8 都是无需回应的遥测类帧`() {
        for (type in listOf(0xAA, 0xB5, 0xB8)) {
            assertEquals("类型 0x%02X".format(type), RxReaction.Ignore, react(frame(type)))
        }
    }

    @Test
    fun `BA 回执为固定的 BA_ACK`() {
        val rx = react(frame(0xBA))
        assertTrue(rx is RxReaction.Write)
        assertArrayEquals(Payloads.BA_ACK, (rx as RxReaction.Write).data)
    }

    @Test
    fun `BC 触发离线残留清理帧`() {
        val rx = react(frame(0xBC))
        assertTrue(rx is RxReaction.Write)
        assertArrayEquals(Payloads.OFFLINE_BOMB_FIX, (rx as RxReaction.Write).data)
    }

    @Test
    fun `C8 表示启动被拒`() {
        assertEquals(RxReaction.Fail(WaterCtlErrorKind.REFUSED), react(frame(0xC8)))
    }

    @Test
    fun `未知类型报未知数据`() {
        for (type in listOf(0x00, 0x77, 0xFE, 0xB9)) {
            assertEquals(
                "类型 0x%02X".format(type),
                RxReaction.Fail(WaterCtlErrorKind.UNKNOWN_RX),
                react(frame(type)),
            )
        }
    }

    @Test
    fun `判定函数对任意输入都不抛异常`() {
        // reactToNotification 刻意设计成"全函数"：所有失败都通过返回值表达。
        // 随机扫一遍字节空间，确认不会漏出异常。
        for (b0 in 0..255) {
            for (b3 in 0..255) {
                val raw = bytes(0xFD, 0xFD, 0x09, b3, b0)
                react(raw) // 抛异常就会让本用例失败
            }
        }
    }
}
