package com.wmserp.app.presentation.scan

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wmserp.app.core.util.Formatters
import com.wmserp.app.domain.model.Item
import com.wmserp.app.domain.model.PurchaseOrder
import com.wmserp.app.domain.model.ScanLookup
import com.wmserp.app.domain.model.ScanSource
import com.wmserp.app.domain.model.ScanTarget
import com.wmserp.app.domain.model.ScannedCode
import com.wmserp.app.domain.model.StockLevel
import com.wmserp.app.domain.model.Warehouse
import com.wmserp.app.presentation.components.ErrorBanner
import com.wmserp.app.presentation.components.InfoBanner
import com.wmserp.app.presentation.components.LabelValue
import com.wmserp.app.presentation.components.ScannerListener
import com.wmserp.app.presentation.components.SectionHeader
import com.wmserp.app.presentation.components.StatusChip
import com.wmserp.app.presentation.components.WarehousePicker
import com.wmserp.app.presentation.components.scannerAwareFocus
import com.wmserp.app.presentation.theme.WmsTheme

@Composable
fun ScanRoute(onReceivePurchaseOrder: (String) -> Unit, viewModel: ScanViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ScannerListener(enabled = !state.transfer.visible) { viewModel.onScanned(it) }
    ScanScreen(
        state = state,
        onTargetChange = viewModel::setTarget,
        onManualInputChange = viewModel::onManualInputChange,
        onManualSubmit = viewModel::submitManual,
        onToggleCamera = viewModel::toggleCamera,
        onCameraBarcode = { value, symbology -> viewModel.onScanned(ScannedCode(value, ScanSource.CAMERA, symbology)) },
        onClearResult = viewModel::clearResult,
        onDismissMessage = viewModel::dismissMessage,
        onOpenTransfer = viewModel::openTransfer,
        onCloseTransfer = viewModel::closeTransfer,
        onTransferFromChange = viewModel::onTransferFromChange,
        onTransferToChange = viewModel::onTransferToChange,
        onTransferQtyChange = viewModel::onTransferQtyChange,
        onSubmitTransfer = viewModel::submitTransfer,
        onReceivePurchaseOrder = onReceivePurchaseOrder,
    )
}

@Composable
fun ScanScreen(
    state: ScanUiState,
    onTargetChange: (ScanTarget) -> Unit,
    onManualInputChange: (String) -> Unit,
    onManualSubmit: () -> Unit,
    onToggleCamera: () -> Unit,
    onCameraBarcode: (String, String?) -> Unit,
    onClearResult: () -> Unit,
    onDismissMessage: () -> Unit,
    onOpenTransfer: () -> Unit,
    onCloseTransfer: () -> Unit,
    onTransferFromChange: (String) -> Unit,
    onTransferToChange: (String) -> Unit,
    onTransferQtyChange: (String) -> Unit,
    onSubmitTransfer: () -> Unit,
    onReceivePurchaseOrder: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Scan", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    if (state.cameraActive) "Using the camera scanner" else if (state.hasHardwareScanner) "Hardware scanner ready — pull the trigger" else "Keyboard scanner input",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onToggleCamera, modifier = Modifier.testTag("scan_toggle_camera")) {
                Icon(
                    if (state.cameraActive) Icons.Outlined.Keyboard else Icons.Outlined.CameraAlt,
                    contentDescription = if (state.cameraActive) "Use hardware scanner" else "Use camera",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ScanTarget.entries.forEachIndexed { index, target ->
                SegmentedButton(
                    selected = state.target == target,
                    onClick = { onTargetChange(target) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ScanTarget.entries.size),
                    modifier = Modifier.testTag("scan_target_${target.name}"),
                    label = { Text(target.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }

        Viewfinder(state = state, onCameraBarcode = onCameraBarcode)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.manualInput,
                onValueChange = onManualInputChange,
                modifier = Modifier
                    .weight(1f)
                    .scannerAwareFocus()
                    .testTag("scan_manual_input"),
                placeholder = { Text("Type or scan a code") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onManualSubmit() }),
            )
            Button(
                onClick = onManualSubmit,
                enabled = state.manualInput.isNotBlank() && !state.isLookingUp,
                modifier = Modifier.testTag("scan_manual_submit"),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Look up") }
        }

        if (state.isLookingUp) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        state.message?.let { InfoBanner(it, container = WmsTheme.colors.successContainer, content = MaterialTheme.colorScheme.onSurface) }
        state.error?.let { ErrorBanner(it, onDismiss = onDismissMessage, modifier = Modifier.testTag("scan_error")) }

        state.result?.let { lookup ->
            Box(modifier = Modifier.testTag("scan_result")) {
                when (lookup) {
                    is ScanLookup.ItemFound -> ItemResultCard(lookup.item, lookup.stock, onClear = onClearResult, onTransfer = onOpenTransfer)
                    is ScanLookup.WarehouseFound -> WarehouseResultCard(lookup.warehouse, lookup.stock, onClear = onClearResult)
                    is ScanLookup.PurchaseOrderFound -> PurchaseOrderResultCard(lookup.purchaseOrder, onClear = onClearResult, onReceive = { onReceivePurchaseOrder(lookup.purchaseOrder.name) })
                    is ScanLookup.NotFound -> NotFoundCard(lookup.code, lookup.target, onClear = onClearResult)
                }
            }
        }

        if (state.history.isNotEmpty()) {
            SectionHeader("Recent scans")
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    state.history.forEachIndexed { index, entry ->
                        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(entry.code, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(entry.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            StatusChip(entry.target.label, if (entry.found) WmsTheme.colors.success else WmsTheme.colors.danger)
                        }
                        if (index < state.history.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (state.transfer.visible) {
        val item = (state.result as? ScanLookup.ItemFound)?.item
        TransferSheet(
            item = item,
            form = state.transfer,
            onDismiss = onCloseTransfer,
            onFromChange = onTransferFromChange,
            onToChange = onTransferToChange,
            onQtyChange = onTransferQtyChange,
            onSubmit = onSubmitTransfer,
        )
    }
}

@Composable
private fun Viewfinder(state: ScanUiState, onCameraBarcode: (String, String?) -> Unit) {
    val colors = WmsTheme.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF15152A)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (state.cameraActive) {
                CameraScannerPane(modifier = Modifier.fillMaxSize(), onBarcode = onCameraBarcode)
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(72.dp))
                }
            }
            ScanFrameOverlay(accent = colors.gradientEnd, animate = !state.isLookingUp)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.isLookingUp) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        state.statusText,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("scan_status"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanFrameOverlay(accent: Color, animate: Boolean) {
    val transition = rememberInfiniteTransition(label = "scanline")
    val progress by transition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.88f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Reverse),
        label = "scanline_progress",
    )
    Canvas(modifier = Modifier.fillMaxSize().padding(28.dp)) {
        val w = size.width
        val h = size.height
        val len = minOf(w, h) * 0.14f
        val stroke = 5.dp.toPx()
        val corners = listOf(
            Offset(0f, 0f) to listOf(Offset(len, 0f), Offset(0f, len)),
            Offset(w, 0f) to listOf(Offset(w - len, 0f), Offset(w, len)),
            Offset(0f, h) to listOf(Offset(len, h), Offset(0f, h - len)),
            Offset(w, h) to listOf(Offset(w - len, h), Offset(w, h - len)),
        )
        corners.forEach { (origin, ends) ->
            ends.forEach { end -> drawLine(Color.White, origin, end, strokeWidth = stroke, cap = StrokeCap.Round) }
        }
        if (animate) {
            val y = h * progress
            drawLine(accent.copy(alpha = 0.9f), Offset(len * 0.6f, y), Offset(w - len * 0.6f, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun ResultCard(title: String, subtitle: String?, chip: String?, chipColor: Color, onClear: () -> Unit, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (chip != null) StatusChip(chip, chipColor)
                IconButton(onClick = onClear) { Icon(Icons.Outlined.Close, contentDescription = "Clear result") }
            }
            content()
        }
    }
}

@Composable
private fun ItemResultCard(item: Item, stock: List<StockLevel>, onClear: () -> Unit, onTransfer: () -> Unit) {
    val total = stock.sumOf { it.actualQty }
    ResultCard(title = item.name, subtitle = item.code, chip = if (item.disabled) "Disabled" else "Item", chipColor = if (item.disabled) WmsTheme.colors.danger else WmsTheme.colors.kpiPurple, onClear = onClear) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabelValue("Group", item.group ?: "-", Modifier.weight(1f))
            LabelValue("UOM", item.stockUom ?: "-", Modifier.weight(1f))
            LabelValue("Total stock", Formatters.qty(total), Modifier.weight(1f))
        }
        if (item.barcodes.isNotEmpty()) LabelValue("Barcodes", item.barcodes.joinToString(", "))
        if (stock.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            stock.take(6).forEach { level ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(level.warehouse, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        Formatters.qty(level.actualQty) + (if (level.reservedQty > 0) " (${Formatters.qty(level.reservedQty)} reserved)" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        } else {
            Text("No stock recorded for this item.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalButton(onClick = onTransfer, modifier = Modifier.fillMaxWidth().testTag("scan_move_stock")) {
            Icon(Icons.Outlined.SwapHoriz, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Move stock")
        }
    }
}

@Composable
private fun WarehouseResultCard(warehouse: Warehouse, stock: List<StockLevel>, onClear: () -> Unit) {
    ResultCard(title = warehouse.warehouseName, subtitle = warehouse.name, chip = warehouse.warehouseType ?: "Warehouse", chipColor = WmsTheme.colors.kpiBlue, onClear = onClear) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabelValue("Company", warehouse.company ?: "-", Modifier.weight(1f))
            LabelValue("Items in stock", stock.size.toString(), Modifier.weight(1f))
            LabelValue("Total qty", Formatters.qty(stock.sumOf { it.actualQty }), Modifier.weight(1f))
        }
        if (stock.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            stock.take(8).forEach { level ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(level.itemName ?: level.itemCode, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Formatters.qty(level.actualQty), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun PurchaseOrderResultCard(po: PurchaseOrder, onClear: () -> Unit, onReceive: () -> Unit) {
    ResultCard(title = po.name, subtitle = po.supplierName, chip = po.status, chipColor = WmsTheme.colors.warning, onClear = onClear) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabelValue("Ordered", Formatters.date(po.transactionDate), Modifier.weight(1f))
            LabelValue("Expected", Formatters.date(po.scheduleDate), Modifier.weight(1f))
            LabelValue("Total", Formatters.money(po.grandTotal, po.currency), Modifier.weight(1f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        po.items.take(6).forEach { line ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(line.itemName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${Formatters.qty(line.receivedQty)} / ${Formatters.qty(line.qty)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        if (po.items.size > 6) Text("+${po.items.size - 6} more items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onReceive, enabled = !po.isFullyReceived, modifier = Modifier.fillMaxWidth().testTag("scan_receive_po")) {
            Text(if (po.isFullyReceived) "Fully received" else "Receive items")
        }
    }
}

@Composable
private fun NotFoundCard(code: String, target: ScanTarget, onClear: () -> Unit) {
    ResultCard(title = "No ${target.label.lowercase()} found", subtitle = code, chip = "Not found", chipColor = WmsTheme.colors.danger, onClear = onClear) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.SearchOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                "Check that the ${target.label.lowercase()} exists in ERPNext and that the barcode is linked to it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransferSheet(
    item: Item?,
    form: TransferFormState,
    onDismiss: () -> Unit,
    onFromChange: (String) -> Unit,
    onToChange: (String) -> Unit,
    onQtyChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Move stock", style = MaterialTheme.typography.titleLarge)
            Text(item?.let { "${it.name} (${it.code})" } ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            WarehousePicker("From warehouse", form.fromWarehouse, form.warehouses, onFromChange)
            WarehousePicker("To warehouse", form.toWarehouse, form.warehouses, onToChange)
            OutlinedTextField(
                value = form.qtyText,
                onValueChange = onQtyChange,
                label = { Text("Quantity") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().scannerAwareFocus(),
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
            )
            form.error?.let { ErrorBanner(it) }
            Button(onClick = onSubmit, enabled = form.canSubmit, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                if (form.submitting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Submit Material Transfer")
                }
            }
        }
    }
}
