package com.wmserp.app.presentation.orders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmserp.app.R
import com.wmserp.app.core.scanner.ScannerController
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.Batch
import com.wmserp.app.domain.model.BatchCount
import com.wmserp.app.domain.model.NewBatch
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
import com.wmserp.app.domain.usecase.CreateBatchUseCase
import com.wmserp.app.domain.usecase.FindBatchUseCase
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
import java.time.LocalDate
import javax.inject.Inject

/** One batch counted under a batch tracked row: the ERPNext batch, what its label says, and the quantity so far. */
data class BatchLineState(
    val batchNo: String,
    val label: String = batchNo,
    val qtyText: String = "0",
    val expiryDate: String? = null,
    /** Created from the app during this count (shown so the operator knows ERPNext did not have it). */
    val isNew: Boolean = false,
) {
    val qty: Double get() = qtyText.replace(',', '.').toDoubleOrNull() ?: 0.0
}

/** One row of the draft receipt with what the warehouse counted so far (a saved count continues where it stopped). */
data class ReceiveLineState(
    val item: PurchaseReceiptItem,
    val qtyText: String = if (item.countedQty > 0.0) item.countedQty.format() else "0",
    /** The batches counted under a batch tracked row; their quantities add up to the row's count. */
    val batches: List<BatchLineState> = initialBatches(item),
    val highlighted: Boolean = false,
) {
    val qty: Double get() = if (batches.isNotEmpty()) batches.sumOf { it.qty } else qtyText.replace(',', '.').toDoubleOrNull() ?: 0.0

    /** Still open against the draft row (expected minus counted); never negative. */
    val remaining: Double get() = (item.qty - qty).coerceAtLeast(0.0)

    /** The quantity is entered per batch (batch lines) instead of on the row. */
    val countsByBatch: Boolean get() = item.hasBatchNo && (batches.isNotEmpty() || item.needsBatch)

    /** Batch tracked row that still has no batch: ERPNext cannot submit it. */
    val batchMissing: Boolean get() = item.needsBatch && batches.isEmpty()

    /** The ERPNext batch of the first line, kept for sites that count one batch per row. */
    val batchNo: String get() = batches.firstOrNull()?.batchNo.orEmpty()

    companion object {
        fun initialBatches(item: PurchaseReceiptItem): List<BatchLineState> = when {
            item.countedBatches.isNotEmpty() -> item.countedBatches.map { BatchLineState(it.batchNo, it.label, it.qty.format()) }
            !item.batchNo.isNullOrBlank() -> listOf(BatchLineState(item.batchNo, item.batchLabel ?: item.batchNo, if (item.countedQty > 0.0) item.countedQty.format() else "0"))
            else -> emptyList()
        }
    }
}

/** One row of the summary shown before the receipt is confirmed. */
data class ReceiveSummaryLine(val itemCode: String, val itemName: String, val expected: Double, val counted: Double, val uom: String?, val batches: List<BatchCount>)

/** Confirmation asked before a receipt is confirmed: every row with its batches, differences from the draft marked. */
data class ReceiveConfirmation(val differences: List<ReceiptDifference>, val summary: List<ReceiveSummaryLine> = emptyList())

/**
 * The sheet that adds a batch under a batch tracked row: the operator scans or types the number
 * printed on the material, the app finds the ERPNext batch (several when the site keeps one per QC
 * run) or offers to create it with its manufacturing and expiry dates.
 */
data class BatchSheetState(
    /** The row the batch is for; null while the operator has to pick one of [candidates]. */
    val rowName: String?,
    /** Rows the scanned number may belong to, when the scan did not say which item. */
    val candidates: List<String> = emptyList(),
    val code: String = "",
    val isBusy: Boolean = false,
    /** The lookup for [code] ran: [matches] has the batches, empty means the batch is new. */
    val lookedUp: Boolean = false,
    val matches: List<Batch> = emptyList(),
    val manufacturingDate: String = LocalDate.now().toString(),
    val expiryDate: String = "",
    val error: UiText? = null,
) {
    val isNew: Boolean get() = lookedUp && matches.isEmpty()
}

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
    val batchSheet: BatchSheetState? = null,
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
 * the rows purchasing prepared, and confirms. Batch tracked rows are counted per batch: the number
 * printed on the material is looked up (by ERPNext name or by the supplier's number) and created in
 * ERPNext when it is new; one row may take several batches, which the backend turns into one
 * Purchase Receipt Item per batch on confirmation. Confirming saves the counts, drops the rows that
 * were not received and submits the receipt through the site's workflow, which puts the stock in.
 * "Save progress" only stores the counts (and the batches) so far.
 */
@HiltViewModel
class ReceiveViewModel @Inject constructor(
    private val getPurchaseReceipt: GetPurchaseReceiptUseCase,
    private val receivePurchaseReceipt: ReceivePurchaseReceiptUseCase,
    private val searchLinkValues: SearchLinkValuesUseCase,
    private val saveFieldDefaults: SaveDocumentFieldDefaultsUseCase,
    private val lookupScan: LookupScanUseCase,
    private val searchWarehouses: SearchWarehousesUseCase,
    private val findBatch: FindBatchUseCase,
    private val createBatch: CreateBatchUseCase,
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

    fun setQty(rowName: String, text: String) = updateLine(rowName) { it.copy(qtyText = text, highlighted = false) }

    fun increment(rowName: String) = adjust(rowName, +1.0)
    fun decrement(rowName: String) = adjust(rowName, -1.0)

    // ---- batch lines ------------------------------------------------------------------------------

    fun setBatchQty(rowName: String, batchNo: String, text: String) = updateBatch(rowName, batchNo) { it.copy(qtyText = text) }
    fun incrementBatch(rowName: String, batchNo: String) = adjustBatch(rowName, batchNo, +1.0)
    fun decrementBatch(rowName: String, batchNo: String) = adjustBatch(rowName, batchNo, -1.0)

    fun removeBatch(rowName: String, batchNo: String) = updateLine(rowName) { line ->
        line.copy(batches = line.batches.filterNot { it.batchNo == batchNo }, highlighted = false)
    }

    private fun adjustBatch(rowName: String, batchNo: String, delta: Double) =
        updateBatch(rowName, batchNo) { it.copy(qtyText = (it.qty + delta).coerceAtLeast(0.0).format()) }

    private fun updateBatch(rowName: String, batchNo: String, transform: (BatchLineState) -> BatchLineState) = updateLine(rowName) { line ->
        line.copy(batches = line.batches.map { if (it.batchNo == batchNo) transform(it) else it }, highlighted = false)
    }

    private fun updateLine(rowName: String, transform: (ReceiveLineState) -> ReceiveLineState) = _uiState.update { state ->
        state.copy(lines = state.lines.map { if (it.item.rowName == rowName) transform(it) else it })
    }

    /** Adds [batch] under the row (if it is not there yet) and returns the line's index. */
    private fun ensureBatchLine(rowName: String, batch: Batch, isNew: Boolean = false): Int {
        updateLine(rowName) { line ->
            if (line.batches.any { it.batchNo == batch.name }) line
            else line.copy(batches = line.batches + BatchLineState(batchNo = batch.name, label = batch.label, expiryDate = batch.expiryDate, isNew = isNew))
        }
        return _uiState.value.lines.indexOfFirst { it.item.rowName == rowName }
    }

    // ---- batch sheet ------------------------------------------------------------------------------

    /** "Add batch" on a batch tracked row. */
    fun openBatchSheet(rowName: String) {
        val line = _uiState.value.lines.firstOrNull { it.item.rowName == rowName } ?: return
        _uiState.update { it.copy(batchSheet = BatchSheetState(rowName = rowName, manufacturingDate = LocalDate.now().toString(), expiryDate = defaultExpiry(line.item, LocalDate.now().toString())), error = null, message = null) }
    }

    fun closeBatchSheet() = _uiState.update { it.copy(batchSheet = null) }

    fun setBatchCode(text: String) = _uiState.update { state ->
        state.copy(batchSheet = state.batchSheet?.copy(code = text, lookedUp = false, matches = emptyList(), error = null))
    }

    fun setBatchManufacturingDate(text: String) = _uiState.update { state ->
        val sheet = state.batchSheet ?: return@update state
        val item = state.lines.firstOrNull { it.item.rowName == sheet.rowName }?.item
        val expiry = if (item != null && (sheet.expiryDate.isBlank() || sheet.expiryDate == defaultExpiry(item, sheet.manufacturingDate))) defaultExpiry(item, text) else sheet.expiryDate
        state.copy(batchSheet = sheet.copy(manufacturingDate = text, expiryDate = expiry, error = null))
    }

    fun setBatchExpiryDate(text: String) = _uiState.update { state -> state.copy(batchSheet = state.batchSheet?.copy(expiryDate = text, error = null)) }

    /** The operator says which row the scanned number belongs to; the lookup then runs for that item. */
    fun chooseBatchRow(rowName: String) {
        val sheet = _uiState.value.batchSheet ?: return
        val line = _uiState.value.lines.firstOrNull { it.item.rowName == rowName } ?: return
        _uiState.update { it.copy(batchSheet = sheet.copy(rowName = rowName, candidates = emptyList(), expiryDate = defaultExpiry(line.item, sheet.manufacturingDate))) }
        if (sheet.code.isNotBlank()) lookupBatch()
    }

    /** Finds the batches of the typed or scanned number for the sheet's item. */
    fun lookupBatch() {
        val sheet = _uiState.value.batchSheet ?: return
        val rowName = sheet.rowName ?: return
        val line = _uiState.value.lines.firstOrNull { it.item.rowName == rowName } ?: return
        val code = ScanCodeSanitizer.sanitize(sheet.code)
        if (code.isEmpty() || sheet.isBusy) return
        _uiState.update { it.copy(batchSheet = sheet.copy(code = code, isBusy = true, error = null)) }
        viewModelScope.launch {
            when (val result = findBatch(code, itemCode = line.item.itemCode)) {
                is AppResult.Success -> {
                    val matches = result.data.batches.filterNot { it.disabled }.ifEmpty { result.data.batches }
                    val single = matches.singleOrNull()
                    if (single != null) {
                        takeBatch(rowName, single, isNew = false)
                    } else {
                        _uiState.update { it.copy(batchSheet = it.batchSheet?.copy(isBusy = false, lookedUp = true, matches = matches)) }
                    }
                }
                is AppResult.Failure -> _uiState.update { it.copy(batchSheet = it.batchSheet?.copy(isBusy = false, error = result.error.toUiText())) }
            }
        }
    }

    /** One of several batches found for the number. */
    fun selectFoundBatch(batch: Batch) {
        val rowName = _uiState.value.batchSheet?.rowName ?: return
        takeBatch(rowName, batch, isNew = false)
    }

    /** Creates the batch of the sheet (new number) in ERPNext and counts it. */
    fun createBatchFromSheet() {
        val state = _uiState.value
        val sheet = state.batchSheet ?: return
        val rowName = sheet.rowName ?: return
        val line = state.lines.firstOrNull { it.item.rowName == rowName } ?: return
        val receipt = state.receipt ?: return
        if (sheet.isBusy || !sheet.isNew) return
        _uiState.update { it.copy(batchSheet = sheet.copy(isBusy = true, error = null)) }
        viewModelScope.launch {
            val request = NewBatch(
                itemCode = line.item.itemCode,
                batchNo = sheet.code,
                manufacturingDate = sheet.manufacturingDate,
                expiryDate = sheet.expiryDate,
                supplier = receipt.supplier.ifBlank { null },
                receipt = receipt.name,
            )
            when (val result = createBatch(request)) {
                is AppResult.Success -> takeBatch(rowName, result.data.batch, isNew = result.data.created)
                is AppResult.Failure -> _uiState.update { it.copy(batchSheet = it.batchSheet?.copy(isBusy = false, error = result.error.toUiText())) }
            }
        }
    }

    /** Closes the sheet, puts the batch under the row and asks for its quantity. */
    private fun takeBatch(rowName: String, batch: Batch, isNew: Boolean) {
        _uiState.update { it.copy(batchSheet = null) }
        val index = ensureBatchLine(rowName, batch, isNew)
        if (isNew) _uiState.update { it.copy(message = UiText.Res(R.string.receive_batch_created, listOf(batch.label, batch.name))) }
        if (index >= 0) countScan(index, batch.name)
    }

    /** Manufacturing date plus the item's shelf life, when its batches expire; blank otherwise. */
    private fun defaultExpiry(item: PurchaseReceiptItem, manufacturingDate: String): String {
        if (!item.hasExpiryDate || item.shelfLifeDays <= 0) return ""
        return runCatching { LocalDate.parse(manufacturingDate.trim()).plusDays(item.shelfLifeDays.toLong()).toString() }.getOrDefault("")
    }

    // ---- counting ---------------------------------------------------------------------------------

    /** Takes every row as the draft expects it (a batch tracked row only when it has exactly one batch). */
    fun receiveAll() = _uiState.update { state ->
        state.copy(
            lines = state.lines.map { line ->
                when {
                    line.batches.size == 1 -> line.copy(batches = listOf(line.batches.single().copy(qtyText = line.item.qty.format())), highlighted = false)
                    line.countsByBatch -> line.copy(highlighted = false)
                    else -> line.copy(qtyText = line.item.qty.format(), highlighted = false)
                }
            },
            message = null,
        )
    }

    fun clearAll() = _uiState.update { state ->
        state.copy(lines = state.lines.map { line -> line.copy(qtyText = "0", batches = line.batches.map { it.copy(qtyText = "0") }, highlighted = false) })
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null, error = null) }

    /**
     * A scan counts the matching row: an item barcode or code finds the row of that item; a batch
     * label (WMS QR with a batch, an ERPNext batch name or the supplier's batch number) finds the
     * batch among the receipt's items, puts it under its row and counts it; a number no batch has
     * yet opens the sheet that creates it.
     */
    fun onScanned(code: ScannedCode) {
        val state = _uiState.value
        val receipt = state.receipt ?: return
        if (state.completed != null || state.isBusy || !state.canReceive) return
        if (code.source != ScanSource.MANUAL) scanner.feedback(state.beep, state.vibrate)
        val value = ScanCodeSanitizer.sanitize(code.value)
        if (value.isEmpty()) return
        if (state.batchSheet != null) {
            // The sheet is waiting for the number printed on the material.
            setBatchCode(value)
            if (state.batchSheet.rowName != null) lookupBatch()
            return
        }
        // A row of the scanned item or barcode is on the receipt: count it like a looked-up item, so
        // that a second row of the same item takes over once the first one is complete.
        val direct = state.lines.firstOrNull { it.item.matches(value) }
        if (direct != null) {
            countItem(direct.item.itemCode, batch = null, receipt = receipt)
            return
        }
        viewModelScope.launch {
            val batchRows = state.lines.filter { it.item.hasBatchNo }
            if (batchRows.isNotEmpty() && !value.startsWith("{")) {
                // The supplier's number printed on the material, among the receipt's items.
                when (val found = findBatch(value, receipt = receipt.name)) {
                    is AppResult.Success -> {
                        val batch = found.data.batches.firstOrNull { b -> batchRows.any { it.item.itemCode.equals(b.itemCode, ignoreCase = true) } }
                        if (batch != null) {
                            countItem(batch.itemCode, batch = batch, receipt = receipt)
                            return@launch
                        }
                    }
                    is AppResult.Failure -> {
                        _uiState.update { it.copy(error = found.error.toUiText()) }
                        return@launch
                    }
                }
            }
            when (val result = lookupScan(value, ScanTarget.ITEM)) {
                is AppResult.Success -> when (val lookup = result.data) {
                    is ScanLookup.ItemFound -> countItem(lookup.item.code, batch = null, receipt = receipt)
                    is ScanLookup.BatchFound -> countItem(lookup.item?.code ?: lookup.batch.itemCode, batch = lookup.batch, receipt = receipt)
                    else -> offerNewBatch(value, batchRows)
                }
                is AppResult.Failure -> _uiState.update { it.copy(error = result.error.toUiText()) }
            }
        }
    }

    /** A number nothing in ERPNext knows: a new batch for one of the batch tracked rows, or not found. */
    private fun offerNewBatch(code: String, batchRows: List<ReceiveLineState>) {
        val candidates = batchRows.filter { it.item.needsBatch || it.item.hasBatchNo && !it.item.createNewBatch }.ifEmpty { batchRows }
        if (candidates.isEmpty()) {
            _uiState.update { it.copy(error = UiText.Res(R.string.scan_item_not_found, listOf(code))) }
            return
        }
        val open = candidates.filter { it.remaining > 1e-9 }.ifEmpty { candidates }
        val single = open.singleOrNull() ?: candidates.singleOrNull()
        val today = LocalDate.now().toString()
        _uiState.update {
            it.copy(
                error = null,
                batchSheet = BatchSheetState(
                    rowName = single?.item?.rowName,
                    candidates = if (single == null) open.map { line -> line.item.rowName } else emptyList(),
                    code = code,
                    lookedUp = single != null,
                    manufacturingDate = today,
                    expiryDate = single?.let { line -> defaultExpiry(line.item, today) }.orEmpty(),
                ),
            )
        }
    }

    /** Finds the open row of [itemCode] (an open one first), puts the scanned batch under it, then counts it. */
    private fun countItem(itemCode: String, batch: Batch?, receipt: PurchaseReceipt) {
        val lines = _uiState.value.lines
        val candidates = lines.withIndex().filter { it.value.item.itemCode.equals(itemCode, ignoreCase = true) }
        if (candidates.isEmpty()) {
            _uiState.update { it.copy(error = UiText.Res(R.string.receive_not_on_receipt, listOf(itemCode, receipt.name))) }
            return
        }
        val target = candidates.firstOrNull { batch != null && it.value.batches.any { b -> b.batchNo.equals(batch.name, ignoreCase = true) } }
            ?: candidates.firstOrNull { batch != null && it.value.item.hasBatchNo && it.value.batches.isEmpty() }
            ?: candidates.firstOrNull { it.value.remaining > 1e-9 }
            ?: candidates.first()
        if (batch != null && target.value.item.hasBatchNo) {
            ensureBatchLine(target.value.item.rowName, batch)
            countScan(target.index, batch.name)
            return
        }
        if (target.value.countsByBatch) {
            // A batch tracked row counted by batch: the scan does not say which batch, ask for it.
            val line = target.value
            if (line.batches.size == 1) countScan(target.index, line.batches.single().batchNo) else openBatchSheet(line.item.rowName)
            return
        }
        countScan(target.index, batchNo = null)
    }

    fun submit(asDraft: Boolean) {
        val state = _uiState.value
        val receipt = state.receipt ?: return
        if (!state.canSubmit) return
        if (!asDraft) {
            val lines = state.lines.map { it.toLine(state.warehouse) }
            val differences = ReceivePurchaseReceiptUseCase.differences(receipt, lines)
            val summary = state.lines.filter { it.qty > 1e-9 }.map { line ->
                ReceiveSummaryLine(line.item.itemCode, line.item.itemName, line.item.qty, line.qty, line.item.uom, line.toLine(state.warehouse).batches)
            }
            _uiState.update { it.copy(confirmation = ReceiveConfirmation(differences, summary), error = null, message = null) }
            return
        }
        perform(asDraft, fieldValues = emptyMap())
    }

    /** The warehouse accepts the summary (and that counts may differ from the draft): rows counted 0 are dropped. */
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
    private fun countScan(index: Int, batchNo: String?) {
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
            addOne(index, batchNo)
            return
        }
        val batchLabel = batchNo?.let { b -> line.batches.firstOrNull { it.batchNo == b }?.label ?: b }
        _uiState.update { s ->
            s.copy(
                error = null,
                message = null,
                pendingScan = ScanQuantityPrompt(
                    rowName = line.item.rowName,
                    itemCode = line.item.itemCode,
                    itemName = line.item.itemName,
                    batchNo = batchLabel,
                    remaining = remaining,
                    uom = line.item.uom,
                ),
                lines = s.lines.mapIndexed { i, l -> l.copy(highlighted = i == index) },
            )
        }
        pendingBatchNo = batchNo
    }

    /** ERPNext name of the batch the open quantity prompt counts (the prompt itself shows the label). */
    private var pendingBatchNo: String? = null

    fun setPendingQty(text: String) = _uiState.update { it.copy(pendingScan = it.pendingScan?.withText(text)) }
    fun incrementPendingQty() = _uiState.update { it.copy(pendingScan = it.pendingScan?.plusOne()) }
    fun decrementPendingQty() = _uiState.update { it.copy(pendingScan = it.pendingScan?.minusOne()) }
    fun setPendingAll() = _uiState.update { it.copy(pendingScan = it.pendingScan?.all()) }
    fun cancelPendingScan() = _uiState.update { it.copy(pendingScan = null) }

    /** Adds the confirmed quantity to the scanned line or batch (never beyond what the draft expects). */
    fun confirmPendingScan() {
        val prompt = _uiState.value.pendingScan ?: return
        if (!prompt.isValid) return
        val batchNo = pendingBatchNo
        _uiState.update { state ->
            state.copy(
                pendingScan = null,
                error = null,
                message = UiText.Res(R.string.receive_qty_added, listOf(prompt.qty.format(), prompt.itemCode)),
                lines = state.lines.map { l -> if (l.item.rowName == prompt.rowName) l.plus(prompt.qty, batchNo).copy(highlighted = true) else l.copy(highlighted = false) },
            )
        }
        pendingBatchNo = null
    }

    private fun addOne(index: Int, batchNo: String?) {
        _uiState.update { state ->
            val line = state.lines[index]
            state.copy(
                message = UiText.Res(R.string.receive_qty_added, listOf("1", line.item.itemCode)),
                error = null,
                lines = state.lines.mapIndexed { i, l -> if (i == index) l.plus(1.0, batchNo).copy(highlighted = true) else l.copy(highlighted = false) },
            )
        }
    }

    /** Adds [delta] to the batch line [batchNo] (or the row), capped at what the draft expects for the row. */
    private fun ReceiveLineState.plus(delta: Double, batchNo: String?): ReceiveLineState {
        val allowed = delta.coerceAtMost(remaining)
        if (batchNo != null && batches.any { it.batchNo == batchNo }) {
            return copy(batches = batches.map { if (it.batchNo == batchNo) it.copy(qtyText = (it.qty + allowed).format()) else it })
        }
        if (batches.isNotEmpty()) return this
        return copy(qtyText = (qty + allowed).format())
    }

    /** The stepper has no upper bound: over-receipt is possible but shown in the confirmation before submitting. */
    private fun adjust(rowName: String, delta: Double) = updateLine(rowName) {
        if (it.batches.isNotEmpty()) it else it.copy(qtyText = (it.qty + delta).coerceAtLeast(0.0).format(), highlighted = false)
    }

    private fun ReceiveLineState.toLine(warehouse: String): ReceiveLine {
        val counted = batches.filter { it.qty > 1e-9 }.map { BatchCount(batchNo = it.batchNo, qty = it.qty, batchLabel = it.label) }
        return ReceiveLine(rowName = item.rowName, qty = qty, warehouse = warehouse.ifBlank { null }, batchNo = counted.firstOrNull()?.batchNo, batches = counted)
    }

    companion object {
        const val ARG_RECEIPT_NAME = "receiptName"
    }
}

internal fun Double.format(): String =
    if (this == Math.floor(this) && !isInfinite()) toLong().toString() else String.format(java.util.Locale.US, "%.3f", this).trimEnd('0').trimEnd('.')
