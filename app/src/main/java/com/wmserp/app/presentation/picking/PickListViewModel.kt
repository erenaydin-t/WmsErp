package com.wmserp.app.presentation.picking

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmserp.app.R
import com.wmserp.app.core.scanner.ScannerController
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.GeneratedDocument
import com.wmserp.app.domain.model.PickList
import com.wmserp.app.domain.model.PickListItem
import com.wmserp.app.domain.model.PickRowStatus
import com.wmserp.app.domain.model.PickingStatus
import com.wmserp.app.domain.model.RowUpdate
import com.wmserp.app.domain.model.ScanSource
import com.wmserp.app.domain.model.ScannedCode
import com.wmserp.app.domain.model.ScannerMode
import com.wmserp.app.domain.model.WmsQrKeys
import com.wmserp.app.domain.model.isPickComplete
import com.wmserp.app.domain.usecase.CompletePickRowUseCase
import com.wmserp.app.domain.usecase.GeneratePickDocumentUseCase
import com.wmserp.app.domain.usecase.GetPickListUseCase
import com.wmserp.app.domain.usecase.GetWmsQrKeysUseCase
import com.wmserp.app.domain.usecase.ObserveScannerSettingsUseCase
import com.wmserp.app.domain.usecase.PickScanOutcome
import com.wmserp.app.domain.usecase.SavePickRowProgressUseCase
import com.wmserp.app.domain.usecase.StartPickRowUseCase
import com.wmserp.app.domain.usecase.ValidatePickScanUseCase
import com.wmserp.app.presentation.common.ScanQuantityPrompt
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.presentation.common.messageRes
import com.wmserp.app.presentation.common.toUiText
import com.wmserp.app.presentation.orders.format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One of the picker's rows with the quantity counted on the device and its hidden timer. */
data class PickLineState(
    val item: PickListItem,
    val qty: Double = item.pickedQty,
    /** Device clock when the row was started (hidden timer); null until the first scan/start. */
    val startedAtMillis: Long? = null,
    /** Number of server calls in flight for this row; the local count is ahead of the server while > 0. */
    val pendingSyncs: Int = 0,
    val highlighted: Boolean = false,
) {
    val syncing: Boolean get() = pendingSyncs > 0
    val isComplete: Boolean get() = item.rowStatus == PickRowStatus.PICKED || isPickComplete(qty, item.requiredQty)
    val remaining: Double get() = (item.requiredQty - qty).coerceAtLeast(0.0)
    val status: PickRowStatus
        get() = when {
            isComplete -> PickRowStatus.PICKED
            qty > 0.0 || item.rowStatus == PickRowStatus.PICKING || startedAtMillis != null -> PickRowStatus.PICKING
            else -> PickRowStatus.NOT_PICKED
        }

    fun elapsedSeconds(now: Long): Double? = startedAtMillis?.let { ((now - it) / 1000.0).coerceAtLeast(0.0) }
}

enum class ScanAlertKind { WRONG_BATCH, NOT_ASSIGNED, ALREADY_COMPLETE, INVALID_QR }

/** Large red error shown after a rejected scan. */
data class ScanAlert(val kind: ScanAlertKind, val message: UiText, val expected: String? = null, val scanned: String? = null)

/** What the screen shows once every row of the picker is done. */
enum class PickOutcome {
    /** This picker completed the last row of the card (or the card is already picked): show the document CTA. */
    CARD_COMPLETED,

    /** The picker's rows are done but other pickers are still working on the card. */
    TASK_COMPLETED,
}

data class PickListUiState(
    val isLoading: Boolean = true,
    val pickList: PickList? = null,
    val lines: List<PickLineState> = emptyList(),
    val qrKeys: WmsQrKeys = WmsQrKeys.DEFAULT,
    val activeRowName: String? = null,
    val isGenerating: Boolean = false,
    val isCompleting: Boolean = false,
    val outcome: PickOutcome? = null,
    val cameraActive: Boolean = false,
    val hasHardwareScanner: Boolean = false,
    val scannerMode: ScannerMode = ScannerMode.AUTO,
    /** A matching scan opens the quantity prompt (settings); off, every scan adds one unit. */
    val askQuantity: Boolean = true,
    /** The matching scan waiting for its quantity; scans are ignored while it is open. */
    val pendingScan: ScanQuantityPrompt? = null,
    val scanAlert: ScanAlert? = null,
    val error: UiText? = null,
    val message: UiText? = null,
    val beep: Boolean = true,
    val vibrate: Boolean = true,
) {
    val cardStatus: PickingStatus? get() = pickList?.pickingStatus
    val isSyncing: Boolean get() = lines.any { it.syncing }
    val myRequired: Double get() = lines.sumOf { it.item.requiredQty }
    val myPicked: Double get() = lines.sumOf { it.qty }
    val myProgress: Float get() = if (myRequired <= 0.0) 0f else (myPicked / myRequired).toFloat().coerceIn(0f, 1f)
    val allMyRowsComplete: Boolean get() = lines.isNotEmpty() && lines.all { it.isComplete }
    val generatedDocument: GeneratedDocument? get() = pickList?.generatedDocument
    val otherRows: Int get() = (pickList?.itemCount ?: 0) - lines.size
    val otherPickedRows: Int get() = ((pickList?.pickedRows ?: 0) - lines.count { it.item.rowStatus == PickRowStatus.PICKED }).coerceAtLeast(0)

    /** The row the picker should scan next: the active one while open, else the first open row. */
    val nextLine: PickLineState?
        get() = lines.firstOrNull { it.item.rowName == activeRowName && !it.isComplete } ?: lines.firstOrNull { !it.isComplete }

    /** The trigger of the built-in scanner is the primary input; the camera is only offered where it is needed. */
    val hardwareScannerReady: Boolean get() = hasHardwareScanner && scannerMode != ScannerMode.CAMERA
    val showCameraButton: Boolean get() = !hardwareScannerReady

    /** Scanning is accepted while the picker still has open rows and no quantity prompt is waiting. */
    val canScan: Boolean get() = pickList != null && outcome == null && !allMyRowsComplete && !isCompleting && !isGenerating && pendingScan == null
    val canComplete: Boolean get() = pickList != null && outcome == null && allMyRowsComplete && !isCompleting && !isGenerating && !isSyncing
    val canGenerate: Boolean
        get() = !isGenerating && !isCompleting && outcome == PickOutcome.CARD_COMPLETED && pickList?.purpose?.targetDocument != null && generatedDocument == null
}

/**
 * Drives the picker's rows of one Pick List. Scans are strictly validated JSON QR labels: a match
 * opens a quantity prompt prefilled with everything still open on the row (or adds one unit when
 * that setting is off), the confirmed quantity syncs immediately (partial saves), the required
 * quantity completes the row on the server, and the response tells whether this picker closed the
 * whole card.
 */
@HiltViewModel
class PickListViewModel @Inject constructor(
    private val getPickList: GetPickListUseCase,
    private val getQrKeys: GetWmsQrKeysUseCase,
    private val startRow: StartPickRowUseCase,
    private val saveRowProgress: SavePickRowProgressUseCase,
    private val completeRow: CompletePickRowUseCase,
    private val generatePickDocument: GeneratePickDocumentUseCase,
    private val validateScan: ValidatePickScanUseCase,
    observeScannerSettings: ObserveScannerSettingsUseCase,
    private val scanner: ScannerController,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val name: String = savedStateHandle.get<String>(ARG_NAME).orEmpty()

    /** Device clock for the hidden row timers; replaceable in tests. */
    internal var clock: () -> Long = System::currentTimeMillis

    private val _uiState = MutableStateFlow(PickListUiState(hasHardwareScanner = scanner.hasHardwareScanner))
    val uiState: StateFlow<PickListUiState> = _uiState.asStateFlow()

    /** The match behind [PickListUiState.pendingScan] (row + label), applied once the quantity is confirmed. */
    private var pendingMatch: PickScanOutcome.Match? = null
    private var settingsApplied = false

    init {
        viewModelScope.launch {
            observeScannerSettings().collect { s ->
                _uiState.update {
                    it.copy(
                        beep = s.beepOnScan,
                        vibrate = s.vibrateOnScan,
                        askQuantity = s.askQuantityOnScan,
                        scannerMode = s.mode,
                        // The camera opens by itself only when the user chose it explicitly; a manual toggle wins afterwards.
                        cameraActive = if (settingsApplied) it.cameraActive else s.mode == ScannerMode.CAMERA,
                    )
                }
                settingsApplied = true
            }
        }
        viewModelScope.launch {
            getQrKeys().getOrNull()?.let { keys -> _uiState.update { it.copy(qrKeys = keys) } }
        }
        load()
    }

    fun load() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = getPickList(name)) {
                is AppResult.Success -> _uiState.update { it.withPickList(result.data).copy(isLoading = false, outcome = initialOutcome(result.data)) }
                is AppResult.Failure -> _uiState.update { it.copy(isLoading = false, error = result.error.toUiText()) }
            }
        }
    }

    fun toggleCamera() = _uiState.update { it.copy(cameraActive = !it.cameraActive) }

    fun dismissMessage() = _uiState.update { it.copy(message = null, error = null, scanAlert = null) }

    /** Selecting a row makes it the active one and starts its (hidden) timer on the server. */
    fun selectRow(rowName: String) {
        val state = _uiState.value
        val pickList = state.pickList ?: return
        val line = state.lines.firstOrNull { it.item.rowName == rowName } ?: return
        if (line.isComplete) return
        _uiState.update { it.copy(activeRowName = rowName, scanAlert = null) }
        if (line.startedAtMillis == null) startTimer(pickList, line)
    }

    /** Hardware wedge, intent, camera or manual input: only JSON QR labels are accepted. */
    fun onScanned(code: ScannedCode) {
        val state = _uiState.value
        val pickList = state.pickList ?: return
        if (!state.canScan) return
        val outcome = validateScan(
            rawCode = code.value,
            keys = state.qrKeys,
            rows = state.lines.map { it.item },
            currentQty = { row -> state.lines.firstOrNull { it.item.rowName == row.rowName }?.qty ?: row.pickedQty },
            activeRowName = state.activeRowName,
        )
        when (outcome) {
            is PickScanOutcome.Match -> {
                if (code.source != ScanSource.MANUAL) scanner.feedback(state.beep, state.vibrate)
                if (state.askQuantity) askQuantity(outcome) else applyScan(pickList, outcome, add = 1.0)
            }
            is PickScanOutcome.WrongBatch -> reject(
                ScanAlert(
                    ScanAlertKind.WRONG_BATCH,
                    UiText.Res(R.string.pick_wrong_batch, listOf(outcome.expected, outcome.scanned ?: "-")),
                    expected = outcome.expected,
                    scanned = outcome.scanned,
                ),
                highlight = outcome.row.rowName,
            )
            is PickScanOutcome.NotAssigned -> reject(
                ScanAlert(ScanAlertKind.NOT_ASSIGNED, UiText.Res(R.string.pick_item_not_assigned, listOf(outcome.label.itemCode)))
            )
            is PickScanOutcome.AlreadyComplete -> reject(
                ScanAlert(ScanAlertKind.ALREADY_COMPLETE, UiText.Res(R.string.pick_row_already_complete, listOf(outcome.row.itemCode, outcome.row.requiredQty.format()))),
                highlight = outcome.row.rowName,
            )
            is PickScanOutcome.InvalidQr -> reject(
                ScanAlert(ScanAlertKind.INVALID_QR, UiText.Res(outcome.error.messageRes()), scanned = outcome.raw.take(80))
            )
        }
    }

    // ---- quantity prompt -------------------------------------------------------------------

    fun setPendingQty(text: String) = _uiState.update { it.copy(pendingScan = it.pendingScan?.withText(text)) }
    fun incrementPendingQty() = _uiState.update { it.copy(pendingScan = it.pendingScan?.plusOne()) }
    fun decrementPendingQty() = _uiState.update { it.copy(pendingScan = it.pendingScan?.minusOne()) }
    fun setPendingAll() = _uiState.update { it.copy(pendingScan = it.pendingScan?.all()) }

    /** Closes the prompt without counting anything; the row keeps its timer. */
    fun cancelPendingScan() {
        pendingMatch = null
        _uiState.update { it.copy(pendingScan = null) }
    }

    /** Counts the confirmed quantity for the scanned row and syncs it with the label and elapsed time. */
    fun confirmPendingScan() {
        val state = _uiState.value
        val prompt = state.pendingScan ?: return
        val match = pendingMatch ?: return
        val pickList = state.pickList ?: return
        if (!prompt.isValid) return
        pendingMatch = null
        _uiState.update { it.copy(pendingScan = null) }
        applyScan(pickList, match, add = prompt.qty)
    }

    /** Sends every locally complete row the server has not marked Picked yet, then applies the last-picker rule. */
    fun completePicking() {
        val state = _uiState.value
        val pickList = state.pickList ?: return
        if (!state.canComplete) return
        val pending = state.lines.filter { it.item.rowStatus != PickRowStatus.PICKED }
        if (pending.isEmpty()) {
            _uiState.update { it.copy(outcome = initialOutcome(pickList) ?: PickOutcome.TASK_COMPLETED) }
            return
        }
        _uiState.update { it.copy(isCompleting = true, error = null, scanAlert = null) }
        viewModelScope.launch {
            var lastPicker = false
            for (line in pending) {
                when (val result = completeRow(pickList, line.item, line.qty, line.elapsedSeconds(clock()))) {
                    is AppResult.Success -> {
                        lastPicker = lastPicker || result.data.isLastPicker
                        _uiState.update { it.withUpdate(result.data) }
                    }
                    is AppResult.Failure -> {
                        _uiState.update { it.copy(isCompleting = false, error = result.error.toUiText()) }
                        return@launch
                    }
                }
            }
            _uiState.update {
                it.copy(
                    isCompleting = false,
                    outcome = if (lastPicker) PickOutcome.CARD_COMPLETED else PickOutcome.TASK_COMPLETED,
                    message = UiText.Res(if (lastPicker) R.string.pick_completed_message else R.string.pick_task_completed_message),
                )
            }
        }
    }

    /** Creates the draft Delivery Note / Stock Entry (the backend guarantees it happens once). */
    fun generateDocument() {
        val state = _uiState.value
        val pickList = state.pickList ?: return
        if (!state.canGenerate) return
        _uiState.update { it.copy(isGenerating = true, error = null, message = null) }
        viewModelScope.launch {
            when (val result = generatePickDocument(pickList)) {
                is AppResult.Success -> {
                    val doc = result.data
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            pickList = it.pickList?.copy(generatedDocument = doc),
                            message = UiText.Res(
                                if (doc.alreadyGenerated) R.string.pick_document_exists else R.string.pick_document_created,
                                listOf(doc.doctype, doc.name),
                            ),
                        )
                    }
                }
                is AppResult.Failure -> _uiState.update { it.copy(isGenerating = false, error = result.error.toUiText()) }
            }
        }
    }

    // ---- internals -------------------------------------------------------------------------

    private fun reject(alert: ScanAlert, highlight: String? = null) {
        scanner.feedback(beep = _uiState.value.beep, vibrate = true, error = true)
        _uiState.update { state ->
            state.copy(scanAlert = alert, message = null, lines = state.lines.map { it.copy(highlighted = it.item.rowName == highlight) })
        }
    }

    /** Opens the quantity prompt for a matching scan, prefilled with everything still open on its row. */
    private fun askQuantity(match: PickScanOutcome.Match) {
        val now = clock()
        val rowName = match.row.rowName
        pendingMatch = match
        _uiState.update { state ->
            val line = state.lines.firstOrNull { it.item.rowName == rowName }
            val remaining = line?.remaining ?: (match.row.requiredQty - match.row.pickedQty).coerceAtLeast(0.0)
            state.copy(
                activeRowName = rowName,
                scanAlert = null,
                error = null,
                message = null,
                pendingScan = ScanQuantityPrompt(
                    rowName = rowName,
                    itemCode = match.row.itemCode,
                    itemName = match.row.itemName,
                    batchNo = match.row.batchNo,
                    remaining = remaining,
                    uom = match.row.uom,
                ),
                lines = state.lines.map {
                    // The hidden timer starts with the scan, so the time spent in the prompt counts.
                    if (it.item.rowName == rowName) it.copy(startedAtMillis = it.startedAtMillis ?: now, highlighted = true) else it.copy(highlighted = false)
                },
            )
        }
    }

    private fun applyScan(pickList: PickList, match: PickScanOutcome.Match, add: Double) {
        val now = clock()
        val rowName = match.row.rowName
        var newQty = 0.0
        var added = 0.0
        var elapsed: Double? = null
        _uiState.update { state ->
            val updatedLines = state.lines.map { line ->
                if (line.item.rowName == rowName) {
                    val started = line.startedAtMillis ?: now
                    newQty = (line.qty + add).coerceAtMost(line.item.requiredQty)
                    added = newQty - line.qty
                    elapsed = ((now - started) / 1000.0).coerceAtLeast(0.0)
                    line.copy(qty = newQty, startedAtMillis = started, pendingSyncs = line.pendingSyncs + 1, highlighted = true)
                } else {
                    line.copy(highlighted = false)
                }
            }
            state.copy(
                activeRowName = rowName,
                scanAlert = null,
                error = null,
                message = UiText.Res(R.string.pick_qty_added, listOf(added.format(), match.row.itemCode)),
                lines = updatedLines,
            )
        }
        viewModelScope.launch {
            when (val result = saveRowProgress(pickList, match.row, newQty, match.label, elapsed)) {
                is AppResult.Success -> {
                    val update = result.data
                    _uiState.update { state ->
                        val merged = state.finishSync(rowName).withUpdate(update)
                        when {
                            update.isLastPicker -> merged.copy(outcome = PickOutcome.CARD_COMPLETED, message = UiText.Res(R.string.pick_completed_message))
                            merged.allMyRowsComplete && merged.outcome == null && update.pickList.isCardPicked -> merged.copy(outcome = PickOutcome.CARD_COMPLETED)
                            else -> merged
                        }
                    }
                }
                is AppResult.Failure -> _uiState.update { state ->
                    // Roll the local count back so the device never shows more than the server holds.
                    state.finishSync(rowName).let { s ->
                        s.copy(error = result.error.toUiText(), lines = s.lines.map { if (it.item.rowName == rowName && !it.syncing) it.copy(qty = it.item.pickedQty) else it })
                    }
                }
            }
        }
    }

    private fun startTimer(pickList: PickList, line: PickLineState) {
        val now = clock()
        val rowName = line.item.rowName
        _uiState.update { state ->
            state.copy(lines = state.lines.map { if (it.item.rowName == rowName) it.copy(startedAtMillis = now, pendingSyncs = it.pendingSyncs + 1) else it })
        }
        viewModelScope.launch {
            when (val result = startRow(pickList, line.item)) {
                is AppResult.Success -> _uiState.update { it.finishSync(rowName).withUpdate(result.data) }
                is AppResult.Failure -> _uiState.update { it.finishSync(rowName).copy(error = result.error.toUiText()) }
            }
        }
    }

    private fun PickListUiState.finishSync(rowName: String): PickListUiState =
        copy(lines = lines.map { if (it.item.rowName == rowName) it.copy(pendingSyncs = (it.pendingSyncs - 1).coerceAtLeast(0)) else it })

    /** Server document -> UI state, keeping device-side timers and counts that are still ahead of the server. */
    private fun PickListUiState.withPickList(fresh: PickList): PickListUiState {
        val previous = lines.associateBy { it.item.rowName }
        val merged = fresh.myItems.map { item ->
            val old = previous[item.rowName]
            PickLineState(
                item = item,
                qty = if (old != null && old.syncing) maxOf(item.pickedQty, old.qty) else item.pickedQty,
                startedAtMillis = old?.startedAtMillis ?: if (item.rowStatus == PickRowStatus.PICKING) clock() else null,
                pendingSyncs = old?.pendingSyncs ?: 0,
                highlighted = old?.highlighted ?: false,
            )
        }
        val active = activeRowName?.takeIf { name -> merged.any { it.item.rowName == name && !it.isComplete } }
            ?: merged.firstOrNull { !it.isComplete }?.item?.rowName
        return copy(pickList = fresh, lines = merged, activeRowName = active)
    }

    private fun PickListUiState.withUpdate(update: RowUpdate): PickListUiState = withPickList(update.pickList)

    /** A card already picked (or already documented) when opened shows its final state directly. */
    private fun initialOutcome(pickList: PickList): PickOutcome? = when {
        pickList.generatedDocument != null || pickList.isCardPicked -> PickOutcome.CARD_COMPLETED
        pickList.myRowCount > 0 && pickList.myOpenRows == 0 -> PickOutcome.TASK_COMPLETED
        else -> null
    }

    companion object {
        const val ARG_NAME = "pickListName"
    }
}
