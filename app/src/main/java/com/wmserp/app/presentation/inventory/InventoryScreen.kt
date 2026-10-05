package com.wmserp.app.presentation.inventory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wmserp.app.R
import com.wmserp.app.core.util.Formatters
import com.wmserp.app.domain.model.ActivityHeatmap
import com.wmserp.app.domain.model.DeliveryDelayReport
import com.wmserp.app.domain.model.InventoryAnalytics
import com.wmserp.app.domain.model.StockAgingReport
import com.wmserp.app.presentation.common.asString
import com.wmserp.app.presentation.common.titleRes
import com.wmserp.app.presentation.common.toUiText
import com.wmserp.app.presentation.components.EmptyState
import com.wmserp.app.presentation.components.ErrorBanner
import com.wmserp.app.presentation.components.InfoBanner
import com.wmserp.app.presentation.components.KpiCard
import com.wmserp.app.presentation.components.LoadingState
import com.wmserp.app.presentation.components.StatusChip
import com.wmserp.app.presentation.components.charts.BarChart
import com.wmserp.app.presentation.components.charts.BarEntry
import com.wmserp.app.presentation.components.charts.HeatmapGrid
import com.wmserp.app.presentation.components.charts.ProgressRow
import com.wmserp.app.presentation.components.charts.StackedDistributionBar
import com.wmserp.app.presentation.components.charts.StackedSegment
import com.wmserp.app.presentation.theme.WmsTheme
import java.util.Locale

@Composable
fun InventoryRoute(viewModel: InventoryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    InventoryScreen(state = state, onSelectTab = viewModel::selectTab, onRefresh = viewModel::refresh, onRetry = { viewModel.load() })
}

@Composable
fun InventoryScreen(
    state: InventoryUiState,
    onSelectTab: (AnalyticsTab) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(stringResource(R.string.inventory_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.inventory_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), onRetry = onRetry) }
            }
            if (state.isLoading && state.analytics == null) {
                item { LoadingState(modifier = Modifier.height(260.dp), message = stringResource(R.string.inventory_loading)) }
            }
            state.analytics?.let { analytics ->
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        KpiCard(stringResource(R.string.kpi_pending_deliveries), Formatters.int(analytics.kpis.pendingDeliveries), Icons.Outlined.LocalShipping, colors.kpiBlue, Modifier.weight(1f).testTag("kpi_pending_deliveries"))
                        KpiCard(stringResource(R.string.kpi_receipts_7d), Formatters.int(analytics.kpis.receipts), Icons.Outlined.MoveToInbox, colors.kpiTeal, Modifier.weight(1f).testTag("kpi_receipts"))
                        KpiCard(stringResource(R.string.kpi_picklists), Formatters.int(analytics.kpis.pickLists), Icons.Outlined.Checklist, colors.kpiPurple, Modifier.weight(1f).testTag("kpi_picklists"))
                    }
                }
                if (analytics.errors.isNotEmpty()) {
                    item { PartialErrors(analytics) }
                }
                item {
                    TabRow(
                        selectedTabIndex = state.selectedTab.ordinal,
                        containerColor = MaterialTheme.colorScheme.background,
                        modifier = Modifier.testTag("analytics_tabs"),
                    ) {
                        AnalyticsTab.entries.forEach { tab ->
                            Tab(
                                selected = state.selectedTab == tab,
                                onClick = { onSelectTab(tab) },
                                text = { Text(stringResource(tab.titleRes()), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.testTag("tab_${tab.name}"),
                            )
                        }
                    }
                }
                when (state.selectedTab) {
                    AnalyticsTab.DELIVERY_DELAYS -> deliveryDelaysContent(analytics.delays)
                    AnalyticsTab.ACTIVITY_HEATMAP -> heatmapContent(analytics.heatmap)
                    AnalyticsTab.STOCK_AGING -> stockAgingContent(analytics.aging)
                }
            }
        }
    }
}

@Composable
private fun PartialErrors(analytics: InventoryAnalytics) {
    val lines = analytics.errors.map { stringResource(R.string.analytics_error_format, stringResource(it.section.titleRes()), it.error.toUiText().asString()) }
    InfoBanner(lines.joinToString("\n"), container = WmsTheme.colors.warningContainer, content = MaterialTheme.colorScheme.onSurface)
}

private fun LazyListScope.deliveryDelaysContent(report: DeliveryDelayReport?) {
    if (report == null) {
        item { EmptyState(Icons.Outlined.Schedule, stringResource(R.string.delays_unavailable_title), stringResource(R.string.delays_unavailable_message)) }
        return
    }
    item {
        ChartCard(
            title = stringResource(R.string.delays_chart_title),
            subtitle = stringResource(R.string.delays_chart_subtitle, report.totalOverdue, "%.1f".format(Locale.US, report.averageDaysLate)),
        ) {
            if (report.orders.isEmpty()) {
                Text(stringResource(R.string.delays_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                BarChart(entries = report.buckets.map { BarEntry(it.label, it.count.toDouble()) })
            }
        }
    }
    items(report.orders.take(10), key = { it.orderName }) { delay ->
        val colors = WmsTheme.colors
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.medium,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(delay.orderName, style = MaterialTheme.typography.titleSmall)
                    Text(delay.customerName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.delays_due, Formatters.date(delay.deliveryDate), delay.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(
                    text = stringResource(R.string.delays_days_late, delay.daysLate),
                    color = when {
                        delay.daysLate >= 15 -> colors.danger
                        delay.daysLate >= 4 -> colors.warning
                        else -> colors.info
                    },
                )
            }
        }
    }
}

private fun LazyListScope.heatmapContent(heatmap: ActivityHeatmap?) {
    if (heatmap == null) {
        item { EmptyState(Icons.Outlined.Insights, stringResource(R.string.heatmap_unavailable_title), stringResource(R.string.heatmap_unavailable_message)) }
        return
    }
    item {
        ChartCard(
            title = stringResource(R.string.heatmap_chart_title),
            subtitle = stringResource(R.string.heatmap_chart_subtitle, heatmap.total),
        ) {
            HeatmapGrid(dayLabels = heatmap.dayLabels, blockLabels = heatmap.blockLabels, cells = heatmap.cells)
        }
    }
    item {
        val busiest = heatmap.cells.withIndex().maxByOrNull { it.value.sum() }
        if (busiest != null && busiest.value.sum() > 0) {
            InfoBanner(stringResource(R.string.heatmap_busiest, heatmap.dayLabels.getOrNull(busiest.index) ?: "-", busiest.value.sum()))
        }
    }
}

private fun LazyListScope.stockAgingContent(report: StockAgingReport?) {
    if (report == null) {
        item { EmptyState(Icons.Outlined.Schedule, stringResource(R.string.aging_unavailable_title), stringResource(R.string.aging_unavailable_message)) }
        return
    }
    item {
        val colors = WmsTheme.colors
        val palette = listOf(colors.kpiTeal, colors.kpiBlue, colors.kpiAmber, colors.danger, colors.kpiPurple)
        ChartCard(title = stringResource(R.string.aging_chart_title), subtitle = stringResource(R.string.aging_chart_subtitle)) {
            if (report.rows.isEmpty()) {
                Text(stringResource(R.string.aging_no_data), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                StackedDistributionBar(
                    segments = report.rangeLabels.mapIndexed { i, label ->
                        StackedSegment(label, report.totalsByRange.getOrElse(i) { 0.0 }, palette[i % palette.size])
                    }
                )
            }
        }
    }
    val maxAge = report.rows.maxOfOrNull { it.averageAge }?.takeIf { it > 0 } ?: 1.0
    items(report.rows.take(10), key = { "${it.itemCode}-${it.warehouse}" }) { row ->
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.medium,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                ProgressRow(
                    label = "${row.itemName} (${row.itemCode})",
                    fraction = (row.averageAge / maxAge).toFloat(),
                    valueText = stringResource(R.string.aging_days, row.averageAge.toInt()),
                    color = if (row.averageAge > 90) WmsTheme.colors.danger else if (row.averageAge > 60) WmsTheme.colors.warning else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.aging_qty, Formatters.qty(row.totalQty)) + (row.warehouse?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChartCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}
