package dev.waterctl.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 布局常量，全部等于设计稿里的实测值。
 *
 * 设计稿基准机型为 412 × 915 dp。这里只固化了「固定的视觉尺寸」
 * （圆角、间距、控件尺寸）；屏幕宽高相关的部分一律用权重与 fillMaxSize 自适应。
 */
object Dimens {

    // ---- 页面骨架 ----
    /** 顶栏高度 */
    val topBarHeight = 64.dp

    /** 顶栏图标按钮（40 × 40 的圆形点击区） */
    val iconButtonSize = 40.dp
    val iconButtonIcon = 24.dp

    /** 顶栏返回箭头 */
    val backIcon = 24.dp

    // ---- 边距与间隔 ----
    /** 页面左右边距 */
    val screenPadding = 16.dp

    /** 主界面内容区竖向间隔 */
    val controlGap = 22.dp

    /** 列表页 / 设置页内容区竖向间隔 */
    val listGap = 16.dp
    val listGapTight = 10.dp

    // ---- 底部导航 ----
    val navBarHeight = 80.dp
    val navBarPadding = 14.dp
    val navIndicatorWidth = 64.dp
    val navIndicatorHeight = 32.dp
    val navItemGap = 4.dp

    // ---- 主按钮（水控开关） ----
    val heroButtonSize = 176.dp
    val heroIconSize = 52.dp
    val heroButtonGap = 10.dp

    // ---- 状态胶囊 ----
    val chipPadding = 10.dp
    val chipGap = 8.dp
    val chipIcon = 16.dp

    // ---- 卡片 ----
    val cardRadius = 16.dp
    val cardPadding = 16.dp
    val cardGap = 10.dp

    /** 记录页汇总卡（设计稿里圆角更大、内边距更宽） */
    val summaryCardRadius = 20.dp
    val summaryCardPadding = 20.dp
    val summaryCardGap = 4.dp

    /** 设置分组卡：外内边距 8，行内边距 12 */
    val groupCardPadding = 8.dp
    val groupCardGap = 4.dp
    val settingRowPadding = 12.dp
    val settingRowGap = 12.dp
    val settingRowTextGap = 2.dp

    /** 记录行 */
    val recordRowRadius = 16.dp
    val recordRowPadding = 14.dp
    val recordRowGap = 12.dp
    val recordRowTextGap = 2.dp

    // ---- 通用胶囊按钮 ----
    val buttonHeight = 48.dp
    val pillRadius = 100.dp

    // ---- 开关 ----
    val switchWidth = 52.dp
    val switchHeight = 32.dp
    val switchThumbOn = 24.dp
    val switchThumbOff = 16.dp
    val switchThumbPadding = 4.dp
    val switchThumbOffPadding = 8.dp

    // ---- 对话框 ----
    val dialogRadius = 28.dp
    val dialogPadding = 24.dp
    val dialogGap = 14.dp
    val dialogWidth = 332.dp
    val dialogIcon = 28.dp
    val debugBoxRadius = 12.dp
    val debugBoxPadding = 12.dp
    val debugBoxGap = 5.dp

    // ---- 插图 ----
    val emptyStateCircle = 112.dp
    val emptyStateIcon = 48.dp
    val permissionCircle = 96.dp
    val permissionIcon = 44.dp

    /** 关于页应用图标 */
    val appIconBox = 56.dp
    val appIconRadius = 16.dp
    val appIconGlyph = 30.dp
}

/** 设计稿用到的圆角，抽成 Shape 便于复用。 */
object AppShapes {
    val card = RoundedCornerShape(Dimens.cardRadius)
    val summaryCard = RoundedCornerShape(Dimens.summaryCardRadius)
    val pill = RoundedCornerShape(Dimens.pillRadius)
    val dialog = RoundedCornerShape(Dimens.dialogRadius)
    val debugBox = RoundedCornerShape(Dimens.debugBoxRadius)
    val appIcon = RoundedCornerShape(Dimens.appIconRadius)
}
