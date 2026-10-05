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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

@Composable
fun ReceiveRoute(onBack: () -> Unit, viewModel: ReceiveViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ScannerListener(enabled = state.completed == null && !state.isBusy) { viewModel.onScanned(it) }
    ReceiveScreen(
        state = state,
        onBack = onBack,
        onSetQty = viewModel::setQty,
        onSetBatch = viewModel::setBatch,
        onIncrement = viewModel::increment,
        onDecrement = viewModel::decrement,
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
    )
}

/**
 * Counting against a draft Purchase Receipt. Rows show the expected quantity (no prices), batch
 * tracked rows take their batch from a scanned label or the batch field, and the two actions are
 * "Save progress" (counts only) and "Confirm receipt" (drops unreceived rows, submits, stock in).
 */
@Composable
fun ReceiveScreen(
    state: ReceiveUiState,
    onBack: () -> Unit,
    onSetQty: (String, String) -> Unit,
    onSetBatch: (String, String) -> Unit,
    onIncrement: (String) -> Unit,
    onDecrement: (String) -> Unit,
    onReceiveAll: () -> Unit,
    onClearAll: () -> Unit,
    onWarehouseChange: (String) -> Unit,
    onSubmit: (asDraft: Boolean) -> Unit,
    onConfirmDifferences: () -> Unit = {},
    onCancelConfirmation: () -> Unit = {},
    onDismissMessage: () -> Unit,
    onRetry: () -> Unit,
    onRequiredFieldChange: (String, String) -> Unit = { _, _ -> },
    onConfirmRequiredFields: () -> Unit = {},
    onDismissRequiredFields: () -> Unit = {},
    onToggleCamera: () -> Unit = {},
    onCameraBarcode: (String, String?) -> Unit = { _, _ -> },
    onPendingQtyChange: (String) -> Unit = {},
    onPendingIncrement: () -> Unit = {},
    onPendingDecrement: () -> Unit = {},
    onPendingAll: () -> Unit = {},
    onConfirmPending: () -> Unit = {},
    onCancelPending: () -> Unit = {},
) {
    val receipt = state.receipt
    if (receipt != null && state.cameraActive && state.completed == null) {
        CameraScannerSheet(
            title = stringResource(R.string.camera_sheet_title),
            onDismiss = onToggleCamera,
            onBarcode = onCameraBarcode,
            scanning = !state.isBusy,
        )
    }
    state.pendingScan?.let { prompt ->
        ScanQuantityDialog(
            prompt = prompt,
            title = stringResource(R.string.scan_qty_title_receive),
            onQtyChange = onPendingQtyChange,
            onIncrement = onPendingIncrement,
            onDecrement = onPendingDecrement,
            onAll = onPendingAll,
            onConfirm = onConfirmPending,
            onDismiss = onCancelPending,
        )
    }
    state.confirmation?.let { confirmation ->
        DifferencesDialog(confirmation.differences, onConfirm = onConfirmDifferences, onDismiss = onCancelConfirmation)
    }
    if (state.requiredFields.isNotEmpty()) {
        RequiredFieldsDialog(
            doctype = stringResource(R.string.doctype_purchase_receipt),
            fields = state.requiredFields,
            answers = state.requiredFieldAnswers,
            linkOptions = state.linkOptions,
            onAnswerChange = onRequiredFieldChange,
            onConfirm = onConfirmRequiredFields,
            onDismiss = onDismissRequiredFields,
        )
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            WmsTopBar(title = receipt?.name ?: stringResource(R.string.receive_title), subtitle = receipt?.supplierName, onBack = onBack, actions = {
                if (receipt != null && state.completed == null && state.canReceive) {
                    IconButton(onClick = onToggleCamera, modifier = Modifier.testTag("receive_camera_toggle")) {
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
                            TextButton(onClick = onReceiveAll, modifier = Modifier.testTag("receive_all")) { Text(stringResource(R.string.receive_all)) }
                            TextButton(onClick = onClearAll) { Text(stringResource(R.string.common_clear)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { onSubmit(true) }, enabled = state.canSubmit, modifier = Modifier.weight(1f).testTag("receive_save_progress")) {
                                Text(stringResource(R.string.receive_save_progress))
                            }
                            Button(onClick = { onSubmit(false) }, enabled = state.canSubmit, modifier = Modifier.weight(1f).testTag("receive_submit")) {
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
                item { ErrorBanner(error.asString(), onRetry = if (receipt == null) onRetry else null, onDismiss = if (receipt != null) onDismissMessage else null) }
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
                            WarehousePicker(stringResource(R.string.receive_warehouse), state.warehouse, state.warehouses, onWarehouseChange, modifier = Modifier.testTag("receive_warehouse"))
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
                ReceiveLineCard(line = line, editable = state.canReceive, onSetQty = onSetQty, onSetBatch = onSetBatch, onIncrement = onIncrement, onDecrement = onDecrement)
            }
        }
    }
}

@Composable
private fun ReceiveLineCard(
    line: ReceiveLineState,
    editable: Boolean,
    onSetQty: (String, String) -> Unit,
    onSetBatch: (String, String) -> Unit,
    onIncrement: (String) -> Unit,
    onDecrement: (String) -> Unit,
) {
    val container = if (line.highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Card(colors = CardDefaults.cardColors(containerColor = container), shape = MaterialTheme.shapes.medium, elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), modifier = Modifier.testTag("receive_line_${line.item.rowName}")) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(line.item.itemName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.receive_line_details, line.item.itemCode, Formatters.qty(line.item.qty), line.item.uom ?: "", line.item.warehouse ?: "-"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (line.item.hasBatchNo) {
                OutlinedTextField(
                    value = line.batchNo,
                    onValueChange = { onSetBatch(line.item.rowName, it) },
                    label = { Text(stringResource(R.string.receive_batch)) },
                    placeholder = { Text(stringResource(R.string.receive_batch_required)) },
                    singleLine = true,
                    enabled = editable,
                    isError = line.batchMissing && line.qty > 0,
                    modifier = Modifier.fillMaxWidth().scannerAwareFocus().testTag("receive_batch_${line.item.rowName}"),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.receive_now), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                QtyStepper(
                    value = line.qtyText,
                    onValueChange = { onSetQty(line.item.rowName, it) },
                    onIncrement = { onIncrement(line.item.rowName) },
                    onDecrement = { onDecrement(line.item.rowName) },
                    enabled = editable,
                )
            }
        }
    }
}

/** Confirms counts that differ from the draft: rows counted 0 are dropped, others take the counted quantity. */
@Composable
private fun DifferencesDialog(differences: List<ReceiptDifference>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("receive_differences_dialog"),
        title = { Text(stringResource(R.string.receive_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.receive_confirm_message), style = MaterialTheme.typography.bodyMedium)
                differences.take(MAX_DIFFERENCES).forEach { diff -> Text(diff.describe(), style = MaterialTheme.typography.bodySmall) }
                if (differences.size > MAX_DIFFERENCES) {
                    Text(stringResource(R.string.scan_more_items, differences.size - MAX_DIFFERENCES), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Spacer(Modifier.height(12.dp))
            Button(onClick = onBack, modifier = Modifier.testTag("receive_done")) { Text(stringResource(R.string.common_done)) }
        }
    }
}

private const val MAX_DIFFERENCES = 8
