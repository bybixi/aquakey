package dev.waterctl.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.waterctl.app.R

/**
 * 字体家族 —— 全局思源宋体。
 *
 * 设计稿要求「全局衬线」，所以包括数字、按钮、导航标签在内的一切文字
 * 都走 [SerifSC]。唯一的例外是错误弹窗里的调试日志（十六进制报文），
 * 设计稿在那里用的是等宽字体，保留 [MonoJB] 以便对齐阅读。
 */
val SerifSC = FontFamily(
    Font(R.font.noto_serif_sc_400, FontWeight.Normal),
    Font(R.font.noto_serif_sc_500, FontWeight.Medium),
    Font(R.font.noto_serif_sc_600, FontWeight.SemiBold),
    Font(R.font.noto_serif_sc_700, FontWeight.Bold),
)

/** 调试日志用的等宽字体。 */
val MonoJB = FontFamily(
    Font(R.font.jetbrains_mono_400, FontWeight.Normal),
    Font(R.font.jetbrains_mono_500, FontWeight.Medium),
)

/** 关掉 Android 字体额外行距，让行盒贴近设计稿。 */
private val NoFontPadding = PlatformTextStyle(includeFontPadding = false)

private fun serif(
    size: Int,
    weight: FontWeight = FontWeight.Normal,
    lineHeight: Int? = null,
) = TextStyle(
    fontFamily = SerifSC,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight?.sp ?: androidx.compose.ui.unit.TextUnit.Unspecified,
    platformStyle = NoFontPadding,
)

/**
 * 命名文字样式。数值 = 设计稿「字阶」板上实测的 fontSize。
 *
 * 刻意不用 Material 的语义名（displayLarge / headlineSmall …），
 * 因为设计稿的档位与 M3 默认语义并不一一对应，硬套会误导后续维护。
 */
object AppTypo {
    /** 会话计时器 —— Display · 44 / Bold */
    val timer = serif(44, FontWeight.Bold)

    /** 记录页汇总数值 —— 28 / SemiBold */
    val summaryValue = serif(28, FontWeight.SemiBold)

    /** 顶栏标题 —— Title Large · 22 / Bold */
    val pageTitle = serif(22, FontWeight.Bold)

    /** 对话框标题、权限页标题 —— 20 / SemiBold */
    val dialogTitle = serif(20, FontWeight.SemiBold)

    /** 应用名、空状态标题、大圆按钮文字 —— 18 / SemiBold */
    val itemTitle = serif(18, FontWeight.SemiBold)

    /** 卡片标题、设备名 —— 16 / Medium */
    val cardTitle = serif(16, FontWeight.Medium)

    /** 设置行标题、记录行标题 —— 15 / Medium */
    val rowTitle = serif(15, FontWeight.Medium)

    /** 主按钮文字 —— 15 / SemiBold */
    val button = serif(15, FontWeight.SemiBold)

    /** 正文 —— 16 / Regular */
    val body = serif(16)

    /** 次要信息 —— 14 / Regular */
    val bodySmall = serif(14)

    /** 次要信息（强调）—— 14 / Medium */
    val bodySmallMedium = serif(14, FontWeight.Medium)

    /** 次要信息（更重）—— 14 / SemiBold */
    val bodySmallSemiBold = serif(14, FontWeight.SemiBold)

    /** 说明文字 —— 13 / Regular */
    val caption = serif(13)

    /** 说明文字（强调）—— 13 / Medium */
    val captionMedium = serif(13, FontWeight.Medium)

    /** 标签 —— 12 / Regular */
    val label = serif(12)

    /** 标签（强调）—— 12 / Medium */
    val labelMedium = serif(12, FontWeight.Medium)

    /** 多行说明 —— 13 / Regular，显式行高 */
    val note = serif(13, lineHeight = 21)

    /** 多行说明（紧）—— 13 / Regular */
    val noteTight = serif(13, lineHeight = 20)

    /** 版权等小字 —— 12 / Regular，显式行高 */
    val legal = serif(12, lineHeight = 19)

    /** 调试日志 —— 等宽 11 / Medium */
    val debugLog = TextStyle(
        fontFamily = MonoJB,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        platformStyle = NoFontPadding,
    )
}

/**
 * 交给 MaterialTheme 的 Typography。
 *
 * 目的是让 AlertDialog / NavigationBar / Switch 这类自带默认文字样式的
 * M3 组件也自动使用思源宋体，避免出现系统无衬线的"漏网之鱼"。
 */
val AppTypography = Typography(
    displayLarge = serif(57, FontWeight.Bold),
    displayMedium = serif(45, FontWeight.Bold),
    displaySmall = serif(36, FontWeight.Bold, lineHeight = 44),
    headlineLarge = serif(32, FontWeight.SemiBold),
    headlineMedium = serif(28, FontWeight.SemiBold),
    headlineSmall = serif(24, FontWeight.SemiBold),
    titleLarge = AppTypo.pageTitle,
    titleMedium = serif(16, FontWeight.Medium),
    titleSmall = serif(14, FontWeight.Medium),
    bodyLarge = serif(16),
    bodyMedium = serif(14),
    bodySmall = serif(12),
    labelLarge = serif(14, FontWeight.Medium),
    labelMedium = serif(12, FontWeight.Medium),
    labelSmall = serif(11, FontWeight.Medium),
)
