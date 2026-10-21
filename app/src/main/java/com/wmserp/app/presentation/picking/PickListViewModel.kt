package com.wmserp.app.presentation.picking

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmserp.app.R
import com.wmserp.app.core.scanner.ScannerController
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.common.ServerErrorTypes
import com.wmserp.app.domain.model.GeneratedDocument
import com.wmserp.app.domain.model.PickList
import com.wmserp.app.domain.model.PICK_QTY_TOLERANCE
import com.wmserp.app.domain.model.PickListItem
import com.wmserp.app.domain.model.PickRowStatus
import com.wmserp.app.domain.model.PickingStatus
import com.wmserp.app.domain.model.RequiredField
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
import com.wmserp.app.domain.usecase.ResetPickRowUseCase
import com.wmserp.app.domain.usecase.SaveDocumentFieldDefaultsUseCase
import com.wmserp.app.domain.usecase.SavePickRowProgressUseCase
import com.wmserp.app.domain.usecase.SearchLinkValuesUseCase
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

    /** The server marked the row Picked (final confirmation given); only a reset reopens it. */
    val confirmed: Boolean get() = item.rowStatus == PickRowStatus.PICKED

    /** Counted in full on the device but not confirmed yet: still editable. */
    val ready: Boolean get() = !confirmed && isComplete
    val remaining: Double get() = (item.requiredQty - qty).coerceAtLeast(0.0)
    val status: PickRowStatus
        get() = when {
            isComplete -> PickRowStatus.PICKED
            qty > 0.0 || item.rowStatus == PickRowStatus.PICKING || startedAtMillis != null -> PickRowStatus.PICKING
            else -> PickRowStatus.NOT_PICKED
        }

    fun elapsedSeconds(now: Long): Double? = startedAtMillis?.let { ((now - it) / 1000.0).coerceAtLeast(0.0) }
}

enum class ScanAlertKind { WRONG_BATCH, NOT_ASSIGNED, ALREADY_COMPLETE, INVALID_QR, NO_STOCK }

/** Large red error shown after a rejected scan. */
data class ScanAlert(val kind: ScanAlertKind, val message: UiText, val expected: String? = null, val scanned: String? = null)

/** What the screen shows once every row of the picker is done. */
enum class PickOutcome {
    /** This picker completed the last row of the card (or the card is already picked): show the document CTA. */
    CARD_COMPLETED,

    /** The picker's rows are done but other pickers are still working on the card. */
    TASK_COMPLETED,
}

/** The app-side workflow of a pick list: Draft -> Picking in progress -> Final confirmation. */
enum class PickPhase { DRAFT, IN_PROGRESS, CONFIRMED }

/** A row opened for correction before the final confirmation: the quantity can be changed, or the pick removed. */
data class PickLineEdit(
    val rowName: String,
    val itemCode: String,
    val itemName: String,
    val batchNo: String?,
    val batchFromWms: Boolean,
    val required: Double,
    val current: Double,
    val uom: String?,
    /** Already confirmed on the server: only removing the pick (a reset) is possible. */
    val confirmed: Boolean,
    val qtyText: String = ScanQuantityPrompt.formatQty(current),
) {
    val qty: Double get() = qtyText.trim().replace(',', '.').toDoubleOrNull() ?: -1.0
    val isValid: Boolean get() = qty >= 0.0 && qty <= required + PICK_QTY_TOLERANCE
    val changed: Boolean get() = isValid && kotlin.math.abs(qty - current) > PICK_QTY_TOLERANCE

    fun withText(text: String): PickLineEdit = copy(qtyText = text)
    fun plusOne(): PickLineEdit = copy(qtyText = ScanQuantityPrompt.formatQty((qty.coerceAtLeast(0.0) + 1.0).coerceAtMost(required)))
    fun minusOne(): PickLineEdit = copy(qtyText = ScanQuantityPrompt.formatQty((qty - 1.0).coerceAtLeast(0.0)))
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
    /** Required fields the ERPNext site added to the target document that still need a value (dialog while non-empty). */
    val requiredFields: List<RequiredField> = emptyList(),
    val requiredFieldAnswers: Map<String, String> = emptyMap(),
    /** Possible values of the required Link fields, keyed by [RequiredField.key]. */
    val linkOptions: Map<String, List<String>> = emptyMap(),
    /** The row being corrected (dialog), if any. */
    val editLine: PickLineEdit? = null,
    val isResetting: Boolean = false,
) {
    val cardStatus: PickingStatus? get() = pickList?.pickingStatus

    /** Draft until something is picked, then in progress, confirmed once the picker's rows are final. */
    val phase: PickPhase
        get() = when {
            outcome != null || pickList?.isCardPicked == true -> PickPhase.CONFIRMED
            lines.any { it.confirmed || it.qty > PICK_QTY_TOLERANCE || it.item.rowStatus == PickRowStatus.PICKING } -> PickPhase.IN_PROGRESS
            else -> PickPhase.DRAFT
        }
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

    /** Scanning is accepted while the picker still has open rows and no prompt or correction dialog is waiting. */
    val canScan: Boolean get() = pickList != null && outcome == null && !allMyRowsComplete && !isCompleting && !isGenerating && pendingScan == null && editLine == null && !isResetting

    /** Final confirmation: every row counted in full, nothing in flight, no dialog open. */
    val canComplete: Boolean get() = pickList != null && outcome == null && allMyRowsComplete && !isCompleting && !isGenerating && !isSyncing && editLine == null && !isResetting
    val canGenerate: Boolean
        get() = !isGenerating && !isCompleting && outcome == PickOutcome.CARD_COMPLETED && pickList?.purpose?.targetDocument != null && generatedDocument == null
}

/**
 * Drives the picker's rows of one Pick List. Scans are strictly validated JSON QR labels: a match
 * opens a quantity prompt prefilled with everything still open on the row (or adds one unit when
 * that setting is off) and the confirmed quantity syncs immediately as a *held* partial save, so
 * the server validates item, batch and stock but keeps the row editable. Until the final
 * confirmation a row can be corrected (quantity) or its pick removed (quantity and scanned batch
 * cleared). *Final confirmation* marks the rows Picked on the server; when that closes the whole
 * card the draft document is created right away.
 */
@HiltViewModel
class PickListViewModel @Inject constructor(
    private val getPickList: GetPickListUseCase,
    private val getQrKeys: GetWmsQrKeysUseCase,
    private val startRow: StartPickRowUseCase,
    private val saveRowProgress: SavePickRowProgressUseCase,
    private val completeRow: CompletePickRowUseCase,
    private val resetRow: ResetPickRowUseCase,
    private val generatePickDocument: GeneratePickDocumentUseCase,
    private val searchLinkValues: SearchLinkValuesUseCase,
    private val saveFieldDefaults: SaveDocumentFieldDefaultsUseCase,
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

    /**
     * Final confirmation: marks every row the server has not confirmed yet as Picked, applies the
     * last-picker rule and, for the picker who closed the card, creates the document at once.
     */
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
            if (lastPicker) generateDocument()
        }
    }

    // ---- corrections before the final confirmation ------------------------------------------

    /** Opens the correction dialog for one of the picker's rows. */
    fun openEdit(rowName: String) {
        val state = _uiState.value
        if (state.pickList == null || state.outcome != null || state.isCompleting || state.isGenerating || state.isResetting) return
        val line = state.lines.firstOrNull { it.item.rowName == rowName } ?: return
        if (line.syncing) return
        pendingMatch = null
        _uiState.update {
            it.copy(
                pendingScan = null,
                scanAlert = null,
                error = null,
                activeRowName = rowName,
                editLine = PickLineEdit(
                    rowName = rowName,
                    itemCode = line.item.itemCode,
                    itemName = line.item.itemName,
                    batchNo = line.item.batchNo,
                    batchFromWms = line.item.batchFromWms,
                    required = line.item.requiredQty,
                    current = line.qty,
                    uom = line.item.uom,
                    confirmed = line.confirmed,
                ),
            )
        }
    }

    fun setEditQty(text: String) = _uiState.update { it.copy(editLine = it.editLine?.withText(text)) }
    fun editPlusOne() = _uiState.update { it.copy(editLine = it.editLine?.plusOne()) }
    fun editMinusOne() = _uiState.update { it.copy(editLine = it.editLine?.minusOne()) }
    fun cancelEdit() = _uiState.update { it.copy(editLine = null) }

    /** Saves the corrected quantity (held on the server like a scan; the row's batch is kept). */
    fun confirmEdit() {
        val state = _uiState.value
        val edit = state.editLine ?: return
        val pickList = state.pickList ?: return
        val line = state.lines.firstOrNull { it.item.rowName == edit.rowName } ?: return
        if (!edit.isValid || edit.confirmed) return
        if (!edit.changed) {
            cancelEdit()
            return
        }
        val now = clock()
        val newQty = edit.qty
        val elapsed = line.elapsedSeconds(now)
        _uiState.update { s ->
            s.copy(
                editLine = null,
                error = null,
                scanAlert = null,
                message = null,
                activeRowName = edit.rowName,
                lines = s.lines.map {
                    if (it.item.rowName == edit.rowName) it.copy(qty = newQty, startedAtMillis = it.startedAtMillis ?: now, pendingSyncs = it.pendingSyncs + 1, highlighted = true) else it.copy(highlighted = false)
                },
            )
        }
        viewModelScope.launch {
            when (val result = saveRowProgress(pickList, line.item, newQty, null, elapsed)) {
                is AppResult.Success -> _uiState.update { s ->
                    s.finishSync(edit.rowName).withUpdate(result.data).copy(message = UiText.Res(R.string.pick_qty_updated, listOf(edit.itemCode, newQty.format())))
                }
                is AppResult.Failure -> _uiState.update { s ->
                    s.finishSync(edit.rowName).let { m ->
                        m.copy(error = result.error.toUiText(), lines = m.lines.map { if (it.item.rowName == edit.rowName && !it.syncing) it.copy(qty = it.item.pickedQty) else it })
                    }
                }
            }
        }
    }

    /** Removes the pick of the row in the dialog: quantity, timers and a batch assigned by scan are cleared on the server. */
    fun removePick() {
        val state = _uiState.value
        val edit = state.editLine ?: return
        val pickList = state.pickList ?: return
        val line = state.lines.firstOrNull { it.item.rowName == edit.rowName } ?: return
        _uiState.update { s ->
            s.copy(
                editLine = null,
                isResetting = true,
                error = null,
                scanAlert = null,
                message = null,
                lines = s.lines.map { if (it.item.rowName == edit.rowName) it.copy(pendingSyncs = it.pendingSyncs + 1) else it },
            )
        }
        viewModelScope.launch {
            when (val result = resetRow(pickList, line.item)) {
                is AppResult.Success -> _uiState.update { s ->
                    val merged = s.finishSync(edit.rowName).withUpdate(result.data)
                    merged.copy(
                        isResetting = false,
                        activeRowName = edit.rowName,
                        lines = merged.lines.map { if (it.item.rowName == edit.rowName) it.copy(qty = 0.0, startedAtMillis = null, highlighted = true) else it.copy(highlighted = false) },
                        message = UiText.Res(R.string.pick_removed, listOf(edit.itemCode)),
                    )
                }
                is AppResult.Failure -> _uiState.update { it.finishSync(edit.rowName).copy(isResetting = false, error = result.error.toUiText()) }
            }
        }
    }

    /**
     * Creates the draft Delivery Note / Stock Entry (the backend guarantees it happens once). When
     * the site requires fields the order does not carry (a Department...), the dialog asks for
     * them once and the call is retried with the answers.
     */
    fun generateDocument() = generateDocument(fieldValues = emptyMap())

    private fun generateDocument(fieldValues: Map<String, String>) {
        val state = _uiState.value
        val pickList = state.pickList ?: return
        if (!state.canGenerate) return
        _uiState.update { it.copy(isGenerating = true, error = null, message = null) }
        viewModelScope.launch {
            when (val result = generatePickDocument(pickList, fieldValues)) {
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
                is AppResult.Failure -> {
                    val error = result.error
                    if (error is AppError.MissingRequiredFields) {
                        askRequiredFields(error.fields, pickList.company)
                    } else {
                        _uiState.update { it.copy(isGenerating = false, error = error.toUiText()) }
                    }
                }
            }
        }
    }

    /** ERPNext needs values the pick list does not carry: open the dialog and load the choices of its Link fields. */
    private fun askRequiredFields(fields: List<RequiredField>, company: String?) {
        _uiState.update { state ->
            state.copy(
                isGenerating = false,
                requiredFields = fields,
                requiredFieldAnswers = fields.associate { it.key to state.requiredFieldAnswers[it.key].orEmpty() },
                linkOptions = emptyMap(),
            )
        }
        viewModelScope.launch {
            fields.filter { it.isLink }.forEach { field ->
                val target = field.options ?: return@forEach
                searchLinkValues(target, "", company).getOrNull()?.let { names ->
                    _uiState.update { it.copy(linkOptions = it.linkOptions + (field.key to names)) }
                }
            }
        }
    }

    fun setRequiredFieldAnswer(key: String, value: String) =
        _uiState.update { it.copy(requiredFieldAnswers = it.requiredFieldAnswers + (key to value)) }

    fun dismissRequiredFields() = _uiState.update { it.copy(requiredFields = emptyList()) }

    /** Saves the answers for the next documents and retries the document with them. */
    fun confirmRequiredFields() {
        val state = _uiState.value
        val answers = state.requiredFields.associate { it.key to state.requiredFieldAnswers[it.key].orEmpty().trim() }
        if (answers.isEmpty() || answers.values.any { it.isBlank() }) return
        _uiState.update { it.copy(requiredFields = emptyList()) }
        viewModelScope.launch {
            saveFieldDefaults(answers)
            generateDocument(answers)
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
                is AppResult.Failure -> {
                    val error = result.error
                    _uiState.update { state ->
                        // Roll the local count back so the device never shows more than the server holds.
                        state.finishSync(rowName).let { s ->
                            s.copy(error = if (error.isInsufficientStock) null else error.toUiText(), lines = s.lines.map { if (it.item.rowName == rowName && !it.syncing) it.copy(qty = it.item.pickedQty) else it })
                        }
                    }
                    // No stock for that batch in the row's warehouse: the same large red alert as a wrong batch.
                    if (error.isInsufficientStock) {
                        reject(ScanAlert(ScanAlertKind.NO_STOCK, UiText.Plain(error.message), scanned = match.label.batchNo), highlight = rowName)
                    }
                }
            }
        }
    }

    private val AppError.isInsufficientStock: Boolean
        get() = this is AppError.Validation && serverType == ServerErrorTypes.INSUFFICIENT_STOCK

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
