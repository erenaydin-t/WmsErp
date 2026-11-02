package com.wmserp.app.presentation.stocktaking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wmserp.app.R
import com.wmserp.app.core.util.Formatters
import com.wmserp.app.domain.model.CountingMode
import com.wmserp.app.domain.model.StocktakingSession
import com.wmserp.app.domain.model.StocktakingStatus
import com.wmserp.app.presentation.common.labelRes
import com.wmserp.app.presentation.components.StatusChip
import com.wmserp.app.presentation.components.charts.ProgressRow
import com.wmserp.app.presentation.theme.WmsTheme

/** One stocktaking session the counter can join (Orders → Count and the dashboard). */
@Composable
fun StocktakingSessionCard(session: StocktakingSession, pendingCounts: Int = 0, onClick: () -> Unit) {
    val color = stocktakingStatusColor(session.status)
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().testTag("stocktaking_${session.name}"),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(session.name, style = MaterialTheme.typography.titleSmall)
                    Text(session.warehouseName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StatusChip(stringResource(session.status.labelRes()), color)
            }
            ProgressRow(
                label = stringResource(R.string.st_card_progress, Formatters.int(session.totals.counted), Formatters.int(session.totals.total)),
                fraction = session.totals.progress,
                valueText = Formatters.percent(session.totals.progress * 100.0),
                color = color,
            )
            Text(
                if (session.mode == CountingMode.OPEN) stringResource(R.string.st_card_open_mode)
                else stringResource(R.string.st_card_mine, Formatters.int(session.my.open), Formatters.int(session.my.assigned)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pendingCounts > 0) {
                Text(
                    stringResource(R.string.st_card_pending_sync, Formatters.int(pendingCounts)),
                    style = MaterialTheme.typography.labelSmall,
                    color = WmsTheme.colors.warning,
                    modifier = Modifier.testTag("stocktaking_pending_${session.name}"),
                )
            }
        }
    }
}

@Composable
fun stocktakingStatusColor(status: StocktakingStatus): Color = when (status) {
    StocktakingStatus.DRAFT, StocktakingStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    StocktakingStatus.COUNTING -> WmsTheme.colors.info
    StocktakingStatus.MANAGER_REVIEW, StocktakingStatus.RECOUNT -> WmsTheme.colors.warning
    StocktakingStatus.FINAL_APPROVAL, StocktakingStatus.RECONCILED, StocktakingStatus.COMPLETED -> WmsTheme.colors.success
    StocktakingStatus.CANCELLED -> WmsTheme.colors.danger
}
