package com.wmserp.app.presentation.orders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmserp.app.R
import com.wmserp.app.core.scanner.ScannerController
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.PurchaseReceipt
import com.wmserp.app.domain.model.PurchaseReceiptItem
import com.wmserp.app.domain.model.ReceiptDifference
import com.wmserp.app.domain.model.ReceiveResult
import com.wmserp.app.domain.model.RequiredField
import com.wmserp.app.domain.model.ScanLookup
import com.wmserp.app.domain.model.ScanSource
import com.wmserp.app.domain.model.ScanTarget
import com.wmserp.app.domain.model.ScannedCode
import com.wmserp.app.domain.model.ScannerMode
import com.wmserp.app.domain.model.Warehouse
import com.wmserp.app.domain.usecase.GetPurchaseReceiptUseCase
import com.wmserp.app.domain.usecase.LookupScanUseCase
import com.wmserp.app.domain.usecase.ObserveScannerSettingsUseCase
import com.wmserp.app.domain.usecase.ReceiveLine
import com.wmserp.app.domain.usecase.ReceivePurchaseReceiptUseCase
import com.wmserp.app.domain.usecase.SaveDocumentFieldDefaultsUseCase
import com.wmserp.app.domain.usecase.ScanCodeSanitizer
import com.wmserp.app.domain.usecase.SearchLinkValuesUseCase
import com.wmserp.app.domain.usecase.SearchWarehousesUseCase
import com.wmserp.app.presentation.common.ScanQuantityPrompt
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.presentation.common.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row of the draft receipt with what the warehouse counted so far. */
data class ReceiveLineState(
    val item: PurchaseReceiptItem,
    val qtyText: String = "0",
    /** Batch scanned or typed for a batch tracked row (the draft's own batch when it has one). */
    val batchNo: String = item.batchNo.orEmpty(),
    val highlighted: Boolean = false,
) {
    val qty: Double get() = qtyText.replace(',', '.').toDoubleOrNull() ?: 0.0

    /** Still open against the draft row (expected minus counted); never negative. */
    val remaining: Double get() = (item.qty - qty).coerceAtLeast(0.0)

    /** Batch tracked row that still has no batch: ERPNext cannot submit it. */
    val batchMissing: Boolean get() = item.needsBatch && batchNo.isBlank()
}

/** Confirmation asked before a receipt whose counts differ from the draft is confirmed. */
data class ReceiveConfirmation(val differences: List<ReceiptDifference>)

data class ReceiveUiState(
    val isLoading: Boolean = true,
    val receipt: PurchaseReceipt? = null,
    val lines: List<ReceiveLineState> = emptyList(),
    /** Warehouse every counted row goes to; blank keeps the warehouse of each row. */
    val warehouse: String = "",
    val warehouses: List<Warehouse> = emptyList(),
    val isSubmitting: Boolean = false,
    val error: UiText? = null,
    val message: UiText? = null,
    /** Set once the receipt was saved or confirmed; the screen then shows the outcome. */
    val completed: ReceiveResult? = null,
    val confirmation: ReceiveConfirmation? = null,
    /** Required fields the ERPNext site added that still need a value; the dialog shows while non-empty. */
    val requiredFields: List<RequiredField> = emptyList(),
    val requiredFieldAnswers: Map<String, String> = emptyMap(),
    /** Possible values of the required Link fields, keyed by [RequiredField.key]. */
    val linkOptions: Map<String, List<String>> = emptyMap(),
    val beep: Boolean = true,
    val vibrate: Boolean = true,
    val cameraActive: Boolean = false,
    val hasHardwareScanner: Boolean = false,
    val scannerMode: ScannerMode = ScannerMode.AUTO,
    /** A matching scan opens the quantity prompt (settings); off, every scan adds one unit. */
    val askQuantity: Boolean = true,
    /** The matching scan waiting for its quantity; scans are ignored while it is open. */
    val pendingScan: ScanQuantityPrompt? = null,
) {
    /** The trigger of the built-in scanner is the primary input; the camera is only offered where it is needed. */
    val hardwareScannerReady: Boolean get() = hasHardwareScanner && scannerMode != ScannerMode.CAMERA
    val showCameraButton: Boolean get() = !hardwareScannerReady
    val totalQty: Double get() = lines.sumOf { it.qty }
    val expectedQty: Double get() = lines.sumOf { it.item.qty }

    /** The receipt is a draft at the user's workflow stage and the user may edit it. */
    val canReceive: Boolean get() = receipt != null && receipt.isDraft && receipt.canReceive
    val canSubmit: Boolean get() = !isSubmitting && canReceive && totalQty > 0 && completed == null && confirmation == null
    val isBusy: Boolean get() = isSubmitting || pendingScan != null || confirmation != null || requiredFields.isNotEmpty()
}

/**
 * Receiving against a draft Purchase Receipt: the warehouse counts the goods (scan or type) against
 * the rows purchasing prepared, enters the batch of batch tracked rows, and confirms. Confirming
 * saves the counts, drops the rows that were not received and submits the receipt through the
 * site's workflow, which puts the stock in. "Save progress" only stores the counts so far.
 */
@HiltViewModel
class ReceiveViewModel @Inject constructor(
    private val getPurchaseReceipt: GetPurchaseReceiptUseCase,
    private val receivePurchaseReceipt: ReceivePurchaseReceiptUseCase,
    private val searchLinkValues: SearchLinkValuesUseCase,
    private val saveFieldDefaults: SaveDocumentFieldDefaultsUseCase,
    private val lookupScan: LookupScanUseCase,
    private val searchWarehouses: SearchWarehousesUseCase,
    observeScannerSettings: ObserveScannerSettingsUseCase,
    private val scanner: ScannerController,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val receiptName: String = savedStateHandle.get<String>(ARG_RECEIPT_NAME).orEmpty()

    private val _uiState = MutableStateFlow(ReceiveUiState(hasHardwareScanner = scanner.hasHardwareScanner))
    val uiState: StateFlow<ReceiveUiState> = _uiState.asStateFlow()

    /** Set after the first settings emission: later ones must not override a manual camera toggle. */
    private var settingsApplied = false
    private var pendingSubmitAsDraft = false

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
        load()
    }

    fun toggleCamera() = _uiState.update { it.copy(cameraActive = !it.cameraActive) }

    fun load() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = getPurchaseReceipt(receiptName)) {
                is AppResult.Success -> {
                    val receipt = result.data
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            receipt = receipt,
                            lines = receipt.items.map { item -> ReceiveLineState(item) },
                            warehouse = it.warehouse.ifBlank { defaultWarehouse(receipt) },
                            error = if (receipt.isDraft && !receipt.canReceive) UiText.Res(R.string.receive_not_receivable) else null,
                        )
                    }
                    val warehouses = searchWarehouses("").getOrNull().orEmpty()
                    _uiState.update { it.copy(warehouses = warehouses) }
                }
                is AppResult.Failure -> _uiState.update { it.copy(isLoading = false, error = result.error.toUiText()) }
            }
        }
    }

    /** The receipt's accepted warehouse, else the one warehouse all rows share; blank when rows differ. */
    private fun defaultWarehouse(receipt: PurchaseReceipt): String {
        receipt.setWarehouse?.takeIf { it.isNotBlank() }?.let { return it }
        val rowWarehouses = receipt.items.mapNotNull { it.warehouse?.takeIf { w -> w.isNotBlank() } }.distinct()
        return rowWarehouses.singleOrNull().orEmpty()
    }

    fun setWarehouse(value: String) = _uiState.update { it.copy(warehouse = value) }

    fun setQty(rowName: String, text: String) = _uiState.update { state ->
        state.copy(lines = state.lines.map { if (it.item.rowName == rowName) it.copy(qtyText = text, highlighted = false) else it })
    }

    fun setBatch(rowName: String, text: String) = _uiState.update { state ->
        state.copy(lines = state.lines.map { if (it.item.rowName == rowName) it.copy(batchNo = text) else it })
    }

    fun increment(rowName: String) = adjust(rowName, +1.0)
    fun decrement(rowName: String) = adjust(rowName, -1.0)

    /** Takes every row as the draft expects it. */
    fun receiveAll() = _uiState.update { state ->
        state.copy(lines = state.lines.map { it.copy(qtyText = it.item.qty.format(), highlighted = false) }, message = null)
    }

    fun clearAll() = _uiState.update { state -> state.copy(lines = state.lines.map { it.copy(qtyText = "0", highlighted = false) }) }

    fun dismissMessage() = _uiState.update { it.copy(message = null, error = null) }

    /**
     * A scan counts the matching row: an item barcode or code finds the row of that item; a batch
     * label (WMS QR with a batch, or a plain batch number) also fills the batch of the row.
     */
    fun onScanned(code: ScannedCode) {
        val state = _uiState.value
        val receipt = state.receipt ?: return
        if (state.completed != null || state.isBusy || !state.canReceive) return
        if (code.source != ScanSource.MANUAL) scanner.feedback(state.beep, state.vibrate)
        val value = ScanCodeSanitizer.sanitize(code.value)
        if (value.isEmpty()) return
        val direct = state.lines.indexOfFirst { it.item.matches(value) }
        if (direct >= 0) {
            countScan(direct)
            return
        }
        viewModelScope.launch {
            when (val result = lookupScan(value, ScanTarget.ITEM)) {
                is AppResult.Success -> when (val lookup = result.data) {
                    is ScanLookup.ItemFound -> countItem(lookup.item.code, batchNo = null, receipt = receipt)
                    is ScanLookup.BatchFound -> {
                        val itemCode = lookup.item?.code ?: lookup.batch.itemCode
                        countItem(itemCode, batchNo = lookup.batch.name, receipt = receipt)
                    }
                    else -> _uiState.update { it.copy(error = UiText.Res(R.string.scan_item_not_found, listOf(value))) }
                }
                is AppResult.Failure -> _uiState.update { it.copy(error = result.error.toUiText()) }
            }
        }
    }

    /** Finds the open row of [itemCode] (an open one first), fills its batch when scanned, then counts it. */
    private fun countItem(itemCode: String, batchNo: String?, receipt: PurchaseReceipt) {
        val lines = _uiState.value.lines
        val candidates = lines.withIndex().filter { it.value.item.itemCode.equals(itemCode, ignoreCase = true) }
        if (candidates.isEmpty()) {
            _uiState.update { it.copy(error = UiText.Res(R.string.receive_not_on_receipt, listOf(itemCode, receipt.name))) }
            return
        }
        val target = candidates.firstOrNull { batchNo != null && it.value.batchNo.equals(batchNo, ignoreCase = true) }
            ?: candidates.firstOrNull { batchNo != null && it.value.item.hasBatchNo && it.value.batchNo.isBlank() }
            ?: candidates.firstOrNull { it.value.remaining > 1e-9 }
            ?: candidates.first()
        if (batchNo != null && target.value.item.hasBatchNo) {
            _uiState.update { state ->
                state.copy(lines = state.lines.mapIndexed { i, line -> if (i == target.index) line.copy(batchNo = batchNo) else line })
            }
        }
        countScan(target.index)
    }

    fun submit(asDraft: Boolean) {
        val state = _uiState.value
        val receipt = state.receipt ?: return
        if (!state.canSubmit) return
        if (!asDraft) {
            val differences = ReceivePurchaseReceiptUseCase.differences(receipt, state.lines.map { it.toLine(state.warehouse) })
            if (differences.isNotEmpty()) {
                _uiState.update { it.copy(confirmation = ReceiveConfirmation(differences), error = null, message = null) }
                return
            }
        }
        perform(asDraft, fieldValues = emptyMap())
    }

    /** The warehouse accepts that the counts differ from the draft: rows counted 0 are dropped. */
    fun confirmDifferences() {
        if (_uiState.value.confirmation == null) return
        _uiState.update { it.copy(confirmation = null) }
        perform(asDraft = false, fieldValues = emptyMap())
    }

    fun cancelConfirmation() = _uiState.update { it.copy(confirmation = null) }

    private fun perform(asDraft: Boolean, fieldValues: Map<String, String>) {
        val state = _uiState.value
        val receipt = state.receipt ?: return
        pendingSubmitAsDraft = asDraft
        _uiState.update { it.copy(isSubmitting = true, error = null, message = null) }
        viewModelScope.launch {
            val result = receivePurchaseReceipt(
                receipt = receipt,
                lines = state.lines.map { it.toLine(state.warehouse) },
                defaultWarehouse = state.warehouse.ifBlank { null },
                submit = !asDraft,
                fieldValues = fieldValues,
            )
            when (result) {
                is AppResult.Success -> {
                    val outcome = result.data
                    val message = when {
                        asDraft -> UiText.Res(R.string.receive_progress_saved, listOf(outcome.receipt.name))
                        outcome.submitted -> UiText.Res(R.string.receive_submitted, listOf(outcome.receipt.name))
                        else -> UiText.Res(R.string.receive_pending_approval, listOf(outcome.receipt.name))
                    }
                    _uiState.update { it.copy(isSubmitting = false, completed = outcome, receipt = outcome.receipt, message = message) }
                }
                is AppResult.Failure -> {
                    val error = result.error
                    if (error is AppError.MissingRequiredFields) {
                        askRequiredFields(error.fields, receipt.company)
                    } else {
                        _uiState.update { it.copy(isSubmitting = false, error = error.toUiText()) }
                    }
                }
            }
        }
    }

    /** ERPNext needs values the receipt does not carry: open the dialog and load the choices of its Link fields. */
    private fun askRequiredFields(fields: List<RequiredField>, company: String?) {
        _uiState.update { state ->
            state.copy(
                isSubmitting = false,
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

    /** Saves the answers for the next documents and retries with them. */
    fun confirmRequiredFields() {
        val state = _uiState.value
        val answers = state.requiredFields.associate { it.key to state.requiredFieldAnswers[it.key].orEmpty().trim() }
        if (answers.isEmpty() || answers.values.any { it.isBlank() }) return
        _uiState.update { it.copy(requiredFields = emptyList()) }
        viewModelScope.launch {
            saveFieldDefaults(answers)
            perform(pendingSubmitAsDraft, answers)
        }
    }

    /** A matching scan: open the quantity prompt (prefilled with what is still open) or add one unit. */
    private fun countScan(index: Int) {
        val state = _uiState.value
        val line = state.lines.getOrNull(index) ?: return
        val remaining = line.remaining
        if (remaining <= 1e-9) {
            _uiState.update { s ->
                s.copy(
                    message = UiText.Res(R.string.receive_already_counted, listOf(line.item.qty.format(), line.item.itemCode)),
                    lines = s.lines.mapIndexed { i, l -> l.copy(highlighted = i == index) },
                )
            }
            return
        }
        if (!state.askQuantity) {
            addOne(index)
            return
        }
        _uiState.update { s ->
            s.copy(
                error = null,
                message = null,
                pendingScan = ScanQuantityPrompt(
                    rowName = line.item.rowName,
                    itemCode = line.item.itemCode,
                    itemName = line.item.itemName,
                    batchNo = line.batchNo.ifBlank { null },
                    remaining = remaining,
                    uom = line.item.uom,
                ),
                lines = s.lines.mapIndexed { i, l -> l.copy(highlighted = i == index) },
            )
        }
    }

    fun setPendingQty(text: String) = _uiState.update { it.copy(pendingScan = it.pendingScan?.withText(text)) }
    fun incrementPendingQty() = _uiState.update { it.copy(pendingScan = it.pendingScan?.plusOne()) }
    fun decrementPendingQty() = _uiState.update { it.copy(pendingScan = it.pendingScan?.minusOne()) }
    fun setPendingAll() = _uiState.update { it.copy(pendingScan = it.pendingScan?.all()) }
    fun cancelPendingScan() = _uiState.update { it.copy(pendingScan = null) }

    /** Adds the confirmed quantity to the scanned line (never beyond what the draft expects). */
    fun confirmPendingScan() {
        val prompt = _uiState.value.pendingScan ?: return
        if (!prompt.isValid) return
        _uiState.update { state ->
            state.copy(
                pendingScan = null,
                error = null,
                message = UiText.Res(R.string.receive_qty_added, listOf(prompt.qty.format(), prompt.itemCode)),
                lines = state.lines.map { l ->
                    if (l.item.rowName == prompt.rowName) l.copy(qtyText = (l.qty + prompt.qty).coerceAtMost(l.item.qty).format(), highlighted = true) else l.copy(highlighted = false)
                },
            )
        }
    }

    private fun addOne(index: Int) {
        _uiState.update { state ->
            val line = state.lines[index]
            state.copy(
                message = UiText.Res(R.string.receive_qty_added, listOf("1", line.item.itemCode)),
                error = null,
                lines = state.lines.mapIndexed { i, l -> if (i == index) l.copy(qtyText = (l.qty + 1).coerceAtMost(l.item.qty).format(), highlighted = true) else l.copy(highlighted = false) },
            )
        }
    }

    /** The stepper has no upper bound: over-receipt is possible but shown in the confirmation before submitting. */
    private fun adjust(rowName: String, delta: Double) = _uiState.update { state ->
        state.copy(
            lines = state.lines.map {
                if (it.item.rowName == rowName) it.copy(qtyText = (it.qty + delta).coerceAtLeast(0.0).format(), highlighted = false) else it
            }
        )
    }

    private fun ReceiveLineState.toLine(warehouse: String): ReceiveLine =
        ReceiveLine(rowName = item.rowName, qty = qty, warehouse = warehouse.ifBlank { null }, batchNo = batchNo.ifBlank { null })

    companion object {
        const val ARG_RECEIPT_NAME = "receiptName"
    }
}

internal fun Double.format(): String =
    if (this == Math.floor(this) && !isInfinite()) toLong().toString() else String.format(java.util.Locale.US, "%.3f", this).trimEnd('0').trimEnd('.')
