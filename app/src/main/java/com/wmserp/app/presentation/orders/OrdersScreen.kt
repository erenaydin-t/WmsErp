package com.wmserp.app.presentation.orders

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wmserp.app.core.util.Formatters
import com.wmserp.app.domain.model.PurchaseOrder
import com.wmserp.app.domain.model.SalesOrder
import com.wmserp.app.presentation.components.EmptyState
import com.wmserp.app.presentation.components.ErrorBanner
import com.wmserp.app.presentation.components.LoadingState
import com.wmserp.app.presentation.components.ScannerListener
import com.wmserp.app.presentation.components.SearchField
import com.wmserp.app.presentation.components.StatusChip
import com.wmserp.app.presentation.components.charts.ProgressRow
import com.wmserp.app.presentation.theme.WmsTheme

@Composable
fun OrdersRoute(
    onOpenPurchaseOrder: (String) -> Unit,
    onOpenSalesOrder: (String) -> Unit,
    viewModel: OrdersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Scanning an order barcode on the list filters it (and opens it when it matches exactly).
    ScannerListener { code ->
        val value = code.value.trim()
        val po = state.purchaseOrders.firstOrNull { it.name.equals(value, ignoreCase = true) }
        val so = state.salesOrders.firstOrNull { it.name.equals(value, ignoreCase = true) }
        when {
            po != null -> onOpenPurchaseOrder(po.name)
            so != null -> onOpenSalesOrder(so.name)
            else -> viewModel.onQueryChange(value)
        }
    }
    OrdersScreen(
        state = state,
        onSelectTab = viewModel::selectTab,
        onQueryChange = viewModel::onQueryChange,
        onRefresh = viewModel::refresh,
        onOpenPurchaseOrder = onOpenPurchaseOrder,
        onOpenSalesOrder = onOpenSalesOrder,
    )
}

@Composable
fun OrdersScreen(
    state: OrdersUiState,
    onSelectTab: (OrdersTab) -> Unit,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenPurchaseOrder: (String) -> Unit,
    onOpenSalesOrder: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("Orders", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                if (state.tab == OrdersTab.RECEIVE) "Purchase orders waiting to be received" else "Sales orders waiting to be dispatched",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TabRow(selectedTabIndex = state.tab.ordinal, containerColor = MaterialTheme.colorScheme.background) {
            OrdersTab.entries.forEach { tab ->
                Tab(
                    selected = state.tab == tab,
                    onClick = { onSelectTab(tab) },
                    text = { Text(tab.title) },
                    modifier = Modifier.testTag("orders_tab_${tab.name}"),
                )
            }
        }
        SearchField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = if (state.tab == OrdersTab.RECEIVE) "Search PO number or supplier" else "Search SO number or customer",
            leadingIcon = Icons.Outlined.Search,
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .testTag("orders_search"),
        )
        PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.error?.let { item { ErrorBanner(it, onRetry = onRefresh) } }
                if (state.isLoading) {
                    item { LoadingState(modifier = Modifier.height(220.dp)) }
                } else {
                    when (state.tab) {
                        OrdersTab.RECEIVE -> {
                            if (state.purchaseOrders.isEmpty()) {
                                item { EmptyState(Icons.Outlined.Inbox, "Nothing to receive", "Open purchase orders will show up here.") }
                            }
                            items(state.purchaseOrders, key = { it.name }) { po -> PurchaseOrderCard(po) { onOpenPurchaseOrder(po.name) } }
                        }
                        OrdersTab.DISPATCH -> {
                            if (state.salesOrders.isEmpty()) {
                                item { EmptyState(Icons.Outlined.Inbox, "Nothing to dispatch", "Open sales orders will show up here.") }
                            }
                            items(state.salesOrders, key = { it.name }) { so -> SalesOrderCard(so) { onOpenSalesOrder(so.name) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PurchaseOrderCard(po: PurchaseOrder, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().testTag("po_${po.name}"),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(po.name, style = MaterialTheme.typography.titleSmall)
                    Text(po.supplierName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StatusChip(po.status, WmsTheme.colors.warning)
            }
            ProgressRow(
                label = "Received ${Formatters.percent(po.perReceived)}",
                fraction = (po.perReceived / 100.0).toFloat(),
                valueText = Formatters.money(po.grandTotal, po.currency),
                color = WmsTheme.colors.kpiTeal,
            )
            Text("Expected ${Formatters.date(po.scheduleDate)} · ordered ${Formatters.date(po.transactionDate)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SalesOrderCard(so: SalesOrder, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().testTag("so_${so.name}"),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(so.name, style = MaterialTheme.typography.titleSmall)
                    Text(so.customerName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StatusChip(so.status, WmsTheme.colors.info)
            }
            ProgressRow(
                label = "Delivered ${Formatters.percent(so.perDelivered)}",
                fraction = (so.perDelivered / 100.0).toFloat(),
                valueText = Formatters.money(so.grandTotal, so.currency),
                color = WmsTheme.colors.kpiBlue,
            )
            Text("Deliver by ${Formatters.date(so.deliveryDate)} · ordered ${Formatters.date(so.transactionDate)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(0.dp))
        }
    }
}
