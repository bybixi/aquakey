package dev.waterctl.app.ui

import java.util.Calendar

/** 与设计稿一致的文案格式。 */
object Fmt {

    /** 计时器：`00:18:32` */
    fun timer(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    /** 单次时长：`18 分 32 秒`；超过一小时则 `1 小时 4 分` */
    fun duration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "%d 小时 %d 分".format(h, m)
            m > 0 -> "%d 分 %02d 秒".format(m, s)
            else -> "%d 秒".format(s)
        }
    }

    /** 累计时长：`4 小时 12 分` / `42 分` / 不足一分钟时 `29 秒` */
    fun totalDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 -> "%d 小时 %d 分".format(h, m)
            m > 0 -> "%d 分".format(m)
            else -> "%d 秒".format(seconds)
        }
    }

    /** 「平均每次」用；不足一分钟不显示成 0 分 */
    fun minutes(seconds: Int): String =
        if (seconds < 60) "%d 秒".format(seconds) else "%d 分".format(seconds / 60)

    /** `2026-10-07 21:12` */
    fun dateTime(millis: Long): String {
        val c = cal(millis)
        return "%04d-%02d-%02d %02d:%02d".format(
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY),
            c.get(Calendar.MINUTE),
        )
    }

    /** `21:12` */
    fun timeOfDay(millis: Long): String {
        val c = cal(millis)
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    private fun cal(millis: Long) = Calendar.getInstance().apply { timeInMillis = millis }
}
