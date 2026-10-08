package dev.waterctl.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.waterctl.app.domain.ConnectSteps
import dev.waterctl.app.domain.ControlStage
import dev.waterctl.app.domain.ControlUiState
import dev.waterctl.app.ui.Fmt
import dev.waterctl.app.ui.theme.AppShapes
import dev.waterctl.app.ui.components.AppTopBar
import dev.waterctl.app.ui.components.CircleIconButton
import dev.waterctl.app.ui.components.HeroButton
import dev.waterctl.app.ui.components.InfoCard
import dev.waterctl.app.ui.components.StatusChip
import dev.waterctl.app.ui.components.StepIndicator
import dev.waterctl.app.ui.components.StepState
import dev.waterctl.app.ui.theme.AppTypo
import dev.waterctl.app.ui.theme.Dimens

/**
 * 水控主界面 —— 设计稿 01 / 02 / 03 三态合一，由 [state] 驱动。
 */
@Composable
fun ControlScreen(
    state: ControlUiState,
    onMainAction: () -> Unit,
    onPickDevice: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 0.dp),
    ) {
        AppTopBar(
            title = "蓝牙水控器",
            modifier = Modifier.statusBarsPadding(),
            action = {
                CircleIconButton(
                    icon = Icons.Outlined.Bluetooth,
                    contentDescription = "选择水控器",
                    onClick = onPickDevice,
                )
            },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = Dimens.screenPadding)
                .imePadding(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state.stage) {
                ControlStage.IDLE -> IdleContent(state, onMainAction, onReconnect)
                ControlStage.CONNECTING -> ConnectingContent(state)
                ControlStage.ACTIVE -> ActiveContent(state, onMainAction)
            }
        }
    }
}

// =====================================================================
// 01 · 未连接
// =====================================================================

@Composable
private fun IdleContent(
    state: ControlUiState,
    onMainAction: () -> Unit,
    onReconnect: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    StatusChip(
        text = "未连接",
        icon = Icons.Outlined.Bluetooth,
        containerColor = scheme.surfaceContainerHighest,
        contentColor = scheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(Dimens.controlGap))

    HeroButton(
        label = "开启",
        containerColor = scheme.primary,
        contentColor = scheme.onPrimary,
        onClick = onMainAction,
    ) {
        Icon(
            imageVector = Icons.Outlined.WaterDrop,
            contentDescription = null,
            tint = scheme.onPrimary,
            modifier = Modifier.size(Dimens.heroIconSize),
        )
    }

    Spacer(Modifier.height(Dimens.controlGap))

    Text(
        text = "点按开始用水",
        style = AppTypo.bodySmall,
        color = scheme.onSurfaceVariant,
    )

    val last = state.lastSession
    if (last != null) {
        Spacer(Modifier.height(Dimens.controlGap))
        InfoCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Bluetooth,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Text("上次连接", style = AppTypo.labelMedium, color = scheme.onSurfaceVariant)
                }
                Text(
                    text = "重连",
                    style = AppTypo.bodySmallMedium,
                    color = scheme.primary,
                    modifier = Modifier
                        .clip(AppShapes.pill)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onReconnect,
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Text(last.deviceName, style = AppTypo.cardTitle, color = scheme.onSurface)
            Text(
                text = "${Fmt.dateTime(last.startedAt)} · 用时 ${Fmt.duration(last.durationSeconds)}",
                style = AppTypo.caption,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

// =====================================================================
// 02 · 连接中
// =====================================================================

@Composable
private fun ConnectingContent(state: ControlUiState) {
    val scheme = MaterialTheme.colorScheme

    StatusChip(
        text = "正在连接 ${state.deviceName ?: ""}".trim(),
        icon = Icons.Outlined.Bluetooth,
        containerColor = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
    )

    Spacer(Modifier.height(Dimens.controlGap))

    HeroButton(
        label = "请稍候",
        containerColor = scheme.surfaceContainerHighest,
        contentColor = scheme.onSurfaceVariant,
        enabled = false,
        onClick = {},
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(Dimens.heroIconSize),
            color = scheme.primary,
            trackColor = scheme.outlineVariant,
            strokeWidth = 4.dp,
        )
    }

    Spacer(Modifier.height(Dimens.controlGap))

    Text(
        text = "正在建立连接并校验密钥，请勿离开",
        style = AppTypo.bodySmall,
        color = scheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )

    Spacer(Modifier.height(Dimens.controlGap))

    InfoCard {
        ProgressStep("蓝牙已连接", state.steps.stepAt(0))
        ProgressStep("正在校验密钥", state.steps.stepAt(1))
        ProgressStep("启动会话", state.steps.stepAt(2))
    }
}

@Composable
private fun ProgressStep(label: String, state: StepState) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StepIndicator(state = state, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            style = when (state) {
                StepState.IN_PROGRESS -> AppTypo.bodySmallSemiBold
                StepState.PENDING -> AppTypo.bodySmall
                StepState.DONE -> AppTypo.bodySmallMedium
            },
            color = when (state) {
                StepState.IN_PROGRESS -> scheme.onSurface
                StepState.PENDING -> scheme.outline
                StepState.DONE -> scheme.onSurfaceVariant
            },
        )
    }
}

private fun ConnectSteps.stepAt(index: Int): StepState = when (index) {
    0 -> if (bluetoothConnected) StepState.DONE else StepState.IN_PROGRESS
    1 -> when {
        keyVerified -> StepState.DONE
        keyVerifying -> StepState.IN_PROGRESS
        else -> StepState.PENDING
    }
    else -> when {
        sessionStarting -> StepState.IN_PROGRESS
        else -> StepState.PENDING
    }
}

// =====================================================================
// 03 · 使用中
// =====================================================================

@Composable
private fun ActiveContent(state: ControlUiState, onMainAction: () -> Unit) {
    val scheme = MaterialTheme.colorScheme

    StatusChip(
        text = "已连接 ${state.deviceName ?: ""}".trim(),
        icon = Icons.Outlined.Bluetooth,
        containerColor = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
    )

    Spacer(Modifier.height(Dimens.controlGap))

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(Fmt.timer(state.elapsedSeconds), style = AppTypo.timer, color = scheme.onSurface)
        Spacer(Modifier.height(2.dp))
        Text("本次用水时长", style = AppTypo.caption, color = scheme.onSurfaceVariant)
    }

    Spacer(Modifier.height(Dimens.controlGap))

    HeroButton(
        label = "结束",
        containerColor = scheme.primary,
        contentColor = scheme.onPrimary,
        onClick = onMainAction,
    ) {
        Icon(
            imageVector = Icons.Outlined.Stop,
            contentDescription = null,
            tint = scheme.onPrimary,
            modifier = Modifier.size(Dimens.heroIconSize),
        )
    }

    Spacer(Modifier.height(Dimens.controlGap))

    Text(
        text = "结束后自动记入用水记录",
        style = AppTypo.bodySmall,
        color = scheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )

    Spacer(Modifier.height(Dimens.controlGap))

    InfoCard {
        Text("本次会话", style = AppTypo.labelMedium, color = scheme.onSurfaceVariant)
        Text("开始时间 · ${Fmt.timeOfDay(sessionStartOf(state))}", style = AppTypo.bodySmall, color = scheme.onSurface)
        Text("水控器 · ${state.deviceName ?: "—"}", style = AppTypo.bodySmall, color = scheme.onSurface)
    }
}

/**
 * 会话开始时刻。ViewModel 用 elapsed 反推，避免这里多引一个状态。
 */
private fun sessionStartOf(state: ControlUiState): Long =
    System.currentTimeMillis() - state.elapsedSeconds * 1000L
