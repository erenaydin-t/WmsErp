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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wmserp.app.core.util.Formatters
import com.wmserp.app.presentation.components.EmptyState
import com.wmserp.app.presentation.components.ErrorBanner
import com.wmserp.app.presentation.components.InfoBanner
import com.wmserp.app.presentation.components.LabelValue
import com.wmserp.app.presentation.components.LoadingState
import com.wmserp.app.presentation.components.QtyStepper
import com.wmserp.app.presentation.components.ScannerListener
import com.wmserp.app.presentation.components.StatusChip
import com.wmserp.app.presentation.components.WarehousePicker
import com.wmserp.app.presentation.components.WmsTopBar
import com.wmserp.app.presentation.theme.WmsTheme

@Composable
fun ReceiveRoute(onBack: () -> Unit, viewModel: ReceiveViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ScannerListener(enabled = state.completed == null) { viewModel.onScanned(it) }
    ReceiveScreen(
        state = state,
        onBack = onBack,
        onSetQty = viewModel::setQty,
        onIncrement = viewModel::increment,
        onDecrement = viewModel::decrement,
        onReceiveAll = viewModel::receiveAll,
        onClearAll = viewModel::clearAll,
        onWarehouseChange = viewModel::setWarehouse,
        onSubmit = viewModel::submit,
        onDismissMessage = viewModel::dismissMessage,
        onRetry = viewModel::load,
    )
}

@Composable
fun ReceiveScreen(
    state: ReceiveUiState,
    onBack: () -> Unit,
    onSetQty: (String, String) -> Unit,
    onIncrement: (String) -> Unit,
    onDecrement: (String) -> Unit,
    onReceiveAll: () -> Unit,
    onClearAll: () -> Unit,
    onWarehouseChange: (String) -> Unit,
    onSubmit: (asDraft: Boolean) -> Unit,
    onDismissMessage: () -> Unit,
    onRetry: () -> Unit,
) {
    val po = state.purchaseOrder
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { WmsTopBar(title = po?.name ?: "Receive", subtitle = po?.supplierName, onBack = onBack) },
        bottomBar = {
            if (po != null && state.completed == null) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                    Column(modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Counted: ${Formatters.qty(state.totalQty)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = onReceiveAll, modifier = Modifier.testTag("receive_all")) { Text("Receive all") }
                            TextButton(onClick = onClearAll) { Text("Clear") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { onSubmit(true) }, enabled = state.canSubmit, modifier = Modifier.weight(1f)) { Text("Save draft") }
                            Button(onClick = { onSubmit(false) }, enabled = state.canSubmit, modifier = Modifier.weight(1f).testTag("receive_submit")) {
                                if (state.isSubmitting) {
                                    CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                } else {
                                    Text("Submit receipt")
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
                item { LoadingState(modifier = Modifier.height(240.dp), message = "Loading purchase order...") }
                return@LazyColumn
            }
            state.error?.let { item { ErrorBanner(it, onRetry = if (po == null) onRetry else null, onDismiss = if (po != null) onDismissMessage else null) } }
            if (po == null) {
                return@LazyColumn
            }
            state.completed?.let { receipt ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = WmsTheme.colors.successContainer), shape = MaterialTheme.shapes.large) {
                        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            androidx.compose.material3.Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = WmsTheme.colors.success, modifier = Modifier.height(40.dp).width(40.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(receipt.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("receive_completed"))
                            Text(state.message ?: receipt.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onBack) { Text("Done") }
                        }
                    }
                }
                return@LazyColumn
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.large, elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Purchase order", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            StatusChip(po.status, WmsTheme.colors.warning)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LabelValue("Expected", Formatters.date(po.scheduleDate), Modifier.weight(1f))
                            LabelValue("Received", Formatters.percent(po.perReceived), Modifier.weight(1f))
                            LabelValue("Total", Formatters.money(po.grandTotal, po.currency), Modifier.weight(1f))
                        }
                        WarehousePicker("Receive into warehouse", state.warehouse, state.warehouses, onWarehouseChange, modifier = Modifier.testTag("receive_warehouse"))
                    }
                }
            }
            state.message?.let { item { InfoBanner(it, container = WmsTheme.colors.successContainer, content = MaterialTheme.colorScheme.onSurface) } }
            item { Text("Scan items or enter counted quantities", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.lines.isEmpty()) {
                item { EmptyState(Icons.Outlined.CheckCircle, "No items", "This purchase order has no item rows.") }
            }
            items(state.lines, key = { it.item.rowName }) { line ->
                val container = if (line.highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                Card(colors = CardDefaults.cardColors(containerColor = container), shape = MaterialTheme.shapes.medium, elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), modifier = Modifier.testTag("receive_line_${line.item.rowName}")) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(line.item.itemName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${line.item.itemCode} · ordered ${Formatters.qty(line.item.qty)} · received ${Formatters.qty(line.item.receivedQty)} · pending ${Formatters.qty(line.item.pendingQty)} ${line.item.uom ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Receiving now", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
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
