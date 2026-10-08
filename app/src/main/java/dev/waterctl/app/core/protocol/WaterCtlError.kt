package dev.waterctl.app.core.protocol

/**
 * 错误分类，对应原项目 `errors.ts` 里的 7 个 case。
 *
 * 原实现靠正则匹配 Web Bluetooth 的异常字符串；原生端改成语义化的枚举，
 * 但文案、是否致命、是否展示调试日志这三项与原版一一对应。
 */
enum class WaterCtlErrorKind {
    /** 收到无法识别的 RXD 数据 —— 不致命，多数情况不影响使用。 */
    UNKNOWN_RX,

    /** 水控器拒绝启动 / 密钥校验失败 —— 致命，且明确提示不要重试。 */
    REFUSED,

    /** 15 秒内没等到响应。 */
    TIMEOUT,

    /** 找不到 0xF1F0 服务或特征 —— 说明这台机器不是支持的水控器。 */
    UNSUPPORTED_MODEL,

    /** 没有蓝牙硬件、蓝牙未开启，或权限被拒。 */
    BLUETOOTH_UNAVAILABLE,

    /** GATT 掉线、连接中断。 */
    UNSTABLE,

    /** 其它未归类异常。 */
    UNHANDLED,
}

data class WaterCtlErrorInfo(
    val kind: WaterCtlErrorKind,
    val title: String,
    val message: String,
    val fatal: Boolean,
    val showLogs: Boolean,
)

class WaterCtlException(
    val kind: WaterCtlErrorKind,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: kind.name, cause)

object ErrorResolver {

    fun infoOf(error: Throwable): WaterCtlErrorInfo {
        val kind = (error as? WaterCtlException)?.kind ?: WaterCtlErrorKind.UNHANDLED
        return infoOf(kind, error)
    }

    fun infoOf(kind: WaterCtlErrorKind, error: Throwable? = null): WaterCtlErrorInfo = when (kind) {
        WaterCtlErrorKind.UNKNOWN_RX -> WaterCtlErrorInfo(
            kind = kind,
            title = "出现错误",
            message = "接收到未知数据，可能不影响使用。\n\n这可能是一个 Bug，请把下面的调试信息反馈给开发者。",
            fatal = false,
            showLogs = true,
        )

        WaterCtlErrorKind.REFUSED -> WaterCtlErrorInfo(
            kind = kind,
            title = "出现错误",
            message = "水控器拒绝启动。\n\n当前版本不支持您的水控器，请不要重试，多次失败可能造成水控器锁定。若已锁定，请在通电状态下等待约一小时。",
            fatal = true,
            showLogs = true,
        )

        WaterCtlErrorKind.TIMEOUT -> WaterCtlErrorInfo(
            kind = kind,
            title = "出现错误",
            message = "等待时间似乎太长了。\n\n如果该问题反复发生，这可能是一个 Bug，请把下面的调试信息反馈给开发者。",
            fatal = false,
            showLogs = true,
        )

        WaterCtlErrorKind.UNSUPPORTED_MODEL -> WaterCtlErrorInfo(
            kind = kind,
            title = "出现错误",
            message = "不支持的机型。\n\n这台设备没有提供水控器所需的蓝牙服务（0xF1F0）。请确认您连接的是水控器，而不是别的蓝牙设备。",
            fatal = true,
            showLogs = false,
        )

        WaterCtlErrorKind.BLUETOOTH_UNAVAILABLE -> WaterCtlErrorInfo(
            kind = kind,
            title = "出现错误",
            message = "设备不支持蓝牙，或蓝牙权限未开启。\n\n请在系统设置中开启蓝牙，并授予本应用「附近的设备」权限。",
            fatal = true,
            showLogs = false,
        )

        WaterCtlErrorKind.UNSTABLE -> WaterCtlErrorInfo(
            kind = kind,
            title = "出现错误",
            message = "连接不稳定，与水控器通信失败。\n请重试。",
            fatal = true,
            showLogs = false,
        )

        WaterCtlErrorKind.UNHANDLED -> WaterCtlErrorInfo(
            kind = kind,
            title = "出现错误",
            message = "${error ?: "未知错误"}\n\n这可能是一个 Bug，请把下面的调试信息反馈给开发者。",
            fatal = true,
            showLogs = true,
        )
    }
}
