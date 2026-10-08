package dev.waterctl.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.waterctl.app.data.db.WaterRecord
import dev.waterctl.app.domain.RecordGroup
import dev.waterctl.app.domain.RecordsUiState
import dev.waterctl.app.ui.Fmt
import dev.waterctl.app.ui.components.AppTopBar
import dev.waterctl.app.ui.components.CircleArt
import dev.waterctl.app.ui.components.InfoCard
import dev.waterctl.app.ui.components.PillButton
import dev.waterctl.app.ui.components.SectionLabel
import dev.waterctl.app.ui.theme.AppShapes
import dev.waterctl.app.ui.theme.AppTypo
import dev.waterctl.app.ui.theme.Dimens

/**
 * 用水记录 —— 设计稿 04（列表）与 05（空状态）。
 */
@Composable
fun RecordsScreen(
    state: RecordsUiState,
    onGoToControl: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        AppTopBar(title = "用水记录", modifier = Modifier.statusBarsPadding())

        if (state.isEmpty) {
            EmptyRecords(modifier = Modifier.weight(1f), onGoToControl = onGoToControl)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Dimens.screenPadding,
                    end = Dimens.screenPadding,
                    top = 0.dp,
                    bottom = Dimens.listGap,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.listGapTight + 2.dp),
            ) {
                item {
                    SummaryCard(state)
                    Spacer(Modifier.height(Dimens.listGap))
                }
                state.groups.forEach { group ->
                    item(key = "label-${group.label}") {
                        SectionLabel(group.label, modifier = Modifier.padding(top = 2.dp))
                    }
                    items(group.records, key = { it.id }) { record ->
                        RecordRow(record)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(state: RecordsUiState) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.summaryCard)
            .background(scheme.surfaceContainer)
            .padding(Dimens.summaryCardPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.summaryCardGap),
    ) {
        Text("本月累计", style = AppTypo.labelMedium, color = scheme.onSurfaceVariant)
        Text(
            text = Fmt.totalDuration(state.summary.totalSeconds),
            style = AppTypo.summaryValue,
            color = scheme.onSurface,
        )
        Text(
            text = "共 ${state.summary.count} 次 · 平均每次 ${Fmt.minutes(state.summary.averageSeconds)}",
            style = AppTypo.caption,
            color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecordRow(record: WaterRecord) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.card)
            .background(scheme.surfaceContainerLow)
            .padding(Dimens.recordRowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.recordRowGap),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimens.recordRowTextGap),
        ) {
            Text(record.deviceName, style = AppTypo.rowTitle, color = scheme.onSurface)
            Text(
                text = Fmt.dateTime(record.startedAt),
                style = AppTypo.label,
                color = scheme.onSurfaceVariant,
            )
        }
        Text(
            text = Fmt.duration(record.durationSeconds),
            style = AppTypo.bodySmallSemiBold,
            color = scheme.primary,
        )
    }
}

@Composable
private fun EmptyRecords(modifier: Modifier = Modifier, onGoToControl: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircleArt(
            size = Dimens.emptyStateCircle,
            container = scheme.surfaceContainer,
            iconSize = Dimens.emptyStateIcon,
        ) {
            Icon(
                imageVector = Icons.Outlined.History,
                contentDescription = null,
                tint = scheme.outline,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Spacer(Modifier.height(Dimens.listGap))

        Text(
            text = "还没有用水记录",
            style = AppTypo.itemTitle,
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = "在「水控」页开启一次用水后，",
            style = AppTypo.bodySmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "记录会自动出现在这里。",
            style = AppTypo.bodySmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(Dimens.listGap))

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PillButton(
                text = "去开启用水",
                onClick = onGoToControl,
                modifier = Modifier.fillMaxWidth(0.62f),
                container = scheme.primaryContainer,
                contentColor = scheme.onPrimaryContainer,
            )
        }
    }
}
