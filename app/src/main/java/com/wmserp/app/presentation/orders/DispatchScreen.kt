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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.wmserp.app.presentation.theme.WmsTheme

@Composable
fun DispatchRoute(onBack: () -> Unit, viewModel: DispatchViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ScannerListener(enabled = state.completed == null) { viewModel.onScanned(it) }
    DispatchScreen(
        state = state,
        onBack = onBack,
        onSetQty = viewModel::setQty,
        onIncrement = viewModel::increment,
        onDecrement = viewModel::decrement,
        onDispatchAll = viewModel::dispatchAll,
        onClearAll = viewModel::clearAll,
        onWarehouseChange = viewModel::setWarehouse,
        onSubmit = viewModel::submit,
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

@Composable
fun DispatchScreen(
    state: DispatchUiState,
    onBack: () -> Unit,
    onSetQty: (String, String) -> Unit,
    onIncrement: (String) -> Unit,
    onDecrement: (String) -> Unit,
    onDispatchAll: () -> Unit,
    onClearAll: () -> Unit,
    onWarehouseChange: (String) -> Unit,
    onSubmit: (asDraft: Boolean) -> Unit,
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
    val so = state.salesOrder
    if (so != null && state.cameraActive && state.completed == null) {
        CameraScannerSheet(
            title = stringResource(R.string.camera_sheet_title),
            onDismiss = onToggleCamera,
            onBarcode = onCameraBarcode,
            scanning = state.pendingScan == null,
        )
    }
    state.pendingScan?.let { prompt ->
        ScanQuantityDialog(
            prompt = prompt,
            title = stringResource(R.string.scan_qty_title_pick),
            onQtyChange = onPendingQtyChange,
            onIncrement = onPendingIncrement,
            onDecrement = onPendingDecrement,
            onAll = onPendingAll,
            onConfirm = onConfirmPending,
            onDismiss = onCancelPending,
        )
    }
    if (state.requiredFields.isNotEmpty()) {
        RequiredFieldsDialog(
            doctype = stringResource(R.string.doctype_delivery_note),
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
        topBar = { WmsTopBar(title = so?.name ?: stringResource(R.string.dispatch_title), subtitle = so?.customerName, onBack = onBack, actions = {
            if (so != null && state.completed == null) {
                IconButton(onClick = onToggleCamera, modifier = Modifier.testTag("dispatch_camera_toggle")) {
                    Icon(
                        Icons.Outlined.CameraAlt,
                        contentDescription = stringResource(R.string.camera_sheet_title),
                        tint = if (state.cameraActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }) },
        bottomBar = {
            if (so != null && state.completed == null) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                    Column(modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.dispatch_picked, Formatters.qty(state.totalQty)), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = onDispatchAll, modifier = Modifier.testTag("dispatch_all")) { Text(stringResource(R.string.dispatch_all)) }
                            TextButton(onClick = onClearAll) { Text(stringResource(R.string.common_clear)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { onSubmit(true) }, enabled = state.canSubmit, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_save_draft)) }
                            Button(onClick = { onSubmit(false) }, enabled = state.canSubmit, modifier = Modifier.weight(1f).testTag("dispatch_submit")) {
                                if (state.isSubmitting) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                } else {
                                    Text(stringResource(R.string.dispatch_submit))
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
                item { LoadingState(modifier = Modifier.height(240.dp), message = stringResource(R.string.dispatch_loading)) }
                return@LazyColumn
            }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), onRetry = if (so == null) onRetry else null, onDismiss = if (so != null) onDismissMessage else null) }
            }
            if (so == null) {
                return@LazyColumn
            }
            state.completed?.let { note ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = WmsTheme.colors.successContainer), shape = MaterialTheme.shapes.large) {
                        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = WmsTheme.colors.success, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(note.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("dispatch_completed"))
                            Text(state.message?.asString() ?: note.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onBack) { Text(stringResource(R.string.common_done)) }
                        }
                    }
                }
                return@LazyColumn
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.large, elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.dispatch_so_card), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            StatusChip(so.status, WmsTheme.colors.info)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LabelValue(stringResource(R.string.label_deliver_by), Formatters.date(so.deliveryDate), Modifier.weight(1f))
                            LabelValue(stringResource(R.string.label_delivered), Formatters.percent(so.perDelivered), Modifier.weight(1f))
                            LabelValue(stringResource(R.string.label_total), Formatters.money(so.grandTotal, so.currency), Modifier.weight(1f))
                        }
                        WarehousePicker(stringResource(R.string.dispatch_warehouse), state.warehouse, state.warehouses, onWarehouseChange, modifier = Modifier.testTag("dispatch_warehouse"))
                    }
                }
            }
            state.message?.let { message -> item { InfoBanner(message.asString(), container = WmsTheme.colors.successContainer, content = MaterialTheme.colorScheme.onSurface) } }
            item { Text(stringResource(R.string.dispatch_scan_hint), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.lines.isEmpty()) {
                item { EmptyState(Icons.Outlined.CheckCircle, stringResource(R.string.receive_no_items_title), stringResource(R.string.dispatch_no_items_message)) }
            }
            items(state.lines, key = { it.item.rowName }) { line ->
                val container = if (line.highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
                Card(colors = CardDefaults.cardColors(containerColor = container), shape = MaterialTheme.shapes.medium, elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), modifier = Modifier.testTag("dispatch_line_${line.item.rowName}")) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(line.item.itemName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(
                                R.string.dispatch_line_details,
                                line.item.itemCode,
                                Formatters.qty(line.item.qty),
                                Formatters.qty(line.item.deliveredQty),
                                Formatters.qty(line.item.pendingQty),
                                line.item.uom ?: "",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.dispatch_now), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            QtyStepper(
                                value = line.qtyText,
                                onValueChange = { onSetQty(line.item.rowName, it) },
                                onIncrement = { onIncrement(line.item.rowName) },
                                onDecrement = { onDecrement(line.item.rowName) },
                                enabled = line.item.pendingQty > 0,
                            )
                        }
                    }
                }
            }
        }
    }
}
