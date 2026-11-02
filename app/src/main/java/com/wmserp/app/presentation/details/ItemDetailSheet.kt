package com.wmserp.app.presentation.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wmserp.app.R
import com.wmserp.app.core.util.Formatters
import com.wmserp.app.domain.model.ItemDetail
import com.wmserp.app.presentation.common.asString
import com.wmserp.app.presentation.components.ErrorBanner
import com.wmserp.app.presentation.components.LabelValue
import com.wmserp.app.presentation.components.LoadingState
import com.wmserp.app.presentation.components.StatusChip
import com.wmserp.app.presentation.theme.WmsTheme

/**
 * The item popup: everything the warehouse needs to know about an item (code, name, group, brand,
 * UOM, tracking, barcodes, description) and its stock per warehouse. Quantities only, never values.
 * Rendered by every screen that lists items; it shows while [ItemDetailViewModel.uiState] is open.
 */
@Composable
fun ItemDetailSheet(viewModel: ItemDetailViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (!state.isOpen) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = viewModel::close, sheetState = sheetState, modifier = Modifier.testTag("item_detail_sheet")) {
        ItemDetailContent(state = state, onRetry = viewModel::retry)
    }
}

@Composable
fun ItemDetailContent(state: ItemDetailUiState, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val detail = state.detail
        Text(
            detail?.item?.name ?: state.itemCode.orEmpty(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("item_detail_name"),
        )
        Text(
            stringResource(R.string.item_label_code) + ": " + (detail?.item?.code ?: state.itemCode.orEmpty()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.error?.let { ErrorBanner(it.asString(), onRetry = onRetry) }
        if (state.isLoading && detail == null) {
            LoadingState(modifier = Modifier.height(160.dp))
        }
        if (detail != null) ItemDetailBody(detail)
    }
}

@Composable
private fun ItemDetailBody(detail: ItemDetail) {
    val colors = WmsTheme.colors
    val item = detail.item
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (item.disabled) StatusChip(stringResource(R.string.scan_chip_disabled), colors.danger)
        if (item.hasBatchNo) StatusChip(stringResource(R.string.item_chip_batch), colors.kpiTeal)
        if (item.hasSerialNo) StatusChip(stringResource(R.string.item_chip_serial), colors.kpiBlue)
        if (!item.isStockItem) StatusChip(stringResource(R.string.item_chip_non_stock), colors.info)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LabelValue(stringResource(R.string.scan_label_group), item.group ?: "-", Modifier.weight(1f))
        LabelValue(stringResource(R.string.item_label_brand), item.brand ?: "-", Modifier.weight(1f))
        LabelValue(stringResource(R.string.scan_label_uom), item.stockUom ?: "-", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LabelValue(stringResource(R.string.scan_label_total_stock), Formatters.qty(detail.totalQty), Modifier.weight(1f).testTag("item_detail_total"))
        LabelValue(stringResource(R.string.item_label_reserved), Formatters.qty(detail.reservedQty), Modifier.weight(1f))
        LabelValue(stringResource(R.string.item_label_available), Formatters.qty(detail.totalQty - detail.reservedQty), Modifier.weight(1f))
    }
    if (item.barcodes.isNotEmpty()) LabelValue(stringResource(R.string.scan_label_barcodes), item.barcodes.joinToString(", "))
    item.description?.takeIf { it.isNotBlank() && it != item.name }?.let { LabelValue(stringResource(R.string.item_label_description), it) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(stringResource(R.string.item_stock_title), style = MaterialTheme.typography.titleSmall)
    if (detail.stock.isEmpty()) {
        Text(stringResource(R.string.scan_no_stock), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        detail.stock.sortedByDescending { it.actualQty }.forEach { level ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(level.warehouse, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Column(horizontalAlignment = Alignment.End) {
                    Text(Formatters.qty(level.actualQty), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    if (level.reservedQty > 0) {
                        Text(stringResource(R.string.scan_reserved, Formatters.qty(level.reservedQty)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
