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
import com.wmserp.app.domain.model.PurchaseReceipt
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
import com.wmserp.app.presentation.stocktaking.StocktakingSessionCard
import com.wmserp.app.presentation.theme.WmsTheme

@Composable
fun OrdersRoute(
    onOpenPurchaseReceipt: (String) -> Unit,
    onOpenPickList: (String) -> Unit,
    onOpenStocktaking: (String) -> Unit = {},
    viewModel: OrdersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Receipts and pick lists change state on their detail screens, so refresh silently when coming back.
    LifecycleResumeEffect(Unit) {
        viewModel.onResumed()
        onPauseOrDispose { }
    }
    // Scanning a document barcode on the list filters it (and opens it when it matches exactly).
    ScannerListener { code ->
        val value = code.value.trim()
        val pr = state.receipts.firstOrNull { it.name.equals(value, ignoreCase = true) }
        val pl = state.pickLists.firstOrNull { it.name.equals(value, ignoreCase = true) }
        val st = state.sessions.firstOrNull { it.name.equals(value, ignoreCase = true) }
        when {
            pr != null -> onOpenPurchaseReceipt(pr.name)
            pl != null -> onOpenPickList(pl.name)
            st != null -> onOpenStocktaking(st.name)
            else -> viewModel.onQueryChange(value)
        }
    }
    OrdersScreen(
        state = state,
        onSelectTab = viewModel::selectTab,
        onQueryChange = viewModel::onQueryChange,
        onRefresh = viewModel::refresh,
        onOpenPurchaseReceipt = onOpenPurchaseReceipt,
        onOpenPickList = onOpenPickList,
        onOpenStocktaking = onOpenStocktaking,
    )
}

@Composable
fun OrdersScreen(
    state: OrdersUiState,
    onSelectTab: (OrdersTab) -> Unit,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenPurchaseReceipt: (String) -> Unit,
    onOpenPickList: (String) -> Unit,
    onOpenStocktaking: (String) -> Unit = {},
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
                        OrdersTab.PICK -> R.string.orders_subtitle_pick
                        OrdersTab.COUNT -> R.string.orders_subtitle_count
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
                    OrdersTab.RECEIVE -> R.string.orders_search_receipt
                    OrdersTab.PICK -> R.string.orders_search_pick
                    OrdersTab.COUNT -> R.string.orders_search_count
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
                when (state.tab) {
                    OrdersTab.RECEIVE -> state.error?.let { error -> item { ErrorBanner(error.asString(), onRetry = onRefresh) } }
                    OrdersTab.PICK -> state.pickListError?.let { error -> item { ErrorBanner(error.asString(), onRetry = onRefresh) } }
                    OrdersTab.COUNT -> state.sessionError?.let { error -> item { ErrorBanner(error.asString(), onRetry = onRefresh) } }
                }
                if (state.isLoading) {
                    item { LoadingState(modifier = Modifier.height(220.dp)) }
                } else {
                    when (state.tab) {
                        OrdersTab.RECEIVE -> {
                            if (state.receipts.isEmpty()) {
                                item { EmptyState(Icons.Outlined.Inbox, stringResource(R.string.orders_empty_receive_title), stringResource(R.string.orders_empty_receive_message)) }
                            }
                            items(state.receipts, key = { it.name }) { receipt -> PurchaseReceiptCard(receipt) { onOpenPurchaseReceipt(receipt.name) } }
                        }
                        OrdersTab.PICK -> {
                            val pickLists = state.filteredPickLists
                            if (pickLists.isEmpty()) {
                                item { EmptyState(Icons.Outlined.Inbox, stringResource(R.string.orders_empty_pick_title), stringResource(R.string.orders_empty_pick_message)) }
                            }
                            items(pickLists, key = { it.name }) { pl -> PickListCard(pl) { onOpenPickList(pl.name) } }
                        }
                        OrdersTab.COUNT -> {
                            val sessions = state.filteredSessions
                            if (sessions.isEmpty()) {
                                item { EmptyState(Icons.Outlined.Inbox, stringResource(R.string.orders_empty_count_title), stringResource(R.string.orders_empty_count_message)) }
                            }
                            items(sessions, key = { it.name }) { session ->
                                StocktakingSessionCard(session, pendingCounts = state.pendingCounts[session.name] ?: 0) { onOpenStocktaking(session.name) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A draft Purchase Receipt waiting for the warehouse: supplier, stage, rows and units; never an amount. */
@Composable
private fun PurchaseReceiptCard(receipt: PurchaseReceipt, onClick: () -> Unit) {
    val stage = receipt.workflowState?.takeIf { it.isNotBlank() } ?: receipt.status ?: stringResource(R.string.receive_stage_draft)
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().testTag("pr_${receipt.name}"),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(receipt.name, style = MaterialTheme.typography.titleSmall)
                    Text(receipt.supplierName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StatusChip(stage, if (receipt.canReceive) WmsTheme.colors.warning else WmsTheme.colors.info)
            }
            Text(
                stringResource(R.string.orders_receipt_rows, receipt.itemCount, Formatters.qty(receipt.totalQty)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                listOfNotNull(
                    stringResource(R.string.orders_receipt_date, Formatters.date(receipt.postingDate)),
                    receipt.setWarehouse,
                    receipt.supplierDeliveryNote?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.receive_supplier_note, it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
