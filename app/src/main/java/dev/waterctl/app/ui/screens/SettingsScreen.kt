package dev.waterctl.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.waterctl.app.BuildConfig
import dev.waterctl.app.data.prefs.AppSettings
import dev.waterctl.app.ui.components.AppSwitch
import dev.waterctl.app.ui.components.AppTopBar
import dev.waterctl.app.ui.components.GroupCard
import dev.waterctl.app.ui.components.GroupTitle
import dev.waterctl.app.ui.components.SettingRow
import dev.waterctl.app.ui.theme.AppShapes
import dev.waterctl.app.ui.theme.AppTypo
import dev.waterctl.app.ui.theme.Dimens

/**
 * 设置 —— 设计稿 06。分组卡内边距 8 / 行内边距 12 / 组间距 10，均与设计稿一致。
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onRememberDeviceChange: (Boolean) -> Unit,
    onAutoReconnectChange: (Boolean) -> Unit,
    onShowDebugLogChange: (Boolean) -> Unit,
    onClearRecords: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        AppTopBar(title = "设置", modifier = Modifier.statusBarsPadding())

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.screenPadding)
                .padding(bottom = Dimens.listGap),
            verticalArrangement = Arrangement.spacedBy(Dimens.listGapTight),
        ) {
            GroupTitle("连接", modifier = Modifier.padding(top = 2.dp))
            GroupCard {
                SettingRow(
                    title = "记住上次的设备",
                    subtitle = "下次打开时自动填入，可一键重连",
                    trailing = {
                        AppSwitch(
                            checked = settings.rememberDevice,
                            onCheckedChange = onRememberDeviceChange,
                        )
                    },
                )
                SettingRow(
                    title = "自动重连",
                    trailing = {
                        AppSwitch(
                            checked = settings.autoReconnect,
                            onCheckedChange = onAutoReconnectChange,
                        )
                    },
                )
            }

            GroupTitle("诊断", modifier = Modifier.padding(top = 6.dp))
            GroupCard {
                SettingRow(
                    title = "显示调试日志",
                    subtitle = "错误弹窗中展示十六进制收发记录",
                    trailing = {
                        AppSwitch(
                            checked = settings.showDebugLog,
                            onCheckedChange = onShowDebugLogChange,
                        )
                    },
                )
            }

            GroupTitle("数据", modifier = Modifier.padding(top = 6.dp))
            GroupCard {
                SettingRow(
                    title = "清除全部用水记录",
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { confirmClear = true },
                )
            }

            GroupTitle("关于", modifier = Modifier.padding(top = 6.dp))
            GroupCard {
                SettingRow(
                    title = "版本",
                    trailing = {
                        Text(
                            text = "${BuildConfig.VERSION_NAME} · Android",
                            style = AppTypo.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                SettingRow(
                    title = "开源许可与致谢",
                    onClick = onOpenAbout,
                    trailing = {
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 0.dp),
                        )
                    },
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            shape = AppShapes.dialog,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text("清除全部用水记录？", style = AppTypo.dialogTitle, color = MaterialTheme.colorScheme.onSurface)
            },
            text = {
                Text(
                    text = "所有本地用水记录将被删除，此操作无法撤销。",
                    style = AppTypo.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onClearRecords()
                    confirmClear = false
                }) {
                    Text("清除", style = AppTypo.bodySmallMedium, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("取消", style = AppTypo.bodySmallMedium, color = MaterialTheme.colorScheme.primary)
                }
            },
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
