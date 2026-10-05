package com.wmserp.app.presentation.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PendingActions
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.Outbox
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wmserp.app.R
import com.wmserp.app.core.util.Formatters
import com.wmserp.app.domain.model.ActivityEntry
import com.wmserp.app.domain.model.UpdateState
import com.wmserp.app.presentation.common.asString
import com.wmserp.app.presentation.common.toUiText
import com.wmserp.app.presentation.components.EmptyState
import com.wmserp.app.presentation.components.ErrorBanner
import com.wmserp.app.presentation.components.KpiCard
import com.wmserp.app.presentation.components.LoadingState
import com.wmserp.app.presentation.components.QuickActionButton
import com.wmserp.app.presentation.components.SectionHeader
import com.wmserp.app.presentation.stocktaking.StocktakingSessionCard
import com.wmserp.app.presentation.theme.WmsTheme
import com.wmserp.app.presentation.update.UpdateBanner
import com.wmserp.app.presentation.update.showsBanner

@Composable
fun DashboardRoute(
    onScan: () -> Unit,
    onReceive: () -> Unit,
    onPick: () -> Unit,
    onReport: () -> Unit,
    onOpenStocktaking: (String) -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    DashboardScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onRetry = { viewModel.load() },
        onScan = onScan,
        onReceive = onReceive,
        onPick = onPick,
        onReport = onReport,
        onOpenStocktaking = onOpenStocktaking,
        updateState = updateState,
        onDownloadUpdate = viewModel::downloadUpdate,
        onDismissUpdate = viewModel::dismissUpdate,
    )
}

@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onScan: () -> Unit,
    onReceive: () -> Unit,
    onPick: () -> Unit,
    onReport: () -> Unit,
    onOpenStocktaking: (String) -> Unit = {},
    updateState: UpdateState = UpdateState.Idle,
    onDownloadUpdate: () -> Unit = {},
    onDismissUpdate: () -> Unit = {},
) {
    val colors = WmsTheme.colors
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (state.greetingName.isBlank()) stringResource(R.string.dashboard_hello) else stringResource(R.string.dashboard_hello_name, state.greetingName),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("dashboard_greeting"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.dashboard_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(Brush.linearGradient(listOf(colors.gradientStart, colors.gradientEnd)), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            state.greetingName.take(1).uppercase().ifBlank { "W" },
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            if (updateState.showsBanner) {
                item { UpdateBanner(updateState, onDownload = onDownloadUpdate, onDismiss = onDismissUpdate) }
            }

            state.error?.let { error ->
                item { ErrorBanner(error.asString(), onRetry = onRetry, modifier = Modifier.testTag("dashboard_error")) }
            }

            if (state.isLoading && state.data == null) {
                item { LoadingState(modifier = Modifier.height(240.dp), message = stringResource(R.string.dashboard_loading)) }
            }

            state.data?.let { data ->
                val kpis = data.kpis
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            KpiCard(
                                title = stringResource(R.string.kpi_total_items),
                                value = Formatters.int(kpis.totalItems),
                                icon = Icons.Outlined.Inventory2,
                                accent = colors.kpiPurple,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("kpi_total_items"),
                                subtitle = stringResource(R.string.kpi_total_items_sub),
                            )
                            KpiCard(
                                title = stringResource(R.string.kpi_pending_orders),
                                value = Formatters.int(kpis.pendingOrders),
                                icon = Icons.Outlined.ShoppingCart,
                                accent = colors.kpiAmber,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("kpi_pending_orders"),
                                subtitle = stringResource(R.string.kpi_pending_orders_sub),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            KpiCard(
                                title = stringResource(R.string.kpi_receipts),
                                value = Formatters.int(kpis.receipts),
                                icon = Icons.Outlined.MoveToInbox,
                                accent = colors.kpiTeal,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("kpi_receipts"),
                                subtitle = stringResource(R.string.kpi_receipts_sub, kpis.periodLabel),
                            )
                            KpiCard(
                                title = stringResource(R.string.kpi_dispatched),
                                value = Formatters.int(kpis.dispatched),
                                icon = Icons.Outlined.LocalShipping,
                                accent = colors.kpiBlue,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("kpi_dispatched"),
                                subtitle = stringResource(R.string.kpi_dispatched_sub, kpis.periodLabel),
                            )
                        }
                    }
                }
            }

            if (state.stocktaking.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.dashboard_stocktaking_title)) }
                items(state.stocktaking, key = { "st_" + it.name }) { session -> StocktakingSessionCard(session) { onOpenStocktaking(session.name) } }
            }

            state.pickerKpis?.let { kpis ->
                item {
                    SectionHeader(stringResource(R.string.kpi_picking_title))
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("picker_kpis")) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            KpiCard(
                                title = stringResource(R.string.kpi_rows_picked),
                                value = Formatters.int(kpis.rowsPicked),
                                icon = Icons.Outlined.TaskAlt,
                                accent = colors.kpiTeal,
                                modifier = Modifier.weight(1f).testTag("kpi_rows_picked"),
                                subtitle = stringResource(R.string.kpi_rows_picked_sub, Formatters.qty(kpis.qtyPicked)),
                            )
                            KpiCard(
                                title = stringResource(R.string.kpi_avg_row_time),
                                value = kpis.avgSecondsPerRow?.let { Formatters.duration(it) } ?: "-",
                                icon = Icons.Outlined.Timer,
                                accent = colors.kpiPurple,
                                modifier = Modifier.weight(1f).testTag("kpi_avg_row_time"),
                                subtitle = kpis.rowsPerHour?.let { stringResource(R.string.kpi_rows_per_hour, Formatters.qty(Math.round(it * 10) / 10.0)) }
                                    ?: stringResource(R.string.kpi_avg_row_time_sub),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            KpiCard(
                                title = stringResource(R.string.kpi_open_rows),
                                value = Formatters.int(kpis.openRows),
                                icon = Icons.Outlined.PendingActions,
                                accent = colors.kpiAmber,
                                modifier = Modifier.weight(1f).testTag("kpi_open_rows"),
                                subtitle = stringResource(R.string.kpi_open_rows_sub, kpis.openPickLists),
                            )
                            KpiCard(
                                title = stringResource(R.string.kpi_cards_completed),
                                value = Formatters.int(kpis.pickListsCompleted),
                                icon = Icons.Outlined.Checklist,
                                accent = colors.kpiBlue,
                                modifier = Modifier.weight(1f).testTag("kpi_cards_completed"),
                                subtitle = stringResource(R.string.kpi_cards_completed_sub, kpis.pickListsTouched),
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader(stringResource(R.string.dashboard_quick_actions))
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    QuickActionButton(stringResource(R.string.action_scan), Icons.Outlined.QrCodeScanner, colors.kpiPurple, onScan, Modifier.testTag("action_scan"))
                    QuickActionButton(stringResource(R.string.action_receive), Icons.Outlined.MoveToInbox, colors.kpiTeal, onReceive, Modifier.testTag("action_receive"))
                    QuickActionButton(stringResource(R.string.action_pick), Icons.Outlined.Checklist, colors.kpiBlue, onPick, Modifier.testTag("action_pick"))
                    QuickActionButton(stringResource(R.string.action_report), Icons.Outlined.Assessment, colors.kpiAmber, onReport, Modifier.testTag("action_report"))
                }
            }

            state.data?.let { data ->
                item { SectionHeader(stringResource(R.string.dashboard_recent_activity)) }
                if (data.recentActivity.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.History,
                            title = stringResource(R.string.dashboard_no_activity_title),
                            message = data.activityError?.toUiText()?.asString() ?: stringResource(R.string.dashboard_no_activity_message),
                        )
                    }
                } else {
                    items(data.recentActivity, key = { it.id }) { entry -> ActivityRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(entry: ActivityEntry) {
    val colors = WmsTheme.colors
    val positive = entry.qtyChange >= 0
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background((if (positive) colors.success else colors.danger).copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (positive) Icons.Outlined.MoveToInbox else Icons.Outlined.Outbox,
                    contentDescription = null,
                    tint = if (positive) colors.success else colors.danger,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.itemName ?: entry.itemCode, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${entry.voucherType} · ${entry.warehouse}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    (if (positive) "+" else "") + Formatters.qty(entry.qtyChange),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (positive) colors.success else colors.danger,
                )
                Text(Formatters.dateTime(entry.postingDate, entry.postingTime), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
