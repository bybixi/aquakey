package dev.waterctl.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.waterctl.app.BuildConfig
import dev.waterctl.app.ui.components.AppTopBar
import dev.waterctl.app.ui.components.GroupCard
import dev.waterctl.app.ui.components.SettingRow
import dev.waterctl.app.ui.theme.AppShapes
import dev.waterctl.app.ui.theme.AppTypo
import dev.waterctl.app.ui.theme.Dimens

private const val URL_SOURCE = "https://github.com/celesWuff/waterctl"
private const val URL_FAQ = "https://github.com/celesWuff/waterctl/blob/2.x/FAQ.md"
private const val URL_LICENSE = "https://opensource.org/licenses/MIT"

/**
 * 关于 —— 设计稿 07。保留原项目的 MIT 版权声明与出处。
 */
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current

    fun open(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        AppTopBar(title = "关于", modifier = Modifier.statusBarsPadding(), onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.screenPadding)
                .padding(bottom = Dimens.listGap),
            verticalArrangement = Arrangement.spacedBy(Dimens.listGap),
        ) {
            // 应用头部：圆角 20 的 surface 底 + 56 的应用图标
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AppShapes.summaryCard)
                    .background(scheme.surfaceContainer)
                    .padding(Dimens.cardPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AppIconBadge()
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("蓝牙水控器", style = AppTypo.itemTitle, color = scheme.onSurface)
                    Text(
                        text = "版本 ${BuildConfig.VERSION_NAME} · Android 原生实现",
                        style = AppTypo.label,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = "本应用是开源项目 celesWuff/waterctl 的安卓原生实现，功能与原版保持一致，" +
                    "用于控制深圳市常工电子的蓝牙水控器。完全离线运行，不收集任何数据。",
                style = AppTypo.note,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )

            GroupCard {
                SettingRow(
                    title = "源代码",
                    onClick = { open(URL_SOURCE) },
                    trailing = { LinkIcon(scheme.primary) },
                )
                SettingRow(
                    title = "疑难解答",
                    onClick = { open(URL_FAQ) },
                    trailing = { LinkIcon(scheme.primary) },
                )
                SettingRow(
                    title = "开源许可",
                    onClick = { open(URL_LICENSE) },
                    trailing = {
                        Text("MIT License", style = AppTypo.bodySmall, color = scheme.onSurfaceVariant)
                    },
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AppShapes.card)
                    .background(scheme.surfaceContainer)
                    .padding(Dimens.cardPadding),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "copyright (c) 2025 celesWuff · MIT License",
                    style = AppTypo.label,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    text = "本应用为独立实现，与原作者无隶属关系；水控器通信协议的实现来自 celesWuff/waterctl。",
                    style = AppTypo.legal,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppIconBadge() {
    Box(
        modifier = Modifier
            .size(Dimens.appIconBox)
            .clip(AppShapes.appIcon)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.WaterDrop,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(Dimens.appIconGlyph),
        )
    }
}

@Composable
private fun LinkIcon(tint: androidx.compose.ui.graphics.Color) {
    Icon(
        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(20.dp),
    )
}
