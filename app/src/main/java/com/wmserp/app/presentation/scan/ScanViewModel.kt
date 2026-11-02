package com.wmserp.app.presentation.scan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmserp.app.R
import com.wmserp.app.core.scanner.ScannerController
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.ScanLookup
import com.wmserp.app.domain.model.ScanSource
import com.wmserp.app.domain.model.ScanTarget
import com.wmserp.app.domain.model.ScannedCode
import com.wmserp.app.domain.model.ScannerMode
import com.wmserp.app.domain.model.RequiredField
import com.wmserp.app.domain.model.TransferForm
import com.wmserp.app.domain.model.TransferRequest
import com.wmserp.app.domain.model.Warehouse
import com.wmserp.app.domain.usecase.CreateStockTransferUseCase
import com.wmserp.app.domain.usecase.GetTransferFormUseCase
import com.wmserp.app.domain.usecase.LookupScanUseCase
import com.wmserp.app.domain.usecase.SaveDocumentFieldDefaultsUseCase
import com.wmserp.app.domain.usecase.SearchLinkValuesUseCase
import com.wmserp.app.domain.usecase.ObserveScannerSettingsUseCase
import com.wmserp.app.domain.usecase.ScanCodeSanitizer
import com.wmserp.app.domain.usecase.SearchWarehousesUseCase
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.presentation.common.hintRes
import com.wmserp.app.presentation.common.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScanHistoryEntry(
    val code: String,
    val target: ScanTarget,
    val summary: UiText,
    val found: Boolean,
    val timeMillis: Long,
)

/**
 * The scan-menu transfer sheet. The draft Material Transfer is created on the server; the user
 * chooses the department and who receives it (when the site's Stock Entry has those fields). A
 * scanned batch is moved as is; a batch tracked item scanned without a batch gets its batches
 * automatically.
 */
data class TransferFormState(
    val visible: Boolean = false,
    val itemCode: String = "",
    val itemName: String = "",
    /** The scanned batch (transfer opened from a batch label); null: none, or picked automatically. */
    val batchNo: String? = null,
    val fromWarehouse: String = "",
    val toWarehouse: String = "",
    val qtyText: String = "1",
    val warehouses: List<Warehouse> = emptyList(),
    val loadingWarehouses: Boolean = false,
    val form: TransferForm? = null,
    val loadingForm: Boolean = false,
    val department: String = "",
    val departmentOptions: List<String> = emptyList(),
    val receivedBy: String = "",
    val receivedByOptions: List<String> = emptyList(),
    val submitting: Boolean = false,
    val error: UiText? = null,
    /** Other fields the site requires that still need a value (dialog while non-empty). */
    val requiredFields: List<RequiredField> = emptyList(),
    val requiredFieldAnswers: Map<String, String> = emptyMap(),
    val linkOptions: Map<String, List<String>> = emptyMap(),
) {
    val qty: Double get() = qtyText.replace(',', '.').toDoubleOrNull() ?: 0.0
    val needsDepartment: Boolean get() = form?.department != null
    val needsReceivedBy: Boolean get() = form?.receivedBy != null
    val autoBatches: Boolean get() = batchNo == null && form?.hasBatchNo == true
    val canSubmit: Boolean
        get() = !submitting && !loadingForm && form != null && fromWarehouse.isNotBlank() && toWarehouse.isNotBlank() && fromWarehouse != toWarehouse && qty > 0 &&
            (!needsDepartment || department.isNotBlank()) && (!needsReceivedBy || receivedBy.isNotBlank())
}

data class ScanUiState(
    val target: ScanTarget = ScanTarget.ITEM,
    val isLookingUp: Boolean = false,
    val lastScan: ScannedCode? = null,
    val result: ScanLookup? = null,
    val error: UiText? = null,
    val message: UiText? = null,
    val manualInput: String = "",
    val history: List<ScanHistoryEntry> = emptyList(),
    val scannerMode: ScannerMode = ScannerMode.AUTO,
    val hasHardwareScanner: Boolean = false,
    val cameraActive: Boolean = false,
    val beep: Boolean = true,
    val vibrate: Boolean = true,
    val transfer: TransferFormState = TransferFormState(),
) {
    /** Text shown under the viewfinder. */
    val statusText: UiText
        get() = if (isLookingUp) {
            UiText.Res(R.string.scan_looking_up, listOf(lastScan?.value.orEmpty()))
        } else {
            UiText.Res(target.hintRes())
        }
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val lookupScan: LookupScanUseCase,
    private val searchWarehouses: SearchWarehousesUseCase,
    private val getTransferForm: GetTransferFormUseCase,
    private val createStockTransfer: CreateStockTransferUseCase,
    private val searchLinkValues: SearchLinkValuesUseCase,
    private val saveFieldDefaults: SaveDocumentFieldDefaultsUseCase,
    observeScannerSettings: ObserveScannerSettingsUseCase,
    private val scanner: ScannerController,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ScanUiState(
            target = savedStateHandle.get<String>(ARG_TARGET)?.let { runCatching { ScanTarget.valueOf(it) }.getOrNull() } ?: ScanTarget.ITEM,
            hasHardwareScanner = scanner.hasHardwareScanner,
            cameraActive = !scanner.hasHardwareScanner,
        )
    )
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private var lookupJob: Job? = null
    private var cameraOverride: Boolean? = null
    private var departmentSearch: Job? = null
    private var receivedBySearch: Job? = null

    init {
        viewModelScope.launch {
            observeScannerSettings().collect { settings ->
                _uiState.update {
                    it.copy(
                        scannerMode = settings.mode,
                        beep = settings.beepOnScan,
                        vibrate = settings.vibrateOnScan,
                        cameraActive = cameraOverride ?: when (settings.mode) {
                            ScannerMode.CAMERA -> true
                            ScannerMode.HARDWARE -> false
                            ScannerMode.AUTO -> !it.hasHardwareScanner
                        },
                    )
                }
            }
        }
    }

    fun setTarget(target: ScanTarget) = _uiState.update { it.copy(target = target, result = null, error = null, message = null) }

    fun onManualInputChange(value: String) = _uiState.update { it.copy(manualInput = value) }

    fun submitManual() {
        val value = _uiState.value.manualInput.trim()
        if (value.isEmpty()) return
        onScanned(ScannedCode(value, ScanSource.MANUAL))
    }

    fun toggleCamera() {
        val next = !_uiState.value.cameraActive
        cameraOverride = next
        _uiState.update { it.copy(cameraActive = next) }
    }

    fun clearResult() = _uiState.update { it.copy(result = null, error = null, message = null, lastScan = null) }

    fun dismissMessage() = _uiState.update { it.copy(message = null, error = null) }

    /** Entry point for every scanner source (wedge, intent, camera, manual). */
    fun onScanned(code: ScannedCode) {
        val current = _uiState.value
        if (current.transfer.visible) return
        val value = ScanCodeSanitizer.sanitize(code.value)
        if (value.isEmpty()) return
        if (current.isLookingUp && current.lastScan?.value == value) return
        if (code.source != ScanSource.MANUAL) scanner.feedback(current.beep, current.vibrate)
        startLookup(code.copy(value = value), current.target, clearMessage = true)
    }

    private fun startLookup(code: ScannedCode, target: ScanTarget, clearMessage: Boolean) {
        lookupJob?.cancel()
        _uiState.update {
            it.copy(
                lastScan = code,
                isLookingUp = true,
                error = null,
                message = if (clearMessage) null else it.message,
                result = null,
                manualInput = "",
            )
        }
        val value = code.value
        lookupJob = viewModelScope.launch {
            when (val result = lookupScan(value, target)) {
                is AppResult.Success -> {
                    val lookup = result.data
                    _uiState.update {
                        it.copy(
                            isLookingUp = false,
                            result = lookup,
                            history = (listOf(historyEntry(lookup, code.timestampMillis)) + it.history).take(MAX_HISTORY),
                        )
                    }
                }
                is AppResult.Failure -> _uiState.update { it.copy(isLookingUp = false, error = result.error.toUiText()) }
            }
        }
    }

    // ---- Stock transfer sheet -------------------------------------------------------------

    /** Opens the transfer sheet for the scanned item, or for the scanned batch (moved as is). */
    fun openTransfer() {
        val sheet = when (val result = _uiState.value.result) {
            is ScanLookup.ItemFound -> TransferFormState(
                visible = true,
                itemCode = result.item.code,
                itemName = result.item.name,
                fromWarehouse = result.stock.maxByOrNull { it.actualQty }?.warehouse.orEmpty(),
            )
            is ScanLookup.BatchFound -> TransferFormState(
                visible = true,
                itemCode = result.item?.code ?: result.batch.itemCode,
                itemName = result.item?.name ?: result.batch.itemName ?: result.batch.itemCode,
                batchNo = result.batch.name,
                fromWarehouse = result.stock.maxByOrNull { it.qty }?.warehouse.orEmpty(),
            )
            else -> return
        }
        _uiState.update { it.copy(transfer = sheet.copy(loadingWarehouses = true, loadingForm = true)) }
        viewModelScope.launch {
            val warehouses = searchWarehouses("").getOrNull().orEmpty()
            _uiState.update { it.copy(transfer = it.transfer.copy(warehouses = warehouses, loadingWarehouses = false)) }
        }
        viewModelScope.launch {
            when (val form = getTransferForm(sheet.itemCode)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(transfer = it.transfer.copy(form = form.data, loadingForm = false)) }
                    form.data.department?.let { searchDepartments("") }
                    form.data.receivedBy?.let { searchReceivers("") }
                }
                is AppResult.Failure -> _uiState.update { it.copy(transfer = it.transfer.copy(loadingForm = false, error = form.error.toUiText())) }
            }
        }
    }

    fun closeTransfer() = _uiState.update { it.copy(transfer = TransferFormState()) }
    fun onTransferFromChange(value: String) = _uiState.update { it.copy(transfer = it.transfer.copy(fromWarehouse = value, error = null)) }
    fun onTransferToChange(value: String) = _uiState.update { it.copy(transfer = it.transfer.copy(toWarehouse = value, error = null)) }
    fun onTransferQtyChange(value: String) = _uiState.update { it.copy(transfer = it.transfer.copy(qtyText = value, error = null)) }

    fun onTransferDepartmentChange(value: String) {
        _uiState.update { it.copy(transfer = it.transfer.copy(department = value, error = null)) }
        searchDepartments(value)
    }

    fun onTransferReceivedByChange(value: String) {
        _uiState.update { it.copy(transfer = it.transfer.copy(receivedBy = value, error = null)) }
        searchReceivers(value)
    }

    private fun searchDepartments(query: String) {
        val doctype = _uiState.value.transfer.form?.department?.options ?: return
        departmentSearch?.cancel()
        departmentSearch = viewModelScope.launch {
            searchLinkValues(doctype, query).getOrNull()?.let { names -> _uiState.update { it.copy(transfer = it.transfer.copy(departmentOptions = names)) } }
        }
    }

    private fun searchReceivers(query: String) {
        val doctype = _uiState.value.transfer.form?.receivedBy?.options ?: return
        receivedBySearch?.cancel()
        receivedBySearch = viewModelScope.launch {
            searchLinkValues(doctype, query).getOrNull()?.let { names -> _uiState.update { it.copy(transfer = it.transfer.copy(receivedByOptions = names)) } }
        }
    }

    /** Creates the draft Material Transfer on the server. */
    fun submitTransfer() = submitTransfer(fieldValues = emptyMap())

    private fun submitTransfer(fieldValues: Map<String, String>) {
        val state = _uiState.value
        val form = state.transfer
        if (!form.canSubmit) return
        _uiState.update { it.copy(transfer = it.transfer.copy(submitting = true, error = null)) }
        viewModelScope.launch {
            val request = TransferRequest(
                itemCode = form.itemCode,
                qty = form.qty,
                sourceWarehouse = form.fromWarehouse,
                targetWarehouse = form.toWarehouse,
                batchNo = form.batchNo,
                department = form.department.trim().takeIf { form.needsDepartment && it.isNotEmpty() },
                receivedBy = form.receivedBy.trim().takeIf { form.needsReceivedBy && it.isNotEmpty() },
            )
            when (val result = createStockTransfer(request, fieldValues)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            transfer = TransferFormState(),
                            message = UiText.Res(
                                R.string.transfer_draft_created,
                                listOf(result.data.name, form.qtyText, form.itemCode, form.toWarehouse),
                            ),
                        )
                    }
                    // Refresh the result, keeping the confirmation message visible.
                    state.lastScan?.let { startLookup(it.copy(source = ScanSource.MANUAL), state.target, clearMessage = false) }
                }
                is AppResult.Failure -> {
                    val error = result.error
                    if (error is AppError.MissingRequiredFields) {
                        askRequiredFields(error.fields)
                    } else {
                        _uiState.update { it.copy(transfer = it.transfer.copy(submitting = false, error = error.toUiText())) }
                    }
                }
            }
        }
    }

    /** The site needs more values than the sheet asks: open the dialog and load the choices of its Link fields. */
    private fun askRequiredFields(fields: List<RequiredField>) {
        _uiState.update { s ->
            s.copy(
                transfer = s.transfer.copy(
                    submitting = false,
                    requiredFields = fields,
                    requiredFieldAnswers = fields.associate { it.key to s.transfer.requiredFieldAnswers[it.key].orEmpty() },
                    linkOptions = emptyMap(),
                ),
            )
        }
        viewModelScope.launch {
            fields.filter { it.isLink }.forEach { field ->
                val target = field.options ?: return@forEach
                searchLinkValues(target, "").getOrNull()?.let { names ->
                    _uiState.update { it.copy(transfer = it.transfer.copy(linkOptions = it.transfer.linkOptions + (field.key to names))) }
                }
            }
        }
    }

    fun setTransferFieldAnswer(key: String, value: String) =
        _uiState.update { it.copy(transfer = it.transfer.copy(requiredFieldAnswers = it.transfer.requiredFieldAnswers + (key to value))) }

    fun dismissTransferFields() = _uiState.update { it.copy(transfer = it.transfer.copy(requiredFields = emptyList())) }

    /** Remembers the answers (except those asked every time) and creates the transfer with them. */
    fun confirmTransferFields() {
        val form = _uiState.value.transfer
        val answers = form.requiredFields.associate { it.key to form.requiredFieldAnswers[it.key].orEmpty().trim() }
        if (answers.isEmpty() || answers.values.any { it.isBlank() }) return
        _uiState.update { it.copy(transfer = it.transfer.copy(requiredFields = emptyList())) }
        viewModelScope.launch {
            saveFieldDefaults(answers.filterKeys { key -> form.requiredFields.firstOrNull { it.key == key }?.remember != false })
            submitTransfer(answers)
        }
    }

    private fun historyEntry(lookup: ScanLookup, time: Long): ScanHistoryEntry = when (lookup) {
        is ScanLookup.ItemFound -> ScanHistoryEntry(
            lookup.code, ScanTarget.ITEM,
            UiText.Res(R.string.history_item_summary, listOf(lookup.item.name, lookup.stock.sumOf { it.actualQty }.trimQty())),
            true, time,
        )
        is ScanLookup.WarehouseFound -> ScanHistoryEntry(
            lookup.code, ScanTarget.WAREHOUSE,
            UiText.Res(R.string.history_warehouse_summary, listOf(lookup.warehouse.warehouseName, lookup.stock.size)),
            true, time,
        )
        is ScanLookup.BatchFound -> ScanHistoryEntry(
            lookup.code, ScanTarget.ITEM,
            UiText.Res(R.string.history_batch_summary, listOf(lookup.batch.name, lookup.item?.name ?: lookup.batch.itemCode, lookup.totalQty.trimQty())),
            true, time,
        )
        is ScanLookup.PurchaseReceiptFound -> ScanHistoryEntry(
            lookup.code, ScanTarget.PURCHASE_RECEIPT,
            UiText.Res(R.string.history_pr_summary, listOf(lookup.receipt.supplierName, lookup.receipt.workflowState ?: lookup.receipt.status ?: "")),
            true, time,
        )
        is ScanLookup.NotFound -> ScanHistoryEntry(lookup.code, lookup.target, UiText.Res(R.string.scan_chip_not_found), false, time)
    }

    private fun Double.trimQty(): String = if (this == Math.floor(this)) toLong().toString() else String.format(java.util.Locale.US, "%.3f", this).trimEnd('0').trimEnd('.')

    companion object {
        const val ARG_TARGET = "target"
        private const val MAX_HISTORY = 12
    }
}
