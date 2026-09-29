package com.wmserp.app.presentation.orders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmserp.app.R
import com.wmserp.app.core.scanner.ScannerController
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.PurchaseOrder
import com.wmserp.app.domain.model.PurchaseOrderItem
import com.wmserp.app.domain.model.PurchaseReceipt
import com.wmserp.app.domain.model.RequiredField
import com.wmserp.app.domain.model.ScanLookup
import com.wmserp.app.domain.model.ScanSource
import com.wmserp.app.domain.model.ScanTarget
import com.wmserp.app.domain.model.ScannedCode
import com.wmserp.app.domain.model.Warehouse
import com.wmserp.app.domain.usecase.GetPurchaseOrderUseCase
import com.wmserp.app.domain.usecase.LookupScanUseCase
import com.wmserp.app.domain.usecase.ObserveScannerSettingsUseCase
import com.wmserp.app.domain.usecase.ReceiveLine
import com.wmserp.app.domain.usecase.ReceivePurchaseOrderUseCase
import com.wmserp.app.domain.usecase.SaveDocumentFieldDefaultsUseCase
import com.wmserp.app.domain.usecase.SearchLinkValuesUseCase
import com.wmserp.app.domain.usecase.ScanCodeSanitizer
import com.wmserp.app.domain.usecase.SearchWarehousesUseCase
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.presentation.common.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReceiveLineState(
    val item: PurchaseOrderItem,
    val qtyText: String = "0",
    val highlighted: Boolean = false,
) {
    val qty: Double get() = qtyText.replace(',', '.').toDoubleOrNull() ?: 0.0
}

data class ReceiveUiState(
    val isLoading: Boolean = true,
    val purchaseOrder: PurchaseOrder? = null,
    val lines: List<ReceiveLineState> = emptyList(),
    val warehouse: String = "",
    val warehouses: List<Warehouse> = emptyList(),
    val isSubmitting: Boolean = false,
    val error: UiText? = null,
    val message: UiText? = null,
    val completed: PurchaseReceipt? = null,
    /** Required fields the ERPNext site added that still need a value; the dialog shows while non-empty. */
    val requiredFields: List<RequiredField> = emptyList(),
    val requiredFieldAnswers: Map<String, String> = emptyMap(),
    /** Possible values of the required Link fields, keyed by [RequiredField.key]. */
    val linkOptions: Map<String, List<String>> = emptyMap(),
    val beep: Boolean = true,
    val vibrate: Boolean = true,
) {
    val totalQty: Double get() = lines.sumOf { it.qty }
    val canSubmit: Boolean get() = !isSubmitting && purchaseOrder != null && totalQty > 0 && completed == null
}

/** Receive flow: count incoming goods against a Purchase Order and post a Purchase Receipt. */
@HiltViewModel
class ReceiveViewModel @Inject constructor(
    private val getPurchaseOrder: GetPurchaseOrderUseCase,
    private val receivePurchaseOrder: ReceivePurchaseOrderUseCase,
    private val searchLinkValues: SearchLinkValuesUseCase,
    private val saveFieldDefaults: SaveDocumentFieldDefaultsUseCase,
    private val lookupScan: LookupScanUseCase,
    private val searchWarehouses: SearchWarehousesUseCase,
    observeScannerSettings: ObserveScannerSettingsUseCase,
    private val scanner: ScannerController,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val poName: String = savedStateHandle.get<String>(ARG_PO_NAME).orEmpty()

    private val _uiState = MutableStateFlow(ReceiveUiState())
    val uiState: StateFlow<ReceiveUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            observeScannerSettings().collect { s -> _uiState.update { it.copy(beep = s.beepOnScan, vibrate = s.vibrateOnScan) } }
        }
        load()
    }

    fun load() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = getPurchaseOrder(poName)) {
                is AppResult.Success -> {
                    val po = result.data
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            purchaseOrder = po,
                            lines = po.items.map { item -> ReceiveLineState(item) },
                            warehouse = it.warehouse.ifBlank { po.setWarehouse ?: po.items.firstNotNullOfOrNull { i -> i.warehouse }.orEmpty() },
                        )
                    }
                    val warehouses = searchWarehouses("").getOrNull().orEmpty()
                    _uiState.update { it.copy(warehouses = warehouses) }
                }
                is AppResult.Failure -> _uiState.update { it.copy(isLoading = false, error = result.error.toUiText()) }
            }
        }
    }

    fun setWarehouse(value: String) = _uiState.update { it.copy(warehouse = value) }

    fun setQty(rowName: String, text: String) = _uiState.update { state ->
        state.copy(lines = state.lines.map { if (it.item.rowName == rowName) it.copy(qtyText = text, highlighted = false) else it })
    }

    fun increment(rowName: String) = adjust(rowName, +1.0)
    fun decrement(rowName: String) = adjust(rowName, -1.0)

    fun receiveAll() = _uiState.update { state ->
        state.copy(lines = state.lines.map { it.copy(qtyText = it.item.pendingQty.format(), highlighted = false) }, message = null)
    }

    fun clearAll() = _uiState.update { state -> state.copy(lines = state.lines.map { it.copy(qtyText = "0", highlighted = false) }) }

    fun dismissMessage() = _uiState.update { it.copy(message = null, error = null) }

    /** A scanned item barcode increments the matching line by one. */
    fun onScanned(code: ScannedCode) {
        val state = _uiState.value
        if (state.purchaseOrder == null || state.completed != null) return
        if (code.source != ScanSource.MANUAL) scanner.feedback(state.beep, state.vibrate)
        val value = ScanCodeSanitizer.sanitize(code.value)
        if (value.isEmpty()) return
        val direct = state.lines.indexOfFirst { it.item.itemCode.equals(value, ignoreCase = true) }
        if (direct >= 0) {
            addOne(direct)
            return
        }
        viewModelScope.launch {
            when (val result = lookupScan(value, ScanTarget.ITEM)) {
                is AppResult.Success -> {
                    val lookup = result.data
                    if (lookup is ScanLookup.ItemFound) {
                        val idx = _uiState.value.lines.indexOfFirst { it.item.itemCode.equals(lookup.item.code, ignoreCase = true) }
                        if (idx >= 0) {
                            addOne(idx)
                        } else {
                            _uiState.update { it.copy(error = UiText.Res(R.string.receive_not_on_order, listOf(lookup.item.code, state.purchaseOrder.name))) }
                        }
                    } else {
                        _uiState.update { it.copy(error = UiText.Res(R.string.scan_item_not_found, listOf(value))) }
                    }
                }
                is AppResult.Failure -> _uiState.update { it.copy(error = result.error.toUiText()) }
            }
        }
    }

    fun submit(asDraft: Boolean) = submit(asDraft, fieldValues = emptyMap())

    private fun submit(asDraft: Boolean, fieldValues: Map<String, String>) {
        val state = _uiState.value
        val po = state.purchaseOrder ?: return
        if (!state.canSubmit) return
        _uiState.update { it.copy(isSubmitting = true, error = null, message = null) }
        viewModelScope.launch {
            val result = receivePurchaseOrder(
                purchaseOrder = po,
                lines = state.lines.map { ReceiveLine(it.item.rowName, it.qty, state.warehouse.ifBlank { null }) },
                defaultWarehouse = state.warehouse.ifBlank { null },
                submit = !asDraft,
                fieldValues = fieldValues,
            )
            when (result) {
                is AppResult.Success -> _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        completed = result.data,
                        message = UiText.Res(if (asDraft) R.string.receive_draft_saved else R.string.receive_submitted, listOf(result.data.name)),
                    )
                }
                is AppResult.Failure -> {
                    val error = result.error
                    if (error is AppError.MissingRequiredFields) {
                        askRequiredFields(error.fields, asDraft, po.company)
                    } else {
                        _uiState.update { it.copy(isSubmitting = false, error = error.toUiText()) }
                    }
                }
            }
        }
    }

    private var pendingSubmitAsDraft = false

    /** ERPNext needs values the order does not carry: open the dialog and load the choices of its Link fields. */
    private fun askRequiredFields(fields: List<RequiredField>, asDraft: Boolean, company: String?) {
        pendingSubmitAsDraft = asDraft
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

    /** Saves the answers for the next documents and retries the submission with them. */
    fun confirmRequiredFields() {
        val state = _uiState.value
        val answers = state.requiredFields.associate { it.key to state.requiredFieldAnswers[it.key].orEmpty().trim() }
        if (answers.isEmpty() || answers.values.any { it.isBlank() }) return
        _uiState.update { it.copy(requiredFields = emptyList()) }
        viewModelScope.launch {
            saveFieldDefaults(answers)
            submit(pendingSubmitAsDraft, answers)
        }
    }

    private fun addOne(index: Int) {
        _uiState.update { state ->
            val line = state.lines[index]
            val pending = line.item.pendingQty
            if (line.qty + 1 > pending + 1e-9) {
                state.copy(
                    message = UiText.Res(R.string.receive_already_counted, listOf(pending.format(), line.item.itemCode)),
                    lines = state.lines.mapIndexed { i, l -> l.copy(highlighted = i == index) },
                )
            } else {
                state.copy(
                    message = UiText.Res(R.string.receive_added, listOf(line.item.itemCode)),
                    error = null,
                    lines = state.lines.mapIndexed { i, l -> if (i == index) l.copy(qtyText = (l.qty + 1).format(), highlighted = true) else l.copy(highlighted = false) },
                )
            }
        }
    }

    private fun adjust(rowName: String, delta: Double) = _uiState.update { state ->
        state.copy(
            lines = state.lines.map {
                if (it.item.rowName == rowName) {
                    val next = (it.qty + delta).coerceIn(0.0, it.item.pendingQty)
                    it.copy(qtyText = next.format(), highlighted = false)
                } else {
                    it
                }
            }
        )
    }

    companion object {
        const val ARG_PO_NAME = "poName"
    }
}

internal fun Double.format(): String =
    if (this == Math.floor(this) && !isInfinite()) toLong().toString() else String.format(java.util.Locale.US, "%.3f", this).trimEnd('0').trimEnd('.')
