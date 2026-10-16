package com.wmserp.app.presentation.browse

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.wmserp.app.domain.model.Item
import com.wmserp.app.domain.model.OrderDoctype
import com.wmserp.app.domain.model.OrderDocument
import com.wmserp.app.presentation.common.asString
import com.wmserp.app.presentation.components.EmptyState
import com.wmserp.app.presentation.components.ErrorBanner
import com.wmserp.app.presentation.components.LoadingState
import com.wmserp.app.presentation.components.ScannerListener
import com.wmserp.app.presentation.components.SearchField
import com.wmserp.app.presentation.components.StatusChip
import com.wmserp.app.presentation.components.WmsTopBar
import com.wmserp.app.presentation.details.DocumentDetailSheet
import com.wmserp.app.presentation.details.DocumentDetailViewModel
import com.wmserp.app.presentation.details.ItemDetailSheet
import com.wmserp.app.presentation.details.ItemDetailViewModel
import com.wmserp.app.presentation.details.labelRes
import com.wmserp.app.presentation.details.stageColor
import com.wmserp.app.presentation.details.stageText
import com.wmserp.app.presentation.theme.WmsTheme

/** The list behind a KPI card. A tap on an entry opens its popup; a scan filters the list. */
@Composable
fun BrowseRoute(
    onBack: () -> Unit,
    onOpenPickList: (String) -> Unit,
    onReceive: (String) -> Unit,
    viewModel: BrowseViewModel = hiltViewModel(),
    itemDetail: ItemDetailViewModel = hiltViewModel(),
    documentDetail: DocumentDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ScannerListener { code -> viewModel.onQueryChange(code.value.trim()) }
    BrowseScreen(
        state = state,
        onBack = onBack,
        onQueryChange = viewModel::onQueryChange,
        onSelectSegment = viewModel::selectSegment,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onOpenItem = itemDetail::open,
        onOpenDocument = documentDetail::open,
    )
    DocumentDetailSheet(documentDetail, onOpenItem = itemDetail::open, onOpenPickList = onOpenPickList, onReceive = onReceive)
    ItemDetailSheet(itemDetail)
}

@Composable
fun BrowseScreen(
    state: BrowseUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelectSegment: (OrderSegment) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenItem: (String) -> Unit,
    onOpenDocument: (OrderDocument) -> Unit,
) {
    Scaffold(
        topBar = {
            WmsTopBar(
                title = stringResource(state.kind.titleRes),
                subtitle = when {
                    state.since != null -> stringResource(R.string.browse_since, Formatters.date(state.since))
                    state.kind.open -> stringResource(R.string.browse_open_only)
                    else -> null
                },
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            SearchField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = stringResource(if (state.kind.isItemList) R.string.browse_search_items else R.string.browse_search_documents),
                leadingIcon = Icons.Outlined.Search,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).testTag("browse_search"),
            )
            if (state.showsSegments) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                    OrderSegment.entries.forEachIndexed { index, segment ->
                        SegmentedButton(
                            selected = state.segment == segment,
                            onClick = { onSelectSegment(segment) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = OrderSegment.entries.size),
                            modifier = Modifier.testTag("browse_segment_${segment.name}"),
                        ) { Text(stringResource(segment.labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("browse_list"),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    state.error?.let { error -> item { ErrorBanner(error.asString(), onRetry = onRefresh) } }
                    if (state.isLoading) {
                        item { LoadingState(modifier = Modifier.height(220.dp)) }
                    } else if (state.isEmpty) {
                        item {
                            EmptyState(
                                Icons.Outlined.Inbox,
                                stringResource(R.string.browse_empty_title),
                                stringResource(if (state.kind.isItemList) R.string.browse_empty_items else R.string.browse_empty_documents),
                            )
                        }
                    } else if (state.kind.isItemList) {
                        items(state.items, key = { it.code }) { item -> ItemCard(item) { onOpenItem(item.code) } }
                    } else {
                        items(state.documents, key = { "${it.doctype.name}:${it.name}" }) { document ->
                            DocumentCard(document, showDoctype = state.showsSegments) { onOpenDocument(document) }
                        }
                    }
                    if (!state.isLoading && state.canLoadMore) {
                        item {
                            // Fetches the next page as soon as the end of the list scrolls into view.
                            LaunchedEffect(state.items.size, state.documents.size) { onLoadMore() }
                            Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                if (state.isLoadingMore) CircularProgressIndicator(modifier = Modifier.height(24.dp).width(24.dp))
                                else TextButton(onClick = onLoadMore, modifier = Modifier.testTag("browse_load_more")) { Text(stringResource(R.string.browse_load_more)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemCard(item: Item, onClick: () -> Unit) {
    val colors = WmsTheme.colors
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().testTag("item_${item.code}"),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(item.code, item.group, item.stockUom).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (item.hasBatchNo) StatusChip(stringResource(R.string.item_chip_batch), colors.kpiTeal)
            if (item.hasSerialNo) StatusChip(stringResource(R.string.item_chip_serial), colors.kpiBlue, modifier = Modifier.padding(start = 4.dp))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A Sales / Purchase Order, Receipt, Delivery Note or Pick List in a list: number, party, stage, dates and rows. */
@Composable
fun DocumentCard(document: OrderDocument, showDoctype: Boolean = false, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().testTag("doc_${document.name}"),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(document.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        document.partyName.ifBlank { document.reference ?: stringResource(document.doctype.labelRes()) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusChip(document.stageText(), document.stageColor())
            }
            val details = listOfNotNull(
                if (showDoctype) stringResource(document.doctype.labelRes()) else null,
                document.date?.let { Formatters.date(it) },
                document.dueDate?.let { stringResource(if (document.doctype == OrderDoctype.PURCHASE_ORDER) R.string.doc_expected else R.string.doc_due, Formatters.date(it)) },
                document.percentDone?.let { stringResource(if (document.doctype == OrderDoctype.PURCHASE_ORDER) R.string.doc_received_percent else R.string.doc_delivered_percent, Formatters.percent(it)) },
                document.warehouse,
            )
            Text(details.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (document.doctype != OrderDoctype.PICK_LIST) {
                Text(
                    stringResource(R.string.orders_receipt_rows, document.itemCount.takeIf { it > 0 } ?: document.items.size, Formatters.qty(document.totalQty)).takeIf { document.totalQty > 0 || document.itemCount > 0 }
                        ?: stringResource(R.string.scan_label_total_qty) + " " + Formatters.qty(document.totalQty),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
