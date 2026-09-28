package com.wmserp.app.presentation.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wmserp.app.R
import com.wmserp.app.core.util.Formatters
import com.wmserp.app.domain.model.PickList
import com.wmserp.app.domain.model.PickingStatus
import com.wmserp.app.domain.model.PurchaseOrder
import com.wmserp.app.domain.model.SalesOrder
import com.wmserp.app.presentation.common.asString
import com.wmserp.app.presentation.common.labelRes
import com.wmserp.app.presentation.common.titleRes
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
    onOpenPickList: (String) -> Unit,
    viewModel: OrdersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Pick lists change state on their detail screen, so refresh silently when coming back.
    LifecycleResumeEffect(Unit) {
        viewModel.onResumed()
        onPauseOrDispose { }
    }
    // Scanning an order barcode on the list filters it (and opens it when it matches exactly).
    ScannerListener { code ->
        val value = code.value.trim()
        val po = state.purchaseOrders.firstOrNull { it.name.equals(value, ignoreCase = true) }
        val so = state.salesOrders.firstOrNull { it.name.equals(value, ignoreCase = true) }
        val pl = state.pickLists.firstOrNull { it.name.equals(value, ignoreCase = true) }
        when {
            po != null -> onOpenPurchaseOrder(po.name)
            so != null -> onOpenSalesOrder(so.name)
            pl != null -> onOpenPickList(pl.name)
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
        onOpenPickList = onOpenPickList,
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
    onOpenPickList: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.orders_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                stringResource(
                    when (state.tab) {
                        OrdersTab.RECEIVE -> R.string.orders_subtitle_receive
                        OrdersTab.DISPATCH -> R.string.orders_subtitle_dispatch
                        OrdersTab.PICK -> R.string.orders_subtitle_pick
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TabRow(selectedTabIndex = state.tab.ordinal, containerColor = MaterialTheme.colorScheme.background) {
            OrdersTab.entries.forEach { tab ->
                Tab(
                    selected = state.tab == tab,
                    onClick = { onSelectTab(tab) },
                    text = { Text(stringResource(tab.titleRes()), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.testTag("orders_tab_${tab.name}"),
                )
            }
        }
        SearchField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = stringResource(
                when (state.tab) {
                    OrdersTab.RECEIVE -> R.string.orders_search_po
                    OrdersTab.DISPATCH -> R.string.orders_search_so
                    OrdersTab.PICK -> R.string.orders_search_pick
                }
            ),
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
                if (state.tab == OrdersTab.PICK) {
                    state.pickListError?.let { error -> item { ErrorBanner(error.asString(), onRetry = onRefresh) } }
                } else {
                    state.error?.let { error -> item { ErrorBanner(error.asString(), onRetry = onRefresh) } }
                }
                if (state.isLoading) {
                    item { LoadingState(modifier = Modifier.height(220.dp)) }
                } else {
                    when (state.tab) {
                        OrdersTab.RECEIVE -> {
                            if (state.purchaseOrders.isEmpty()) {
                                item { EmptyState(Icons.Outlined.Inbox, stringResource(R.string.orders_empty_receive_title), stringResource(R.string.orders_empty_receive_message)) }
                            }
                            items(state.purchaseOrders, key = { it.name }) { po -> PurchaseOrderCard(po) { onOpenPurchaseOrder(po.name) } }
                        }
                        OrdersTab.DISPATCH -> {
                            if (state.salesOrders.isEmpty()) {
                                item { EmptyState(Icons.Outlined.Inbox, stringResource(R.string.orders_empty_dispatch_title), stringResource(R.string.orders_empty_dispatch_message)) }
                            }
                            items(state.salesOrders, key = { it.name }) { so -> SalesOrderCard(so) { onOpenSalesOrder(so.name) } }
                        }
                        OrdersTab.PICK -> {
                            val pickLists = state.filteredPickLists
                            if (pickLists.isEmpty()) {
                                item { EmptyState(Icons.Outlined.Inbox, stringResource(R.string.orders_empty_pick_title), stringResource(R.string.orders_empty_pick_message)) }
                            }
                            items(pickLists, key = { it.name }) { pl -> PickListCard(pl) { onOpenPickList(pl.name) } }
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
                label = stringResource(R.string.orders_received_pct, Formatters.percent(po.perReceived)),
                fraction = (po.perReceived / 100.0).toFloat(),
                valueText = Formatters.money(po.grandTotal, po.currency),
                color = WmsTheme.colors.kpiTeal,
            )
            Text(
                stringResource(R.string.orders_expected_ordered, Formatters.date(po.scheduleDate), Formatters.date(po.transactionDate)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                label = stringResource(R.string.orders_delivered_pct, Formatters.percent(so.perDelivered)),
                fraction = (so.perDelivered / 100.0).toFloat(),
                valueText = Formatters.money(so.grandTotal, so.currency),
                color = WmsTheme.colors.kpiBlue,
            )
            Text(
                stringResource(R.string.orders_deliver_by_ordered, Formatters.date(so.deliveryDate), Formatters.date(so.transactionDate)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PickListCard(pickList: PickList, onClick: () -> Unit) {
    val statusColor = when (pickList.pickingStatus) {
        PickingStatus.READY_TO_PICK -> WmsTheme.colors.info
        PickingStatus.PICKING -> WmsTheme.colors.warning
        PickingStatus.PICKED -> WmsTheme.colors.success
    }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().testTag("pick_${pickList.name}"),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(pickList.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        pickList.customerName ?: stringResource(pickList.purpose.labelRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusChip(stringResource(pickList.pickingStatus.labelRes()), statusColor)
            }
            ProgressRow(
                label = stringResource(R.string.pick_card_my_rows, pickList.myPickedRows, pickList.myRowCount),
                fraction = pickList.myProgress,
                valueText = stringResource(R.string.pick_card_progress, Formatters.qty(pickList.pickedQty), Formatters.qty(pickList.requiredQty)),
                color = statusColor,
            )
            Text(
                listOfNotNull(stringResource(pickList.purpose.labelRes()), pickList.parentWarehouse, stringResource(R.string.pick_card_items, pickList.itemCount)).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
