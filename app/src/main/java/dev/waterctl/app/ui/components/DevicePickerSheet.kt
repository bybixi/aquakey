package dev.waterctl.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.waterctl.app.core.ble.WaterDevice
import dev.waterctl.app.ui.theme.AppShapes
import dev.waterctl.app.ui.theme.AppTypo
import dev.waterctl.app.ui.theme.Dimens

/**
 * 设备选择面板。
 *
 * 注意：这一屏**不在设计稿里**。原 Web 版靠浏览器的设备选择器选设备，
 * 原生端需要一个等价物；这里用 Material 3 的底部面板承载，视觉上沿用
 * 设计稿的圆角 / 间距 / 字阶，保证不破坏整体一致性。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerSheet(
    scanning: Boolean,
    devices: List<WaterDevice>,
    onPick: (WaterDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.screenPadding)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(Dimens.listGapTight),
        ) {
            Text("选择水控器", style = AppTypo.dialogTitle, color = scheme.onSurface)
            Text(
                text = "只显示附近名称以字母或数字开头的蓝牙设备",
                style = AppTypo.label,
                color = scheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(2.dp))

            when {
                devices.isEmpty() && scanning -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = scheme.primary,
                        strokeWidth = 2.5.dp,
                    )
                    Spacer(Modifier.size(12.dp))
                    Text("正在搜索附近的设备…", style = AppTypo.bodySmall, color = scheme.onSurfaceVariant)
                }

                devices.isEmpty() -> Text(
                    text = "没有找到水控器。请确认水控器已通电、距离足够近，然后重新搜索。",
                    style = AppTypo.caption,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 28.dp),
                )

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(Dimens.listGapTight),
                ) {
                    items(devices, key = { it.address }) { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(AppShapes.card)
                                .background(scheme.surfaceContainerLow)
                                .clickable { onPick(device) }
                                .padding(Dimens.recordRowPadding),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Dimens.recordRowGap),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(Dimens.recordRowTextGap),
                            ) {
                                Text(device.name, style = AppTypo.rowTitle, color = scheme.onSurface)
                                Text(
                                    text = device.address,
                                    style = AppTypo.debugLog,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(AppShapes.pill)
                                    .background(scheme.surfaceContainerHighest)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    text = "${device.rssi} dBm",
                                    style = AppTypo.label,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
