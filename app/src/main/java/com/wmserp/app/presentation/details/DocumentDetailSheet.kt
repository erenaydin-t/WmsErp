package com.wmserp.app.presentation.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.wmserp.app.domain.model.OrderDoctype
import com.wmserp.app.domain.model.OrderDocument
import com.wmserp.app.domain.model.OrderLine
import com.wmserp.app.presentation.common.asString
import com.wmserp.app.presentation.components.ErrorBanner
import com.wmserp.app.presentation.components.LabelValue
import com.wmserp.app.presentation.components.LoadingState
import com.wmserp.app.presentation.components.StatusChip
import com.wmserp.app.presentation.components.charts.ProgressRow

/**
 * The read-only document popup: party, stage, dates, warehouse and every row. A row opens the
 * item popup; a Pick List offers to open the picking screen and a draft Purchase Receipt the
 * receiving screen. Shown while [DocumentDetailViewModel.uiState] is open.
 */
@Composable
fun DocumentDetailSheet(
    viewModel: DocumentDetailViewModel,
    onOpenItem: (String) -> Unit,
    onOpenPickList: ((String) -> Unit)? = null,
    onReceive: ((String) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (!state.isOpen) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = viewModel::close, sheetState = sheetState, modifier = Modifier.testTag("document_detail_sheet")) {
        DocumentDetailContent(
            state = state,
            onRetry = viewModel::retry,
            onOpenItem = onOpenItem,
            onOpenPickList = onOpenPickList?.let { open -> { name -> viewModel.close(); open(name) } },
            onReceive = onReceive?.let { receive -> { name -> viewModel.close(); receive(name) } },
        )
    }
}

@Composable
fun DocumentDetailContent(
    state: DocumentDetailUiState,
    onRetry: () -> Unit,
    onOpenItem: (String) -> Unit,
    onOpenPickList: ((String) -> Unit)? = null,
    onReceive: ((String) -> Unit)? = null,
) {
    val ref = state.ref ?: return
    val document = state.document
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(ref.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("document_detail_name"))
                Text(stringResource(ref.doctype.labelRes()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (document != null) StatusChip(document.stageText(), document.stageColor())
        }
        state.error?.let { ErrorBanner(it.asString(), onRetry = onRetry) }
        if (state.isLoading && document == null) LoadingState(modifier = Modifier.height(160.dp))
        if (document != null) {
            DocumentHeader(document)
            if (document.percentDone != null) {
                ProgressRow(
                    label = stringResource(if (document.doctype == OrderDoctype.SALES_ORDER) R.string.doc_delivered_percent else R.string.doc_received_percent, Formatters.percent(document.percentDone)),
                    fraction = (document.percentDone / 100.0).toFloat().coerceIn(0f, 1f),
                    valueText = Formatters.percent(document.percentDone),
                    color = document.stageColor(),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.detail_rows_title, document.items.size), style = MaterialTheme.typography.titleSmall)
            if (document.items.isEmpty() && state.isLoading) {
                LoadingState(modifier = Modifier.height(80.dp))
            } else if (document.items.isEmpty()) {
                Text(stringResource(R.string.orders_receipt_rows, document.itemCount, Formatters.qty(document.totalQty)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.detail_tap_row_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                document.items.forEach { line -> DocumentLineRow(line) { onOpenItem(line.itemCode) } }
            }
            if (document.doctype == OrderDoctype.PICK_LIST && onOpenPickList != null && !document.isCancelled) {
                Button(onClick = { onOpenPickList(document.name) }, modifier = Modifier.fillMaxWidth().testTag("document_open_pick_list")) {
                    Text(stringResource(R.string.detail_open_pick_list))
                }
            }
            if (document.doctype == OrderDoctype.PURCHASE_RECEIPT && onReceive != null && document.docStatus == 0) {
                Button(onClick = { onReceive(document.name) }, modifier = Modifier.fillMaxWidth().testTag("document_receive")) {
                    Text(stringResource(R.string.detail_receive))
                }
            }
        }
    }
}

@Composable
private fun DocumentHeader(document: OrderDocument) {
    val partyLabel = when (document.doctype) {
        OrderDoctype.PURCHASE_ORDER, OrderDoctype.PURCHASE_RECEIPT -> R.string.scan_label_supplier
        else -> R.string.label_customer
    }
    if (document.partyName.isNotBlank()) {
        LabelValue(stringResource(partyLabel), document.partyName + (document.party.takeIf { it.isNotBlank() && it != document.partyName }?.let { " ($it)" } ?: ""))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LabelValue(stringResource(R.string.label_date), Formatters.date(document.date), Modifier.weight(1f))
        when (document.doctype) {
            OrderDoctype.SALES_ORDER -> LabelValue(stringResource(R.string.label_due_date), Formatters.date(document.dueDate), Modifier.weight(1f))
            OrderDoctype.PURCHASE_ORDER -> LabelValue(stringResource(R.string.label_expected_date), Formatters.date(document.dueDate), Modifier.weight(1f))
            else -> LabelValue(stringResource(R.string.scan_label_total_qty), Formatters.qty(document.totalQty), Modifier.weight(1f))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LabelValue(stringResource(R.string.label_warehouse), document.warehouse ?: "-", Modifier.weight(1f))
        when (document.doctype) {
            OrderDoctype.SALES_ORDER -> LabelValue(stringResource(R.string.label_customer_po), document.reference ?: "-", Modifier.weight(1f))
            OrderDoctype.PURCHASE_RECEIPT -> LabelValue(stringResource(R.string.label_supplier_note), document.reference ?: "-", Modifier.weight(1f))
            OrderDoctype.PICK_LIST -> LabelValue(stringResource(R.string.label_purpose), document.reference ?: "-", Modifier.weight(1f))
            else -> LabelValue(stringResource(R.string.scan_label_company), document.company ?: "-", Modifier.weight(1f))
        }
    }
}

@Composable
private fun DocumentLineRow(line: OrderLine, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
            .testTag("document_line_${line.idx}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(line.itemName, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(line.itemCode, line.warehouse, line.batchNo?.let { stringResource(R.string.detail_line_batch, it) }, line.against).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("${Formatters.qty(line.qty)} ${line.uom.orEmpty()}".trim(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            line.doneQty?.let {
                Text(stringResource(R.string.detail_line_done, Formatters.qty(it), Formatters.qty(line.qty)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
