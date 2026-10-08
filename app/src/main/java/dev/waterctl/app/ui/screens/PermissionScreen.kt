package dev.waterctl.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.waterctl.app.ui.components.AppTopBar
import dev.waterctl.app.ui.components.BleBlockReason
import dev.waterctl.app.ui.components.CircleArt
import dev.waterctl.app.ui.components.InfoCard
import dev.waterctl.app.ui.components.PillButton
import dev.waterctl.app.ui.components.TextPillButton
import dev.waterctl.app.ui.theme.AppTypo
import dev.waterctl.app.ui.theme.Dimens

/**
 * 蓝牙权限引导 —— 设计稿 09。
 * 原文案针对 Android 12+ 的「附近的设备」权限，这里按实际阻塞原因切换措辞。
 */
@Composable
fun PermissionScreen(
    reason: BleBlockReason,
    onRequest: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme

    val title = when (reason) {
        BleBlockReason.NO_BLUETOOTH -> "这台设备不支持蓝牙"
        BleBlockReason.BLUETOOTH_OFF -> "请先开启蓝牙"
        else -> "需要「附近的设备」权限"
    }
    val paragraph = when (reason) {
        BleBlockReason.NO_BLUETOOTH -> "本应用需要低功耗蓝牙（BLE）才能与水控器通信，当前设备没有可用的蓝牙硬件。"
        BleBlockReason.BLUETOOTH_OFF -> "水控器通过低功耗蓝牙通信，请打开系统蓝牙后重试。"
        else -> "安卓 12 及以上版本把蓝牙访问归类为「附近的设备」权限。"
    }
    val privacy = "本应用仅用它连接水控器，不会用于定位，也不会读取或上传任何位置信息。"
    val primaryLabel = if (reason == BleBlockReason.BLUETOOTH_OFF) "开启蓝牙" else "授予权限"

    Column(modifier = modifier.fillMaxSize()) {
        AppTopBar(title = "蓝牙权限", modifier = Modifier.statusBarsPadding(), onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircleArt(
                size = Dimens.permissionCircle,
                container = scheme.primaryContainer,
                iconSize = Dimens.permissionIcon,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Bluetooth,
                    contentDescription = null,
                    tint = scheme.onPrimaryContainer,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(Modifier.height(Dimens.listGap))

            Text(
                text = title,
                style = AppTypo.dialogTitle,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = paragraph,
                style = AppTypo.note,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = privacy,
                style = AppTypo.noteTight,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(Dimens.listGap))

            InfoCard {
                PermissionBullet("扫描并连接附近的水控器")
                PermissionBullet("读取设备名称（协议校验必需）")
            }

            Spacer(Modifier.height(Dimens.listGap))

            PillButton(
                text = primaryLabel,
                onClick = {
                    if (reason == BleBlockReason.BLUETOOTH_OFF) onOpenBluetoothSettings() else onRequest()
                },
            )

            TextPillButton(
                text = "前往系统设置",
                onClick = onOpenAppSettings,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun PermissionBullet(text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Check,
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(text = text, style = AppTypo.bodySmall, color = scheme.onSurface)
    }
}
