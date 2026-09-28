package com.wmserp.app.presentation.picking

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wmserp.app.R
import com.wmserp.app.core.util.Formatters
import com.wmserp.app.domain.model.PickList
import com.wmserp.app.domain.model.PickListItem
import com.wmserp.app.domain.model.PickRowStatus
import com.wmserp.app.domain.model.PickingStatus
import com.wmserp.app.presentation.common.asString
import com.wmserp.app.presentation.common.createLabelRes
import com.wmserp.app.presentation.common.labelRes
import com.wmserp.app.presentation.components.ErrorBanner
import com.wmserp.app.presentation.components.InfoBanner
import com.wmserp.app.presentation.components.LabelValue
import com.wmserp.app.presentation.components.LoadingState
import com.wmserp.app.presentation.components.QtyStepper
import com.wmserp.app.presentation.components.ScannerListener
import com.wmserp.app.presentation.components.SectionHeader
import com.wmserp.app.presentation.components.StatusChip
import com.wmserp.app.presentation.components.WmsTopBar
import com.wmserp.app.presentation.components.scannerAwareFocus
import com.wmserp.app.presentation.theme.WmsTheme

@Composable
fun PickListRoute(onBack: () -> Unit, viewModel: PickListViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The hardware scanner only feeds this screen while the list is actively being picked.
    ScannerListener(enabled = state.status == PickingStatus.PICKING) { viewModel.onScanned(it) }
    PickListScreen(
        state = state,
        onBack = onBack,
        onStart = viewModel::startPicking,
        onSetQty = viewModel::setQty,
        onIncrement = viewModel::increment,
        onDecrement = viewModel::decrement,
        onSetBatch = viewModel::setBatch,
        onSave = viewModel::saveProgress,
        onComplete = viewModel::completePicking,
        onGenerate = viewModel::generateDocument,
        onDismissMessage = viewModel::dismissMessage,
        onRetry = viewModel::load,
    )
}

/**
 * Pick list detail. The content and the bottom action bar follow the WMS picking status:
 * Ready to Pick (details + "Start Picking"), Picking (editable rows + "Save progress" /
 * "Complete picking") and Picked (summary + context-aware "Create ..." document button).
 */
@Composable
fun PickListScreen(
    state: PickListUiState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onSetQty: (String, String) -> Unit,
    onIncrement: (String) -> Unit,
    onDecrement: (String) -> Unit,
    onSetBatch: (String, String) -> Unit,
    onSave: () -> Unit,
    onComplete: () -> Unit,
    onGenerate: () -> Unit,
    onDismissMessage: () -> Unit,
    onRetry: () -> Unit,
) {
    val pickList = state.pickList
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            WmsTopBar(
                title = pickList?.name ?: stringResource(R.string.pick_title),
                subtitle = pickList?.let { it.customerName ?: stringResource(it.purpose.labelRes()) },
                onBack = onBack,
            )
        },
        bottomBar = {
            if (pickList != null) {
                when (pickList.pickingStatus) {
                    PickingStatus.READY_TO_PICK -> StartBar(state, onStart)
                    PickingStatus.PICKING -> PickingBar(state, onSave, onComplete)
                    PickingStatus.PICKED -> GenerateBar(state, pickList, onGenerate, onBack)
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
                item { LoadingState(modifier = Modifier.height(240.dp), message = stringResource(R.string.pick_loading)) }
                return@LazyColumn
            }
            state.error?.let { error ->
                item {
                    ErrorBanner(
                        error.asString(),
                        onRetry = if (pickList == null) onRetry else null,
                        onDismiss = if (pickList != null) onDismissMessage else null,
                        modifier = Modifier.testTag("pick_error"),
                    )
                }
            }
            if (pickList == null) {
                return@LazyColumn
            }
            state.message?.let { message ->
                item { InfoBanner(message.asString(), container = WmsTheme.colors.successContainer, content = MaterialTheme.colorScheme.onSurface) }
            }
            when (pickList.pickingStatus) {
                PickingStatus.READY_TO_PICK -> readyContent(pickList, state.currentUser)
                PickingStatus.PICKING -> pickingContent(state, onSetQty, onIncrement, onDecrement, onSetBatch)
                PickingStatus.PICKED -> pickedContent(pickList)
            }
        }
    }
}

// ---- Ready to Pick -------------------------------------------------------------------------

private fun LazyListScope.readyContent(pickList: PickList, currentUser: String?) {
    item { PickListDetailsCard(pickList) }
    val assignedElsewhere = pickList.picker != null && pickList.picker != currentUser && currentUser !in pickList.assignedTo
    if (assignedElsewhere) {
        item {
            InfoBanner(
                stringResource(R.string.pick_assigned_to_other, pickList.picker.orEmpty()),
                container = WmsTheme.colors.warningContainer,
                content = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    item { Text(stringResource(R.string.pick_ready_hint), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    item { SectionHeader(stringResource(R.string.pick_lines_title)) }
    items(pickList.items, key = { it.rowName }) { item ->
        PickRowCard(item = item, qty = item.pickedQty, status = item.status, highlighted = false, modifier = Modifier.testTag("pick_line_${item.rowName}"))
    }
}

@Composable
private fun StartBar(state: PickListUiState, onStart: () -> Unit) {
    BottomActionBar {
        Button(onClick = onStart, enabled = state.canStart, modifier = Modifier.fillMaxWidth().testTag("pick_start")) {
            if (state.isStarting) ButtonProgress() else Text(stringResource(R.string.pick_start))
        }
    }
}

// ---- Picking -------------------------------------------------------------------------------

private fun LazyListScope.pickingContent(
    state: PickListUiState,
    onSetQty: (String, String) -> Unit,
    onIncrement: (String) -> Unit,
    onDecrement: (String) -> Unit,
    onSetBatch: (String, String) -> Unit,
) {
    item { Text(stringResource(R.string.pick_scan_hint), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    state.pickList?.pickingStartedBy?.let { user ->
        item { Text(stringResource(R.string.pick_started_by, user), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    items(state.lines, key = { it.item.rowName }) { line ->
        val rowName = line.item.rowName
        PickRowCard(
            item = line.item,
            qty = line.qty,
            status = line.status,
            highlighted = line.highlighted,
            modifier = Modifier.testTag("pick_line_$rowName"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.label_picked), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                QtyStepper(
                    value = line.qtyText,
                    onValueChange = { onSetQty(rowName, it) },
                    onIncrement = { onIncrement(rowName) },
                    onDecrement = { onDecrement(rowName) },
                    enabled = line.item.requiredQty > 0,
                    modifier = Modifier.testTag("pick_qty_$rowName"),
                )
            }
            if (line.item.hasBatchNo && line.item.batchNo == null) {
                OutlinedTextField(
                    value = line.batchText,
                    onValueChange = { onSetBatch(rowName, it) },
                    label = { Text(stringResource(R.string.label_batch)) },
                    placeholder = { Text(stringResource(R.string.pick_batch_placeholder)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .scannerAwareFocus()
                        .testTag("pick_batch_$rowName"),
                )
            }
        }
    }
}

@Composable
private fun PickingBar(state: PickListUiState, onSave: () -> Unit, onComplete: () -> Unit) {
    BottomActionBar {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.pick_progress_summary, Formatters.qty(state.totalPicked), Formatters.qty(state.totalRequired)),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).testTag("pick_progress"),
            )
            if (state.hasUnsavedChanges) {
                Text(stringResource(R.string.pick_unsaved), style = MaterialTheme.typography.labelSmall, color = WmsTheme.colors.warning)
            }
        }
        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onSave, enabled = state.canSave, modifier = Modifier.weight(1f).testTag("pick_save")) {
                if (state.isSaving) ButtonProgress(MaterialTheme.colorScheme.primary) else Text(stringResource(R.string.pick_save_progress))
            }
            Button(onClick = onComplete, enabled = state.canComplete, modifier = Modifier.weight(1f).testTag("pick_complete")) {
                if (state.isCompleting) ButtonProgress() else Text(stringResource(R.string.pick_complete))
            }
        }
    }
}

// ---- Picked --------------------------------------------------------------------------------

private fun LazyListScope.pickedContent(pickList: PickList) {
    item {
        Card(colors = CardDefaults.cardColors(containerColor = WmsTheme.colors.successContainer), shape = MaterialTheme.shapes.large) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = WmsTheme.colors.success, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.pick_picked_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("pick_picked_title"))
                Text(
                    stringResource(R.string.pick_picked_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                pickList.pickingCompletedBy?.let { user ->
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.pick_completed_by, user), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    pickList.generatedDocument?.let { doc ->
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.large, elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.pick_generated_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${doc.doctype} ${doc.name}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("pick_generated"))
                }
            }
        }
    }
    item { PickListDetailsCard(pickList) }
    item { SectionHeader(stringResource(R.string.pick_lines_title)) }
    items(pickList.items, key = { it.rowName }) { item ->
        PickRowCard(item = item, qty = item.pickedQty, status = item.status, highlighted = false, modifier = Modifier.testTag("pick_line_${item.rowName}"))
    }
}

@Composable
private fun GenerateBar(state: PickListUiState, pickList: PickList, onGenerate: () -> Unit, onBack: () -> Unit) {
    BottomActionBar {
        if (pickList.generatedDocument != null) {
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth().testTag("pick_done")) { Text(stringResource(R.string.common_done)) }
        } else {
            val label = pickList.purpose.targetDocument?.createLabelRes() ?: R.string.pick_create_unsupported
            Button(onClick = onGenerate, enabled = state.canGenerate, modifier = Modifier.fillMaxWidth().testTag("pick_generate")) {
                if (state.isGenerating) ButtonProgress() else Text(stringResource(label))
            }
        }
    }
}

// ---- Shared pieces -------------------------------------------------------------------------

@Composable
private fun PickListDetailsCard(pickList: PickList) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.large, elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pick_details_card), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusChip(stringResource(pickList.pickingStatus.labelRes()), pickingStatusColor(pickList.pickingStatus), modifier = Modifier.testTag("pick_status"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabelValue(stringResource(R.string.label_purpose), stringResource(pickList.purpose.labelRes()), Modifier.weight(1f))
                LabelValue(stringResource(R.string.label_customer), pickList.customerName ?: "-", Modifier.weight(1f))
                LabelValue(stringResource(R.string.label_picker), pickList.picker ?: "-", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabelValue(stringResource(R.string.label_source_warehouse), pickList.parentWarehouse ?: "-", Modifier.weight(1f))
                LabelValue(stringResource(R.string.label_target_warehouse), pickList.targetWarehouse ?: "-", Modifier.weight(1f))
                LabelValue(stringResource(R.string.pick_lines_title), pickList.itemCount.toString(), Modifier.weight(1f))
            }
            if (pickList.pickingStartedAt != null || pickList.pickingCompletedAt != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LabelValue(stringResource(R.string.label_started), formatTimestamp(pickList.pickingStartedAt), Modifier.weight(1f))
                    LabelValue(stringResource(R.string.label_completed), formatTimestamp(pickList.pickingCompletedAt), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PickRowCard(
    item: PickListItem,
    qty: Double,
    status: PickRowStatus,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val container = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Card(colors = CardDefaults.cardColors(containerColor = container), shape = MaterialTheme.shapes.medium, elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.itemName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(item.itemCode, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusChip(stringResource(status.labelRes()), rowStatusColor(status), modifier = Modifier.testTag("pick_row_status_${item.rowName}"))
            }
            val source = item.sourceWarehouse ?: "-"
            Text(
                if (item.targetWarehouse != null) stringResource(R.string.pick_row_transfer, source, item.targetWarehouse) else source,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.batchNo != null || item.expiryDate != null) {
                Text(
                    listOfNotNull(
                        item.batchNo?.let { stringResource(R.string.label_batch) + " " + it },
                        item.expiryDate?.let { stringResource(R.string.label_expiry) + " " + Formatters.date(it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.pick_row_qty, Formatters.qty(item.requiredQty), item.uom ?: "", Formatters.qty(qty)),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (item.optional) {
                    StatusChip(stringResource(R.string.pick_row_optional), MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            extra()
        }
    }
}

@Composable
private fun BottomActionBar(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), content = content)
    }
}

@Composable
private fun ButtonProgress(color: Color = MaterialTheme.colorScheme.onPrimary) {
    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = color)
}

@Composable
private fun pickingStatusColor(status: PickingStatus): Color = when (status) {
    PickingStatus.READY_TO_PICK -> WmsTheme.colors.info
    PickingStatus.PICKING -> WmsTheme.colors.warning
    PickingStatus.PICKED -> WmsTheme.colors.success
}

@Composable
private fun rowStatusColor(status: PickRowStatus): Color = when (status) {
    PickRowStatus.NOT_PICKED -> MaterialTheme.colorScheme.onSurfaceVariant
    PickRowStatus.PARTIAL -> WmsTheme.colors.warning
    PickRowStatus.PICKED -> WmsTheme.colors.success
}

/** "2026-09-28 09:12:00.123456" (Frappe datetime) -> "28 Sep, 09:12". */
private fun formatTimestamp(value: String?): String {
    if (value.isNullOrBlank()) return "-"
    return Formatters.dateTime(value.take(10), value.drop(11).takeIf { it.isNotBlank() })
}
