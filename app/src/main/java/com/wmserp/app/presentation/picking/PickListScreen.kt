package com.wmserp.app.presentation.picking

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.wmserp.app.domain.model.PickRowStatus
import com.wmserp.app.domain.model.PickingStatus
import com.wmserp.app.domain.model.ScanSource
import com.wmserp.app.domain.model.ScannedCode
import com.wmserp.app.presentation.common.asString
import com.wmserp.app.presentation.common.createLabelRes
import com.wmserp.app.presentation.common.labelRes
import com.wmserp.app.presentation.components.ErrorBanner
import com.wmserp.app.presentation.components.InfoBanner
import com.wmserp.app.presentation.components.LabelValue
import com.wmserp.app.presentation.components.LoadingState
import com.wmserp.app.presentation.components.ScannerListener
import com.wmserp.app.presentation.components.SectionHeader
import com.wmserp.app.presentation.components.StatusChip
import com.wmserp.app.presentation.components.WmsTopBar
import com.wmserp.app.presentation.scan.CameraScannerPane
import com.wmserp.app.presentation.theme.WmsTheme

@Composable
fun PickListRoute(onBack: () -> Unit, onDone: () -> Unit, viewModel: PickListViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The hardware scanner feeds this screen only while the picker still has open rows.
    ScannerListener(enabled = state.canScan) { viewModel.onScanned(it) }
    PickListScreen(
        state = state,
        onBack = onBack,
        onDone = onDone,
        onSelectRow = viewModel::selectRow,
        onToggleCamera = viewModel::toggleCamera,
        onCameraBarcode = { value, symbology -> viewModel.onScanned(ScannedCode(value, ScanSource.CAMERA, symbology)) },
        onComplete = viewModel::completePicking,
        onGenerate = viewModel::generateDocument,
        onDismissMessage = viewModel::dismissMessage,
        onRetry = viewModel::load,
    )
}

/**
 * Active picking for the rows assigned to the signed-in picker. Quantities only move through
 * scanned JSON QR labels (no manual batch input); a wrong batch is shown as a large red alert.
 * When the picker's rows are done the screen becomes either the "task completed" state or, for
 * the picker who closed the last row of the card, the post-picking state with the document CTA.
 */
@Composable
fun PickListScreen(
    state: PickListUiState,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onSelectRow: (String) -> Unit,
    onToggleCamera: () -> Unit,
    onCameraBarcode: (String, String?) -> Unit,
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
                actions = {
                    if (pickList != null && state.outcome == null) {
                        IconButton(onClick = onToggleCamera, modifier = Modifier.testTag("pick_camera_toggle")) {
                            Icon(
                                Icons.Outlined.CameraAlt,
                                contentDescription = stringResource(R.string.pick_camera),
                                tint = if (state.cameraActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (pickList != null) {
                when (state.outcome) {
                    null -> PickingBar(state, onComplete)
                    PickOutcome.TASK_COMPLETED -> DoneBar(onDone)
                    PickOutcome.CARD_COMPLETED -> GenerateBar(state, pickList, onGenerate, onDone)
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(padding)) {
            if (pickList != null && state.cameraActive && state.outcome == null) {
                Box(modifier = Modifier.fillMaxWidth().height(220.dp).background(Color.Black)) {
                    CameraScannerPane(onBarcode = onCameraBarcode)
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
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
                state.scanAlert?.let { alert -> item { ScanAlertBanner(alert, onDismissMessage) } }
                state.message?.let { message ->
                    item { InfoBanner(message.asString(), container = WmsTheme.colors.successContainer, content = MaterialTheme.colorScheme.onSurface) }
                }
                when (state.outcome) {
                    null -> activeContent(state, pickList, onSelectRow)
                    PickOutcome.TASK_COMPLETED -> taskCompletedContent(state, pickList)
                    PickOutcome.CARD_COMPLETED -> cardCompletedContent(state, pickList)
                }
            }
        }
    }
}

// ---- Active picking ------------------------------------------------------------------------

private fun LazyListScope.activeContent(state: PickListUiState, pickList: PickList, onSelectRow: (String) -> Unit) {
    item { PickListDetailsCard(pickList, state) }
    item { Text(stringResource(R.string.pick_scan_hint_qr), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    item { SectionHeader(stringResource(R.string.pick_my_rows)) }
    items(state.lines, key = { it.item.rowName }) { line ->
        PickRowCard(
            line = line,
            active = state.activeRowName == line.item.rowName,
            onClick = { onSelectRow(line.item.rowName) },
        )
    }
}

@Composable
private fun PickingBar(state: PickListUiState, onComplete: () -> Unit) {
    BottomActionBar {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.pick_progress_summary, Formatters.qty(state.myPicked), Formatters.qty(state.myRequired)),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).testTag("pick_progress"),
            )
            if (state.isSyncing) {
                Text(stringResource(R.string.pick_syncing), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LinearProgressIndicator(progress = { state.myProgress }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        Button(onClick = onComplete, enabled = state.canComplete, modifier = Modifier.fillMaxWidth().testTag("pick_complete")) {
            if (state.isCompleting) ButtonProgress() else Text(stringResource(R.string.pick_complete))
        }
    }
}

// ---- Task completed (other pickers still working) ---------------------------------------------

private fun LazyListScope.taskCompletedContent(state: PickListUiState, pickList: PickList) {
    item {
        OutcomeCard(
            title = stringResource(R.string.pick_task_completed_title),
            subtitle = stringResource(R.string.pick_task_completed_message),
            tag = "pick_task_completed",
        ) {
            if (state.otherRows > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.pick_other_pickers, state.otherPickedRows, state.otherRows),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    item { PickListDetailsCard(pickList, state) }
    item { SectionHeader(stringResource(R.string.pick_my_rows)) }
    items(state.lines, key = { it.item.rowName }) { line -> PickRowCard(line = line, active = false, onClick = null) }
}

@Composable
private fun DoneBar(onDone: () -> Unit) {
    BottomActionBar {
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().testTag("pick_done")) { Text(stringResource(R.string.pick_back_to_dashboard)) }
    }
}

// ---- Card completed (last picker): document CTA ----------------------------------------------

private fun LazyListScope.cardCompletedContent(state: PickListUiState, pickList: PickList) {
    item {
        OutcomeCard(title = stringResource(R.string.pick_picked_title), subtitle = stringResource(R.string.pick_picked_subtitle), tag = "pick_picked_title")
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
    item { PickListDetailsCard(pickList, state) }
    if (state.lines.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.pick_my_rows)) }
        items(state.lines, key = { it.item.rowName }) { line -> PickRowCard(line = line, active = false, onClick = null) }
    }
}

@Composable
private fun GenerateBar(state: PickListUiState, pickList: PickList, onGenerate: () -> Unit, onDone: () -> Unit) {
    BottomActionBar {
        if (pickList.generatedDocument != null) {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().testTag("pick_done")) { Text(stringResource(R.string.common_done)) }
        } else {
            val label = pickList.purpose.targetDocument?.createLabelRes() ?: R.string.pick_create_unsupported
            Button(onClick = onGenerate, enabled = state.canGenerate, modifier = Modifier.fillMaxWidth().testTag("pick_generate")) {
                if (state.isGenerating) ButtonProgress() else Text(stringResource(label))
            }
        }
    }
}

// ---- Shared pieces -------------------------------------------------------------------------

/** Big red banner for rejected scans: wrong batch, unknown item, complete row or invalid label. */
@Composable
private fun ScanAlertBanner(alert: ScanAlert, onDismiss: () -> Unit) {
    val title = when (alert.kind) {
        ScanAlertKind.WRONG_BATCH -> R.string.pick_alert_wrong_batch_title
        ScanAlertKind.NOT_ASSIGNED -> R.string.pick_alert_not_assigned_title
        ScanAlertKind.ALREADY_COMPLETE -> R.string.pick_alert_complete_title
        ScanAlertKind.INVALID_QR -> R.string.pick_alert_invalid_title
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = WmsTheme.colors.danger),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().testTag("pick_alert_${alert.kind.name}"),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                Spacer(Modifier.size(10.dp))
                Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Text(alert.message.asString(), style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.testTag("pick_alert_message"))
            alert.expected?.let { Text(stringResource(R.string.pick_alert_expected, it), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White) }
            alert.scanned?.let { Text(stringResource(R.string.pick_alert_scanned, it), style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.common_dismiss), color = Color.White) }
        }
    }
}

@Composable
private fun OutcomeCard(title: String, subtitle: String, tag: String, extra: @Composable ColumnScope.() -> Unit = {}) {
    Card(colors = CardDefaults.cardColors(containerColor = WmsTheme.colors.successContainer), shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = WmsTheme.colors.success, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag(tag))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            extra()
        }
    }
}

@Composable
private fun PickListDetailsCard(pickList: PickList, state: PickListUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.large, elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pick_details_card), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusChip(stringResource(pickList.pickingStatus.labelRes()), pickingStatusColor(pickList.pickingStatus), modifier = Modifier.testTag("pick_status"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabelValue(stringResource(R.string.label_purpose), stringResource(pickList.purpose.labelRes()), Modifier.weight(1f))
                LabelValue(stringResource(R.string.label_customer), pickList.customerName ?: "-", Modifier.weight(1f))
                LabelValue(stringResource(R.string.pick_my_rows), "${state.lines.count { it.isComplete }} / ${state.lines.size}", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabelValue(stringResource(R.string.label_source_warehouse), pickList.parentWarehouse ?: "-", Modifier.weight(1f))
                LabelValue(stringResource(R.string.label_target_warehouse), pickList.targetWarehouse ?: "-", Modifier.weight(1f))
                LabelValue(stringResource(R.string.pick_lines_title), "${pickList.pickedRows} / ${pickList.itemCount}", Modifier.weight(1f))
            }
            if (state.otherRows > 0) {
                Text(
                    stringResource(R.string.pick_other_pickers, state.otherPickedRows, state.otherRows),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pickList.cardStartedAt != null || pickList.cardCompletedAt != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LabelValue(stringResource(R.string.label_started), formatTimestamp(pickList.cardStartedAt), Modifier.weight(1f))
                    LabelValue(stringResource(R.string.label_completed), formatTimestamp(pickList.cardCompletedAt), Modifier.weight(1f))
                }
            }
        }
    }
}

/** One of the picker's rows: expected batch in bold, picked/required, status. No manual inputs. */
@Composable
private fun PickRowCard(line: PickLineState, active: Boolean, onClick: (() -> Unit)?) {
    val item = line.item
    val container = when {
        line.highlighted -> MaterialTheme.colorScheme.secondaryContainer
        active -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val clickable = if (onClick != null && !line.isComplete) Modifier.clickable(onClick = onClick) else Modifier
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().then(clickable).testTag("pick_line_${item.rowName}"),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.itemName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(item.itemCode, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (active && !line.isComplete) {
                    StatusChip(stringResource(R.string.pick_active), MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 6.dp))
                }
                StatusChip(stringResource(line.status.labelRes()), rowStatusColor(line.status), modifier = Modifier.testTag("pick_row_status_${item.rowName}"))
            }
            val source = item.sourceWarehouse ?: "-"
            Text(
                if (item.targetWarehouse != null) stringResource(R.string.pick_row_transfer, source, item.targetWarehouse) else source,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column {
                Text(stringResource(R.string.pick_expected_batch), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    item.batchNo ?: stringResource(R.string.pick_no_batch),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (item.batchNo != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("pick_expected_batch_${item.rowName}"),
                )
                item.expiryDate?.let {
                    Text(stringResource(R.string.label_expiry) + " " + Formatters.date(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.pick_row_qty, Formatters.qty(line.qty), Formatters.qty(item.requiredQty), item.uom ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).testTag("pick_qty_${item.rowName}"),
                )
                if (line.syncing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            LinearProgressIndicator(
                progress = { if (item.requiredQty <= 0.0) 0f else (line.qty / item.requiredQty).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = rowStatusColor(line.status),
            )
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
    PickRowStatus.PICKING -> WmsTheme.colors.warning
    PickRowStatus.PICKED -> WmsTheme.colors.success
}

/** "2026-09-28 09:12:00.123456" (Frappe datetime) -> "28 Sep, 09:12". */
private fun formatTimestamp(value: String?): String {
    if (value.isNullOrBlank()) return "-"
    return Formatters.dateTime(value.take(10), value.drop(11).takeIf { it.isNotBlank() })
}
