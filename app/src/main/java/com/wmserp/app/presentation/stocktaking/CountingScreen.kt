package com.wmserp.app.presentation.stocktaking

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wmserp.app.R
import com.wmserp.app.core.util.Formatters
import com.wmserp.app.domain.model.CountItemStatus
import com.wmserp.app.domain.model.CountType
import com.wmserp.app.domain.model.CountingMode
import com.wmserp.app.domain.model.ScanSource
import com.wmserp.app.domain.model.ScannedCode
import com.wmserp.app.domain.model.StocktakingItem
import com.wmserp.app.presentation.common.asString
import com.wmserp.app.presentation.common.labelRes
import com.wmserp.app.presentation.components.CameraScannerSheet
import com.wmserp.app.presentation.components.ErrorBanner
import com.wmserp.app.presentation.components.InfoBanner
import com.wmserp.app.presentation.components.LoadingState
import com.wmserp.app.presentation.components.ScannerListener
import com.wmserp.app.presentation.components.SearchField
import com.wmserp.app.presentation.components.StatusChip
import com.wmserp.app.presentation.components.WmsTopBar
import com.wmserp.app.presentation.components.scannerAwareFocus
import com.wmserp.app.presentation.theme.WmsTheme
import kotlinx.coroutines.delay

@Composable
fun CountingRoute(onBack: () -> Unit, viewModel: CountingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The hardware scanner feeds the screen only while it waits for the next label.
    ScannerListener(enabled = state.canScan) { viewModel.onScanned(it) }
    LifecycleResumeEffect(Unit) {
        viewModel.onResumed()
        onPauseOrDispose { }
    }
    CountingScreen(
        state = state,
        onBack = onBack,
        onToggleCamera = viewModel::toggleCamera,
        onCameraBarcode = { value, symbology -> viewModel.onScanned(ScannedCode(value, ScanSource.CAMERA, symbology)) },
        onQtyChange = viewModel::setQtyText,
        onUseErpQty = viewModel::useErpQty,
        onSubmit = viewModel::submitCount,
        onCancelCount = viewModel::cancelCounting,
        onSelectRow = viewModel::selectRow,
        onSyncNow = viewModel::syncNow,
        onDismissFeedback = viewModel::dismissFeedback,
        onRetry = viewModel::load,
        onToggleSearch = viewModel::toggleSearch,
        onQueryChange = viewModel::setQuery,
        onManualCode = { viewModel.onScanned(ScannedCode(it, ScanSource.MANUAL)) },
    )
}

/**
 * The counter's screen of one stocktaking session: SCAN → SEE ITEM → ENTER QTY → SUBMIT → NEXT.
 * The scan panel is the resting state; a recognised label turns into the count card (item, batch,
 * expiry, ERP quantity, one big number field); submitting returns to the scan panel at once.
 * Counts that could not reach ERPNext stay queued and are shown as "waiting to sync".
 */
@Composable
fun CountingScreen(
    state: CountingUiState,
    onBack: () -> Unit,
    onToggleCamera: () -> Unit,
    onCameraBarcode: (String, String?) -> Unit,
    onQtyChange: (String) -> Unit,
    onUseErpQty: () -> Unit,
    onSubmit: () -> Unit,
    onCancelCount: () -> Unit,
    onSelectRow: (String) -> Unit,
    onSyncNow: () -> Unit,
    onDismissFeedback: () -> Unit,
    onRetry: () -> Unit,
    onToggleSearch: () -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onManualCode: (String) -> Unit = {},
) {
    val session = state.session
    if (session != null && state.cameraActive && state.canScan) {
        CameraScannerSheet(
            title = stringResource(R.string.camera_sheet_title),
            onDismiss = onToggleCamera,
            onBarcode = onCameraBarcode,
            scanning = state.canScan,
        )
    }
    val feedback = state.feedback
    LaunchedEffect(feedback?.id) {
        if (feedback != null && feedback.kind != FeedbackKind.ERROR) {
            delay(FEEDBACK_MILLIS)
            onDismissFeedback()
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            WmsTopBar(
                title = session?.name ?: stringResource(R.string.st_title),
                subtitle = session?.let { "${it.warehouseName} · ${stringResource(it.mode.labelRes())}" },
                onBack = onBack,
                actions = {
                    if (session != null) {
                        IconButton(onClick = onToggleSearch, modifier = Modifier.testTag("st_search_toggle")) {
                            Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.st_find_item), tint = if (state.showSearch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onSyncNow, enabled = !state.isSyncing, modifier = Modifier.testTag("st_sync_now")) {
                            BadgedBox(badge = { if (state.pendingCount > 0) Badge { Text(state.pendingCount.toString()) } }) {
                                Icon(
                                    when {
                                        state.isSyncing -> Icons.Outlined.CloudUpload
                                        state.pendingCount > 0 -> Icons.Outlined.CloudOff
                                        else -> Icons.Outlined.CloudDone
                                    },
                                    contentDescription = stringResource(R.string.st_sync_now),
                                    tint = if (state.pendingCount > 0) WmsTheme.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (state.sessionOpen) {
                            IconButton(onClick = onToggleCamera, modifier = Modifier.testTag("st_camera_toggle")) {
                                Icon(
                                    Icons.Outlined.CameraAlt,
                                    contentDescription = stringResource(R.string.pick_camera),
                                    tint = if (state.cameraActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (session != null && !state.isLoading) {
                when (val phase = state.phase) {
                    is CountingPhase.Counting -> SubmitBar(phase, onSubmit, onCancelCount)
                    else -> ScanBar(state, onToggleCamera, onSyncNow)
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(padding)) {
            feedback?.let { FeedbackBanner(it, onDismissFeedback) }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.isLoading) {
                    item {
                        val progress = state.loadProgress
                        LoadingState(
                            modifier = Modifier.height(240.dp),
                            message = if (progress == null) stringResource(R.string.st_loading_start)
                            else stringResource(R.string.st_loading_items, Formatters.int(progress.first), Formatters.int(progress.second)),
                        )
                    }
                    return@LazyColumn
                }
                state.error?.let { error ->
                    item { ErrorBanner(error.asString(), onRetry = if (session == null) onRetry else null, onDismiss = if (session != null) onDismissFeedback else null, modifier = Modifier.testTag("st_error")) }
                }
                if (session == null) return@LazyColumn
                if (state.fromCache) {
                    item {
                        InfoBanner(
                            stringResource(R.string.st_offline_cache, Formatters.dateTime(cachedDate(state.cachedAtMillis), cachedTime(state.cachedAtMillis))),
                            container = WmsTheme.colors.warningContainer,
                            content = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("st_offline"),
                        )
                    }
                }
                if (!state.sessionOpen) {
                    item {
                        InfoBanner(
                            stringResource(R.string.st_closed_title) + " — " + stringResource(R.string.st_closed_message, stringResource(session.status.labelRes())),
                            container = MaterialTheme.colorScheme.errorContainer,
                            content = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.testTag("st_closed"),
                        )
                    }
                }
                when (val phase = state.phase) {
                    is CountingPhase.Counting -> item { CountCard(phase, state, onQtyChange, onUseErpQty, onSubmit) }
                    is CountingPhase.ChoosingBatch -> {
                        item { BatchChooserHeader(phase) }
                        items(phase.rows, key = { it.name }) { row -> BatchRowCard(row, onClick = { onSelectRow(row.name) }) }
                    }
                    CountingPhase.Scanning -> {
                        if (state.showSearch) {
                            item { SearchPanel(state, onQueryChange, onManualCode) }
                            val results = state.searchResults
                            if (state.query.isNotBlank() && results.isEmpty()) {
                                item { Text(stringResource(R.string.st_search_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            items(results, key = { it.name }) { row -> BatchRowCard(row, onClick = { onSelectRow(row.name) }, tag = "st_search_result_${row.name}") }
                        } else {
                            if (state.sessionOpen) item { ReadyPanel(state) }
                            item { ProgressCard(state) }
                            state.lastCount?.let { last -> item { LastCountCard(last) } }
                        }
                    }
                }
            }
        }
    }
}

private const val FEEDBACK_MILLIS = 1600L

// ---- scanning state -------------------------------------------------------------------------

/** The resting state: a large hint that the device is listening for the next label. */
@Composable
private fun ReadyPanel(state: CountingUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().testTag("st_ready"),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.size(72.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                if (state.isLookingUp) CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                else Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.st_ready_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(if (state.hardwareScannerReady) R.string.st_ready_hint_hardware else R.string.st_ready_hint_camera),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("st_scanner_status"),
            )
        }
    }
}

@Composable
private fun ProgressCard(state: CountingUiState) {
    val totals = state.totals
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.large, elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.st_progress_counted, Formatters.int(totals.counted), Formatters.int(totals.total)),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).testTag("st_progress"),
                )
                if (state.session?.mode == CountingMode.ASSIGNED) {
                    Text(stringResource(R.string.st_progress_mine, Formatters.int(state.myOpen), Formatters.int(state.myTotal)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LinearProgressIndicator(progress = { totals.progress }, modifier = Modifier.fillMaxWidth())
            Text(
                listOf(
                    stringResource(R.string.st_progress_not_counted, Formatters.int(totals.uncounted)),
                    stringResource(R.string.st_progress_review, Formatters.int(totals.pendingReview + totals.recountRequired)),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PendingSyncLine(state)
        }
    }
}

@Composable
private fun PendingSyncLine(state: CountingUiState) {
    val text = when {
        state.isSyncing -> stringResource(R.string.st_syncing)
        state.pendingCount > 0 -> stringResource(R.string.st_pending_sync, Formatters.int(state.pendingCount))
        else -> stringResource(R.string.st_pending_synced)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (state.pendingCount > 0 && !state.isSyncing) Icons.Outlined.CloudOff else Icons.Outlined.CloudDone,
            contentDescription = null,
            tint = if (state.pendingCount > 0) WmsTheme.colors.warning else WmsTheme.colors.success,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = if (state.pendingCount > 0) WmsTheme.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("st_pending"))
    }
}

@Composable
private fun LastCountCard(item: StocktakingItem) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.medium, elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), modifier = Modifier.fillMaxWidth().testTag("st_last")) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.st_last_count, Formatters.qty(item.lastCount ?: 0.0), item.itemName), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(item.itemCode, item.batchNo).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusChip(stringResource(item.status.labelRes()), rowStatusColor(item.status))
        }
    }
}

@Composable
private fun SearchPanel(state: CountingUiState, onQueryChange: (String) -> Unit, onManualCode: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.st_search_placeholder),
            leadingIcon = Icons.Outlined.Search,
            modifier = Modifier.testTag("st_search"),
        )
        if (state.query.isNotBlank() && state.searchResults.isEmpty() && state.sessionOpen) {
            TextButton(onClick = { onManualCode(state.query) }, modifier = Modifier.testTag("st_search_lookup")) { Text(stringResource(R.string.st_find_item) + ": " + state.query.trim()) }
        }
    }
}

@Composable
private fun ScanBar(state: CountingUiState, onToggleCamera: () -> Unit, onSyncNow: () -> Unit) {
    BottomActionBar {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.showCameraButton && state.sessionOpen) {
                Button(onClick = onToggleCamera, modifier = Modifier.weight(1f).testTag("st_camera_button")) {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.pick_scan_camera_button), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (state.pendingCount > 0) {
                OutlinedButton(onClick = onSyncNow, enabled = !state.isSyncing, modifier = Modifier.weight(1f).testTag("st_sync_button")) {
                    if (state.isSyncing) ButtonProgress(MaterialTheme.colorScheme.primary) else Text(stringResource(R.string.st_sync_now) + " (${state.pendingCount})", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---- counting state ---------------------------------------------------------------------------

/** Item, batch, expiry, ERP quantity and one big number field. */
@Composable
private fun CountCard(phase: CountingPhase.Counting, state: CountingUiState, onQtyChange: (String) -> Unit, onUseErpQty: () -> Unit, onSubmit: () -> Unit) {
    val item = phase.item
    val blind = state.session?.blindCount == true
    val focus = remember { FocusRequester() }
    LaunchedEffect(item.name, item.itemCode, phase.countType) { runCatching { focus.requestFocus() } }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().testTag("st_count_card"),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (phase.secondCount) {
                Notice(
                    icon = Icons.Outlined.WarningAmber,
                    container = WmsTheme.colors.warningContainer,
                    title = stringResource(R.string.st_second_count_title),
                    message = stringResource(R.string.st_second_count_message, Formatters.qty(item.count1 ?: 0.0)),
                    tag = "st_second_count",
                )
            } else if (phase.countType == CountType.RECOUNT && item.status == CountItemStatus.RECOUNT_REQUIRED) {
                Notice(
                    icon = Icons.Outlined.Info,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    title = stringResource(R.string.st_recount_title),
                    message = item.recountNote?.let { stringResource(R.string.st_recount_note, it) },
                    tag = "st_recount",
                )
            }
            if (phase.isNew) {
                Text(stringResource(R.string.st_not_in_list), style = MaterialTheme.typography.labelMedium, color = WmsTheme.colors.warning, modifier = Modifier.testTag("st_new_row"))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.st_item_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                StatusChip(stringResource(phase.countType.labelRes()), MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("st_count_type"))
            }
            Text(item.itemName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("st_item_name"))
            Text(listOfNotNull(item.itemCode, item.warehouse).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.st_batch_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        item.batchNo ?: stringResource(R.string.st_no_batch),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (item.batchNo != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("st_batch"),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.st_expiry_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.expiryDate?.let { Formatters.date(it) } ?: "-", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("st_expiry"))
                }
            }
            Column {
                Text(stringResource(R.string.st_erp_qty_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (blind || item.erpQty == null) {
                    Text(if (blind) stringResource(R.string.st_blind_hint) else "-", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("st_erp_qty"))
                } else {
                    Text("${Formatters.qty(item.erpQty)} ${item.uom.orEmpty()}".trim(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("st_erp_qty"))
                }
            }
            Text(stringResource(R.string.st_physical_count_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val uomSuffix: (@Composable () -> Unit)? = item.uom?.let { uom -> { Text(uom, style = MaterialTheme.typography.labelMedium) } }
            OutlinedTextField(
                value = phase.qtyText,
                onValueChange = onQtyChange,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).scannerAwareFocus().testTag("st_qty_input"),
                singleLine = true,
                isError = phase.qtyText.isNotBlank() && !phase.isValid,
                textStyle = MaterialTheme.typography.displaySmall.copy(textAlign = TextAlign.Center, fontWeight = FontWeight.Bold),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (phase.isValid) onSubmit() }),
                suffix = uomSuffix,
            )
            if (!blind && item.erpQty != null) {
                AssistChip(
                    onClick = onUseErpQty,
                    label = { Text(stringResource(R.string.st_same_as_erp, Formatters.qty(item.erpQty))) },
                    leadingIcon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("st_same_as_erp"),
                )
            }
        }
    }
}

@Composable
private fun SubmitBar(phase: CountingPhase.Counting, onSubmit: () -> Unit, onCancel: () -> Unit) {
    BottomActionBar {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).testTag("st_cancel")) { Text(stringResource(R.string.st_cancel_count)) }
            Button(onClick = onSubmit, enabled = phase.isValid, modifier = Modifier.weight(2f).testTag("st_submit")) {
                Text(stringResource(R.string.st_submit_count), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---- batch chooser ----------------------------------------------------------------------------

@Composable
private fun BatchChooserHeader(phase: CountingPhase.ChoosingBatch) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("st_choose_batch")) {
        Text(stringResource(R.string.st_choose_batch_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.st_choose_batch_message, phase.itemName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One row (batch / warehouse) as a tappable card; also used for search results. */
@Composable
private fun BatchRowCard(row: StocktakingItem, onClick: () -> Unit, tag: String = "st_batch_${row.name}") {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.batchNo ?: row.itemName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(if (row.batchNo != null) row.itemName else null, row.itemCode, row.warehouse).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                row.expiryDate?.let { Text(stringResource(R.string.st_batch_expires, Formatters.date(it)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (row.status.isCounted) {
                    Text(
                        listOfNotNull(row.countedByName?.let { stringResource(R.string.st_counted_by, it) }, row.lastCount?.let { Formatters.qty(it) }).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(stringResource(R.string.st_open_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                row.erpQty?.let { Text("${Formatters.qty(it)} ${row.uom.orEmpty()}".trim(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                StatusChip(stringResource(row.status.labelRes()), rowStatusColor(row.status), modifier = Modifier.testTag("st_row_status_${row.name}"))
            }
        }
    }
}

// ---- shared pieces ------------------------------------------------------------------------------

/** Green accepted / blue info / amber warning / red error strip under the top bar. */
@Composable
private fun FeedbackBanner(feedback: CountFeedback, onDismiss: () -> Unit) {
    val (container, content, icon) = when (feedback.kind) {
        FeedbackKind.SUCCESS -> Triple(WmsTheme.colors.success, Color.White, Icons.Outlined.CheckCircle)
        FeedbackKind.INFO -> Triple(WmsTheme.colors.info, Color.White, Icons.Outlined.Info)
        FeedbackKind.WARNING -> Triple(WmsTheme.colors.warning, Color.White, Icons.Outlined.WarningAmber)
        FeedbackKind.ERROR -> Triple(WmsTheme.colors.danger, Color.White, Icons.Outlined.ErrorOutline)
    }
    Surface(color = container, modifier = Modifier.fillMaxWidth().testTag("st_feedback_${feedback.kind.name}")) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(feedback.title.asString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = content, modifier = Modifier.testTag("st_feedback_title"))
                feedback.detail?.let { Text(it.asString(), style = MaterialTheme.typography.bodyMedium, color = content, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            if (feedback.kind == FeedbackKind.ERROR) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_dismiss), color = content) }
            }
        }
    }
}

@Composable
private fun Notice(icon: androidx.compose.ui.graphics.vector.ImageVector, container: Color, title: String, message: String?, tag: String) {
    Surface(color = container, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
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
private fun rowStatusColor(status: CountItemStatus): Color = when (status) {
    CountItemStatus.NOT_COUNTED, CountItemStatus.ASSIGNED -> MaterialTheme.colorScheme.onSurfaceVariant
    CountItemStatus.COUNTING -> WmsTheme.colors.info
    CountItemStatus.COUNTED, CountItemStatus.APPROVED, CountItemStatus.FINALIZED -> WmsTheme.colors.success
    CountItemStatus.RECOUNT_REQUIRED -> WmsTheme.colors.danger
    CountItemStatus.RECOUNTED, CountItemStatus.MANAGER_REVIEW -> WmsTheme.colors.warning
}

private fun cachedDate(millis: Long): String = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
private fun cachedTime(millis: Long): String = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalTime().toString().take(8)
