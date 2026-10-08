package dev.waterctl.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.waterctl.app.core.protocol.WaterCtlErrorInfo
import dev.waterctl.app.ui.theme.AppShapes
import dev.waterctl.app.ui.theme.AppTypo
import dev.waterctl.app.ui.theme.Dimens

/**
 * 错误弹窗 —— 设计稿 08。
 *
 * 用自绘 Dialog 而不是 M3 AlertDialog，是为了严格控制设计稿里的
 * 332 宽、圆角 28、内边距 24、间距 14 与居中的图标标题。
 */
@Composable
fun ErrorDialog(
    info: WaterCtlErrorInfo,
    debugLog: List<String>,
    showDebugLog: Boolean,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val width = minOf(Dimens.dialogWidth, screenWidth - 40.dp)

    val paragraphs = info.message.split("\n\n", limit = 2)
    val lead = paragraphs.getOrNull(0).orEmpty()
    val rest = paragraphs.getOrNull(1).orEmpty()
    val logs = debugLog.takeLast(DEBUG_VISIBLE_LINES)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .width(width)
                .clip(AppShapes.dialog)
                .background(scheme.surfaceContainerHigh)
                .padding(Dimens.dialogPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.dialogGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = null,
                tint = scheme.error,
                modifier = Modifier.size(Dimens.dialogIcon),
            )

            Text(
                text = info.title,
                style = AppTypo.dialogTitle,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            if (lead.isNotEmpty()) {
                Text(
                    text = lead,
                    style = AppTypo.bodySmallMedium,
                    color = scheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (rest.isNotEmpty()) {
                Text(
                    text = rest,
                    style = AppTypo.noteTight,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (showDebugLog && logs.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 132.dp)
                        .clip(AppShapes.debugBox)
                        .background(scheme.surfaceContainerHighest)
                        .padding(Dimens.debugBoxPadding),
                    verticalArrangement = Arrangement.spacedBy(Dimens.debugBoxGap),
                ) {
                    Text(
                        text = "调试信息",
                        style = AppTypo.labelMedium.copy(fontFamily = AppTypo.labelMedium.fontFamily),
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        logs.forEach { line ->
                            Text(
                                text = line,
                                style = AppTypo.debugLog,
                                color = scheme.onSurface,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(AppShapes.pill)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(text = "好", style = AppTypo.bodySmallMedium, color = scheme.primary)
                }
            }
        }
    }
}

private const val DEBUG_VISIBLE_LINES = 8
