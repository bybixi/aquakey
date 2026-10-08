package dev.waterctl.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.waterctl.app.ui.theme.AppShapes
import dev.waterctl.app.ui.theme.AppTypo
import dev.waterctl.app.ui.theme.Dimens

/**
 * 顶部应用栏 —— 设计稿：高 64、左右内边距 16、标题居中、字号 22 Bold。
 *
 * 左右各留一个 40dp 的槽位，保证标题在任何情况下都真正居中。
 */
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.topBarHeight)
            .padding(horizontal = Dimens.screenPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            CircleIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
            )
        } else {
            Spacer(Modifier.size(Dimens.iconButtonSize))
        }

        Text(
            text = title,
            style = AppTypo.pageTitle,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )

        if (action != null) {
            action()
        } else {
            Spacer(Modifier.size(Dimens.iconButtonSize))
        }
    }
}

/** 40 × 40 的圆形图标按钮（顶栏操作 / 返回）。 */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        modifier = Modifier
            .size(Dimens.iconButtonSize)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(Dimens.iconButtonIcon),
        )
    }
}

/**
 * 状态胶囊 —— 设计稿：内边距 10、图标与文字间距 8、图标 16、文字 13 Medium、全圆角。
 */
@Composable
fun StatusChip(
    text: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(AppShapes.pill)
            .background(containerColor)
            .padding(Dimens.chipPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.chipGap),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(Dimens.chipIcon),
        )
        Text(text = text, style = AppTypo.captionMedium, color = contentColor)
    }
}

/**
 * 主按钮 —— 设计稿：176 直径的正圆，图标 52、文字 18 SemiBold、两者间距 10。
 */
@Composable
fun HeroButton(
    label: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .size(Dimens.heroButtonSize)
            .clip(CircleShape)
            .background(containerColor)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.size(Dimens.heroIconSize), contentAlignment = Alignment.Center) {
            icon()
        }
        Spacer(Modifier.height(Dimens.heroButtonGap))
        Text(text = label, style = AppTypo.itemTitle, color = contentColor)
    }
}

/**
 * 卡片 —— 设计稿：圆角 16、内边距 16、内容间距 10、
 * surfaceContainerLow 底 + 1dp outlineVariant 描边。
 */
@Composable
fun InfoCard(
    modifier: Modifier = Modifier,
    gap: androidx.compose.ui.unit.Dp = Dimens.cardGap,
    padding: androidx.compose.ui.unit.Dp = Dimens.cardPadding,
    radius: androidx.compose.ui.unit.Dp = Dimens.cardRadius,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(container)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(radius))
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/**
 * 设置分组卡 —— 设计稿：外内边距 8、行间距 4、无描边。
 */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShapes.card)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(Dimens.groupCardPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.groupCardGap),
        content = content,
    )
}

/** 设置分组标题 —— 12 Medium onSurfaceVariant。 */
@Composable
fun GroupTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = AppTypo.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    )
}

/** 分组卡里的一行：标题 + 说明 + 尾部内容。 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(Dimens.settingRowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.settingRowGap),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimens.settingRowTextGap),
        ) {
            Text(text = title, style = AppTypo.rowTitle, color = titleColor)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = AppTypo.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * 开关 —— 设计稿指定 52 × 32 的轨道、开启时 24 的滑块、关闭时 16。
 * 用自绘代替 M3 Switch，因为 M3 默认尺寸（52 × 32 但滑块 28/16 且带描边）与设计稿不完全一致。
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackColor = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
    val trackBorder = if (checked) Color.Transparent else MaterialTheme.colorScheme.outline
    val thumbColor = if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline
    val thumbSize = if (checked) Dimens.switchThumbOn else Dimens.switchThumbOff

    Box(
        modifier = modifier
            .size(Dimens.switchWidth, Dimens.switchHeight)
            .clip(AppShapes.pill)
            .background(trackColor)
            .border(if (checked) 0.dp else 2.dp, trackBorder, AppShapes.pill)
            .clickable { onCheckedChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(
                    if (checked) Dimens.switchThumbPadding else Dimens.switchThumbOffPadding,
                )
                .size(thumbSize)
                .clip(CircleShape)
                .background(thumbColor),
        )
    }
}

/** 整宽胶囊按钮 —— 设计稿：高 48、全圆角、15 SemiBold。 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.buttonHeight)
            .clip(AppShapes.pill)
            .background(container)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = AppTypo.button, color = contentColor)
    }
}

/** 居中的次级文字按钮（如「前往系统设置」）。 */
@Composable
fun TextPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier
            .clip(AppShapes.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(text = text, style = AppTypo.bodySmallMedium, color = contentColor)
    }
}

/** 空状态 / 权限页顶部的圆形插图底。 */
@Composable
fun CircleArt(
    size: androidx.compose.ui.unit.Dp,
    container: Color,
    modifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(iconSize), contentAlignment = Alignment.Center) { icon() }
    }
}

/** 设计稿里的分区标题（列表页「今天 / 昨天」）。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = AppTypo.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    )
}

/** 用于把内容卡片和页面背景分开的一层（设计稿里 section 之间没有分割线）。 */
@Composable
fun VerticalGap(height: androidx.compose.ui.unit.Dp) {
    Spacer(Modifier.height(height))
}

/** 最小的 Surface 包装，统一页面底色。 */
@Composable
fun ScreenSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        content = content,
    )
}
