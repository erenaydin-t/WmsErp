package com.wmserp.app.presentation.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wmserp.app.R
import com.wmserp.app.core.util.Formatters
import com.wmserp.app.domain.model.Batch
import com.wmserp.app.domain.model.PurchaseReceiptItem
import com.wmserp.app.domain.model.ReceiptDifference
import com.wmserp.app.domain.model.ReceiveResult
import com.wmserp.app.domain.model.ScanSource
import com.wmserp.app.domain.model.ScannedCode
import com.wmserp.app.presentation.common.asString
import com.wmserp.app.presentation.components.CameraScannerSheet
import com.wmserp.app.presentation.components.EmptyState
import com.wmserp.app.presentation.components.ErrorBanner
import com.wmserp.app.presentation.components.InfoBanner
import com.wmserp.app.presentation.components.LabelValue
import com.wmserp.app.presentation.components.LoadingState
import com.wmserp.app.presentation.components.QtyStepper
import com.wmserp.app.presentation.components.RequiredFieldsDialog
import com.wmserp.app.presentation.components.ScanQuantityDialog
import com.wmserp.app.presentation.components.ScannerListener
import com.wmserp.app.presentation.components.StatusChip
import com.wmserp.app.presentation.components.WarehousePicker
import com.wmserp.app.presentation.components.WmsTopBar
import com.wmserp.app.presentation.components.scannerAwareFocus
import com.wmserp.app.presentation.theme.WmsTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Everything the receive screen can ask the view model to do; grouped so the screen signature stays readable. */
data class ReceiveActions(
    val onSetQty: (String, String) -> Unit = { _, _ -> },
    val onIncrement: (String) -> Unit = {},
    val onDecrement: (String) -> Unit = {},
    val onSetBatchQty: (String, String, String) -> Unit = { _, _, _ -> },
    val onIncrementBatch: (String, String) -> Unit = { _, _ -> },
    val onDecrementBatch: (String, String) -> Unit = { _, _ -> },
    val onRemoveBatch: (String, String) -> Unit = { _, _ -> },
    val onAddBatch: (String) -> Unit = {},
    val onReceiveAll: () -> Unit = {},
    val onClearAll: () -> Unit = {},
    val onWarehouseChange: (String) -> Unit = {},
    val onSubmit: (asDraft: Boolean) -> Unit = {},
    val onConfirmDifferences: () -> Unit = {},
    val onCancelConfirmation: () -> Unit = {},
    val onDismissMessage: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onRequiredFieldChange: (String, String) -> Unit = { _, _ -> },
    val onConfirmRequiredFields: () -> Unit = {},
    val onDismissRequiredFields: () -> Unit = {},
    val onToggleCamera: () -> Unit = {},
    val onCameraBarcode: (String, String?) -> Unit = { _, _ -> },
    val onPendingQtyChange: (String) -> Unit = {},
    val onPendingIncrement: () -> Unit = {},
    val onPendingDecrement: () -> Unit = {},
    val onPendingAll: () -> Unit = {},
    val onConfirmPending: () -> Unit = {},
    val onCancelPending: () -> Unit = {},
    val onBatchCodeChange: (String) -> Unit = {},
    val onLookupBatch: () -> Unit = {},
    val onSelectFoundBatch: (Batch) -> Unit = {},
    val onBatchQcNoChange: (String) -> Unit = {},
    val onCreateAnotherBatch: () -> Unit = {},
    val onChooseBatchRow: (String) -> Unit = {},
    val onBatchManufacturingDateChange: (String) -> Unit = {},
    val onBatchExpiryDateChange: (String) -> Unit = {},
    val onCreateBatch: () -> Unit = {},
    val onCloseBatchSheet: () -> Unit = {},
)

@Composable
fun ReceiveRoute(onBack: () -> Unit, viewModel: ReceiveViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ScannerListener(enabled = state.completed == null && !state.isBusy) { viewModel.onScanned(it) }
    ReceiveScreen(
        state = state,
        onBack = onBack,
        actions = ReceiveActions(
            onSetQty = viewModel::setQty,
            onIncrement = viewModel::increment,
            onDecrement = viewModel::decrement,
            onSetBatchQty = viewModel::setBatchQty,
            onIncrementBatch = viewModel::incrementBatch,
            onDecrementBatch = viewModel::decrementBatch,
            onRemoveBatch = viewModel::removeBatch,
            onAddBatch = viewModel::openBatchSheet,
            onReceiveAll = viewModel::receiveAll,
            onClearAll = viewModel::clearAll,
            onWarehouseChange = viewModel::setWarehouse,
            onSubmit = viewModel::submit,
            onConfirmDifferences = viewModel::confirmDifferences,
            onCancelConfirmation = viewModel::cancelConfirmation,
            onDismissMessage = viewModel::dismissMessage,
            onRetry = viewModel::load,
            onRequiredFieldChange = viewModel::setRequiredFieldAnswer,
            onConfirmRequiredFields = viewModel::confirmRequiredFields,
            onDismissRequiredFields = viewModel::dismissRequiredFields,
            onToggleCamera = viewModel::toggleCamera,
            onCameraBarcode = { value, symbology -> viewModel.onScanned(ScannedCode(value, ScanSource.CAMERA, symbology)) },
            onPendingQtyChange = viewModel::setPendingQty,
            onPendingIncrement = viewModel::incrementPendingQty,
            onPendingDecrement = viewModel::decrementPendingQty,
            onPendingAll = viewModel::setPendingAll,
            onConfirmPending = viewModel::confirmPendingScan,
            onCancelPending = viewModel::cancelPendingScan,
            onBatchCodeChange = viewModel::setBatchCode,
            onLookupBatch = viewModel::lookupBatch,
            onSelectFoundBatch = viewModel::selectFoundBatch,
            onChooseBatchRow = viewModel::chooseBatchRow,
            onBatchManufacturingDateChange = viewModel::setBatchManufacturingDate,
            onBatchExpiryDateChange = viewModel::setBatchExpiryDate,
            onBatchQcNoChange = viewModel::setBatchQcNo,
            onCreateAnotherBatch = viewModel::createAnotherBatch,
            onCreateBatch = viewModel::createBatchFromSheet,
            onCloseBatchSheet = viewModel::closeBatchSheet,
        ),
    )
}

/**
 * Counting against a draft Purchase Receipt. Rows show the expected quantity (no prices); batch
 * tracked rows list the batches counted under them (added by scanning the number printed on the
 * material, found or created in ERPNext), and the two actions are "Save progress" (counts only)
 * and "Confirm receipt" (summary, then drops unreceived rows, submits, stock in).
 */
@Composable
fun ReceiveScreen(state: ReceiveUiState, onBack: () -> Unit, actions: ReceiveActions) {
    val receipt = state.receipt
    if (receipt != null && state.cameraActive && state.completed == null) {
        CameraScannerSheet(
            title = stringResource(R.string.camera_sheet_title),
            onDismiss = actions.onToggleCamera,
            onBarcode = actions.onCameraBarcode,
            scanning = !state.isBusy,
        )
    }
    state.pendingScan?.let { prompt ->
        ScanQuantityDialog(
            prompt = prompt,
            title = stringResource(R.string.scan_qty_title_receive),
            onQtyChange = actions.onPendingQtyChange,
            onIncrement = actions.onPendingIncrement,
            onDecrement = actions.onPendingDecrement,
            onAll = actions.onPendingAll,
            onConfirm = actions.onConfirmPending,
            onDismiss = actions.onCancelPending,
        )
    }
    state.confirmation?.let { confirmation ->
        SummaryDialog(confirmation, onConfirm = actions.onConfirmDifferences, onDismiss = actions.onCancelConfirmation)
    }
    if (state.requiredFields.isNotEmpty()) {
        RequiredFieldsDialog(
            doctype = stringResource(R.string.doctype_purchase_receipt),
            fields = state.requiredFields,
            answers = state.requiredFieldAnswers,
            linkOptions = state.linkOptions,
            onAnswerChange = actions.onRequiredFieldChange,
            onConfirm = actions.onConfirmRequiredFields,
            onDismiss = actions.onDismissRequiredFields,
        )
    }
    state.batchSheet?.let { sheet -> BatchSheet(sheet, state.lines, actions) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            WmsTopBar(title = receipt?.name ?: stringResource(R.string.receive_title), subtitle = receipt?.supplierName, onBack = onBack, actions = {
                if (receipt != null && state.completed == null && state.canReceive) {
                    IconButton(onClick = actions.onToggleCamera, modifier = Modifier.testTag("receive_camera_toggle")) {
                        Icon(
                            Icons.Outlined.CameraAlt,
                            contentDescription = stringResource(R.string.camera_sheet_title),
                            tint = if (state.cameraActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            })
        },
        bottomBar = {
            if (receipt != null && state.completed == null && state.canReceive) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                    Column(modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.receive_counted, Formatters.qty(state.totalQty), Formatters.qty(state.expectedQty)),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f).testTag("receive_counted"),
                            )
                            TextButton(onClick = actions.onReceiveAll, modifier = Modifier.testTag("receive_all")) { Text(stringResource(R.string.receive_all)) }
                            TextButton(onClick = actions.onClearAll) { Text(stringResource(R.string.common_clear)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { actions.onSubmit(true) }, enabled = state.canSubmit, modifier = Modifier.weight(1f).testTag("receive_save_progress")) {
                                Text(stringResource(R.string.receive_save_progress))
                            }
                            Button(onClick = { actions.onSubmit(false) }, enabled = state.canSubmit, modifier = Modifier.weight(1f).testTag("receive_submit")) {
                                if (state.isSubmitting) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                } else {
                                    Text(stringResource(R.string.receive_confirm))
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.isLoading) {
                item { LoadingState(modifier = Modifier.height(240.dp), message = stringResource(R.string.receive_loading)) }
                return@LazyColumn
            }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), onRetry = if (receipt == null) actions.onRetry else null, onDismiss = if (receipt != null) actions.onDismissMessage else null) }
            }
            if (receipt == null) {
                return@LazyColumn
            }
            state.completed?.let { outcome ->
                item { CompletedCard(outcome, state.message?.asString(), onBack) }
                return@LazyColumn
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.large, elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.receive_card), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            StatusChip(
                                receipt.workflowState?.takeIf { it.isNotBlank() } ?: receipt.status ?: stringResource(R.string.receive_stage_draft),
                                if (state.canReceive) WmsTheme.colors.warning else WmsTheme.colors.info,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LabelValue(stringResource(R.string.label_posting_date), Formatters.date(receipt.postingDate), Modifier.weight(1f))
                            LabelValue(stringResource(R.string.label_items), Formatters.int(receipt.items.size), Modifier.weight(1f))
                            LabelValue(stringResource(R.string.label_expected_qty), Formatters.qty(state.expectedQty), Modifier.weight(1f))
                        }
                        receipt.supplierDeliveryNote?.takeIf { it.isNotBlank() }?.let {
                            Text(stringResource(R.string.receive_supplier_note, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.canReceive) {
                            WarehousePicker(stringResource(R.string.receive_warehouse), state.warehouse, state.warehouses, actions.onWarehouseChange, modifier = Modifier.testTag("receive_warehouse"))
                            if (state.warehouse.isBlank()) {
                                Text(stringResource(R.string.receive_warehouse_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            state.message?.let { message -> item { InfoBanner(message.asString(), container = WmsTheme.colors.successContainer, content = MaterialTheme.colorScheme.onSurface) } }
            if (state.canReceive) {
                item { Text(stringResource(R.string.receive_scan_hint), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (state.lines.isEmpty()) {
                item { EmptyState(Icons.Outlined.Inventory2, stringResource(R.string.receive_no_items_title), stringResource(R.string.receive_no_items_message)) }
            }
            items(state.lines, key = { it.item.rowName }) { line ->
                ReceiveLineCard(line = line, editable = state.canReceive, actions = actions)
            }
        }
    }
}

/** Where the row goes: its warehouse, or what kind of non-stock row it is (a fixed asset, a service) since those take none. */
@Composable
private fun linePlace(item: PurchaseReceiptItem): String = when {
    item.isFixedAsset -> stringResource(R.string.receive_line_asset)
    !item.isStockItem -> stringResource(R.string.item_chip_non_stock)
    else -> item.warehouse ?: "-"
}

@Composable
private fun ReceiveLineCard(line: ReceiveLineState, editable: Boolean, actions: ReceiveActions) {
    val container = if (line.highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val rowName = line.item.rowName
    Card(colors = CardDefaults.cardColors(containerColor = container), shape = MaterialTheme.shapes.medium, elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), modifier = Modifier.testTag("receive_line_$rowName")) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(line.item.itemName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.receive_line_details, line.item.itemCode, Formatters.qty(line.item.qty), line.item.uom ?: "", linePlace(line.item)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (line.item.hasBatchNo) {
                line.batches.forEach { batch ->
                    BatchLineRow(line, batch, editable, actions)
                }
                if (line.batchMissing && editable) {
                    Text(stringResource(R.string.receive_batch_required), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (editable) {
                    OutlinedButton(onClick = { actions.onAddBatch(rowName) }, modifier = Modifier.testTag("receive_add_batch_$rowName")) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(if (line.batches.isEmpty()) R.string.receive_add_batch else R.string.receive_add_another_batch))
                    }
                }
            }
            if (!line.countsByBatch) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.receive_now), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    QtyStepper(
                        value = line.qtyText,
                        onValueChange = { actions.onSetQty(rowName, it) },
                        onIncrement = { actions.onIncrement(rowName) },
                        onDecrement = { actions.onDecrement(rowName) },
                        enabled = editable,
                    )
                }
            } else if (line.batches.size > 1) {
                Text(
                    stringResource(R.string.receive_batches_total, Formatters.qty(line.qty), Formatters.qty(line.item.qty)),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("receive_batches_total_$rowName"),
                )
            }
        }
    }
}

@Composable
private fun BatchLineRow(line: ReceiveLineState, batch: BatchLineState, editable: Boolean, actions: ReceiveActions) {
    val rowName = line.item.rowName
    Column(modifier = Modifier.fillMaxWidth().testTag("receive_batch_${rowName}_${batch.batchNo}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.receive_batch_line, batch.label), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (batch.isNew) StatusChip(stringResource(R.string.receive_batch_new), WmsTheme.colors.kpiTeal)
                }
                val details = listOfNotNull(
                    batch.batchNo.takeIf { it != batch.label },
                    batch.expiryDate?.let { stringResource(R.string.st_batch_expires, Formatters.date(it)) },
                ).joinToString(" · ")
                if (details.isNotEmpty()) {
                    Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            QtyStepper(
                value = batch.qtyText,
                onValueChange = { actions.onSetBatchQty(rowName, batch.batchNo, it) },
                onIncrement = { actions.onIncrementBatch(rowName, batch.batchNo) },
                onDecrement = { actions.onDecrementBatch(rowName, batch.batchNo) },
                enabled = editable,
            )
            if (editable) {
                IconButton(onClick = { actions.onRemoveBatch(rowName, batch.batchNo) }, modifier = Modifier.size(32.dp).testTag("receive_remove_batch_${rowName}_${batch.batchNo}")) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.receive_remove_batch), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Adds a batch under a batch tracked row: scan or type the number printed on the material, pick
 * the ERPNext batch when several carry that number, or create it with its dates when it is new.
 */
@Composable
private fun BatchSheet(sheet: BatchSheetState, lines: List<ReceiveLineState>, actions: ReceiveActions) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val line = lines.firstOrNull { it.item.rowName == sheet.rowName }
    ModalBottomSheet(onDismissRequest = actions.onCloseBatchSheet, sheetState = sheetState, modifier = Modifier.testTag("receive_batch_sheet")) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.receive_batch_sheet_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (line != null) {
                Text(line.item.itemName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(line.item.itemCode, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            sheet.error?.let { ErrorBanner(it.asString()) }
            if (sheet.rowName == null) {
                Text(stringResource(R.string.receive_batch_choose_row, sheet.code), style = MaterialTheme.typography.bodyMedium)
                sheet.candidates.mapNotNull { name -> lines.firstOrNull { it.item.rowName == name } }.forEach { candidate ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { actions.onChooseBatchRow(candidate.item.rowName) }.testTag("receive_batch_row_${candidate.item.rowName}"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(candidate.item.itemName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                stringResource(R.string.receive_line_details, candidate.item.itemCode, Formatters.qty(candidate.item.qty), candidate.item.uom ?: "", linePlace(candidate.item)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                return@Column
            }
            OutlinedTextField(
                value = sheet.code,
                onValueChange = actions.onBatchCodeChange,
                label = { Text(stringResource(R.string.receive_batch_number)) },
                placeholder = { Text(stringResource(R.string.receive_batch_number_hint)) },
                singleLine = true,
                enabled = !sheet.isBusy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { actions.onLookupBatch() }),
                trailingIcon = {
                    IconButton(onClick = actions.onLookupBatch, enabled = !sheet.isBusy && sheet.code.isNotBlank(), modifier = Modifier.testTag("receive_batch_lookup")) {
                        Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.receive_batch_lookup))
                    }
                },
                modifier = Modifier.fillMaxWidth().scannerAwareFocus().testTag("receive_batch_code"),
            )
            if (sheet.isBusy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.receive_batch_looking_up), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (sheet.matches.isNotEmpty()) {
                Text(stringResource(R.string.receive_batch_several, sheet.code), style = MaterialTheme.typography.bodyMedium)
                sheet.matches.forEach { batch ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { actions.onSelectFoundBatch(batch) }.testTag("receive_batch_match_${batch.name}"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(batch.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            val details = listOfNotNull(
                                batch.qcNo?.let { stringResource(R.string.receive_batch_qc, it) },
                                batch.expiryDate?.let { stringResource(R.string.st_batch_expires, Formatters.date(it)) },
                                batch.manufacturingDate?.let { stringResource(R.string.receive_batch_manufactured, Formatters.date(it)) },
                                if (batch.disabled) stringResource(R.string.scan_chip_disabled) else null,
                            ).joinToString(" · ")
                            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (sheet.matchesHaveQc) {
                    TextButton(onClick = actions.onCreateAnotherBatch, enabled = !sheet.isBusy, modifier = Modifier.testTag("receive_batch_another")) {
                        Text(stringResource(R.string.receive_batch_another_qc))
                    }
                }
            }
            if (sheet.isNew && line != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(stringResource(R.string.receive_batch_new_message, sheet.code, line.item.itemCode), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = sheet.qcNo,
                    onValueChange = actions.onBatchQcNoChange,
                    label = { Text(stringResource(R.string.receive_batch_qc_no)) },
                    supportingText = { Text(stringResource(R.string.receive_batch_qc_no_hint)) },
                    singleLine = true,
                    enabled = !sheet.isBusy,
                    modifier = Modifier.fillMaxWidth().testTag("receive_batch_qc_no"),
                )
                DateField(
                    label = stringResource(R.string.receive_batch_manufacturing_date),
                    value = sheet.manufacturingDate,
                    onValueChange = actions.onBatchManufacturingDateChange,
                    enabled = !sheet.isBusy,
                    modifier = Modifier.testTag("receive_batch_mfg_date"),
                )
                DateField(
                    label = stringResource(R.string.receive_batch_expiry_date),
                    value = sheet.expiryDate,
                    onValueChange = actions.onBatchExpiryDateChange,
                    enabled = !sheet.isBusy,
                    supporting = when {
                        !line.item.hasExpiryDate -> stringResource(R.string.receive_batch_expiry_optional)
                        line.item.shelfLifeDays > 0 -> stringResource(R.string.receive_batch_expiry_default, line.item.shelfLifeDays)
                        else -> stringResource(R.string.receive_batch_expiry_required)
                    },
                    modifier = Modifier.testTag("receive_batch_expiry_date"),
                )
                Button(
                    onClick = actions.onCreateBatch,
                    enabled = !sheet.isBusy && sheet.code.isNotBlank() && (!line.item.hasExpiryDate || sheet.expiryDate.isNotBlank()),
                    modifier = Modifier.fillMaxWidth().testTag("receive_batch_create"),
                ) { Text(stringResource(R.string.receive_batch_create)) }
            }
        }
    }
}

/** An ISO date field with a calendar picker; the helper line shows the date as the UI language writes it. */
@Composable
private fun DateField(label: String, value: String, onValueChange: (String) -> Unit, enabled: Boolean, modifier: Modifier = Modifier, supporting: String? = null) {
    var pickerOpen by remember { mutableStateOf(false) }
    if (pickerOpen) {
        val initial = runCatching { LocalDate.parse(value.trim()) }.getOrNull() ?: LocalDate.now()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis -> onValueChange(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    pickerOpen = false
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickerOpen = false }) { Text(stringResource(R.string.required_fields_cancel)) } },
        ) { DatePicker(state = pickerState) }
    }
    val parsed = runCatching { LocalDate.parse(value.trim()) }.getOrNull()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text("yyyy-MM-dd") },
        singleLine = true,
        enabled = enabled,
        isError = value.isNotBlank() && parsed == null,
        supportingText = {
            val formatted = parsed?.let { Formatters.date(it) }
            Text(listOfNotNull(formatted, supporting).joinToString(" · "))
        },
        trailingIcon = {
            IconButton(onClick = { pickerOpen = true }, enabled = enabled) { Icon(Icons.Outlined.DateRange, contentDescription = label) }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/** The summary before submitting: every row with its batches; rows counted 0 are dropped, others take the counted quantity. */
@Composable
private fun SummaryDialog(confirmation: ReceiveConfirmation, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val differs = confirmation.differences.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("receive_differences_dialog"),
        title = { Text(stringResource(if (differs) R.string.receive_confirm_title else R.string.receive_summary_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(if (differs) R.string.receive_confirm_message else R.string.receive_summary_message), style = MaterialTheme.typography.bodyMedium)
                confirmation.summary.forEach { line ->
                    Text(
                        stringResource(R.string.receive_summary_row, line.itemCode, Formatters.qty(line.counted), line.uom ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    line.batches.forEach { batch ->
                        Text(
                            "   " + stringResource(R.string.receive_summary_batch, batch.label, Formatters.qty(batch.qty)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (differs) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    confirmation.differences.take(MAX_DIFFERENCES).forEach { diff -> Text(diff.describe(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (confirmation.differences.size > MAX_DIFFERENCES) {
                        Text(stringResource(R.string.scan_more_items, confirmation.differences.size - MAX_DIFFERENCES), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, modifier = Modifier.testTag("receive_differences_confirm")) { Text(stringResource(R.string.receive_confirm_submit)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.required_fields_cancel)) } },
    )
}

@Composable
private fun ReceiptDifference.describe(): String =
    if (counted <= 1e-9) {
        stringResource(R.string.receive_confirm_removed, itemCode)
    } else {
        stringResource(R.string.receive_confirm_row, itemCode, Formatters.qty(expected), Formatters.qty(counted))
    }

@Composable
private fun CompletedCard(outcome: ReceiveResult, message: String?, onBack: () -> Unit) {
    val colors = WmsTheme.colors
    Card(colors = CardDefaults.cardColors(containerColor = if (outcome.submitted) colors.successContainer else MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = if (outcome.submitted) colors.success else colors.info, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(8.dp))
            Text(outcome.receipt.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("receive_completed"))
            Text(
                message ?: outcome.receipt.workflowState ?: outcome.receipt.status.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (outcome.removedRows.isNotEmpty()) {
                Text(stringResource(R.string.receive_removed_rows, outcome.removedRows.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val changed = outcome.differences.count { it.counted > 1e-9 }
            if (changed > 0) {
                Text(stringResource(R.string.receive_differences, changed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val batched = outcome.receipt.items.filter { !it.batchNo.isNullOrBlank() }
            if (outcome.submitted && batched.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                batched.forEach { row ->
                    Text(
                        stringResource(R.string.receive_summary_row, row.itemCode, Formatters.qty(row.qty), row.uom ?: "") + " · " + (row.batchLabel ?: row.batchNo.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onBack, modifier = Modifier.testTag("receive_done")) { Text(stringResource(R.string.common_done)) }
        }
    }
}

private const val MAX_DIFFERENCES = 8
