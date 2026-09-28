package com.wmserp.app.presentation.orders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmserp.app.R
import com.wmserp.app.core.scanner.ScannerController
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.DeliveryNote
import com.wmserp.app.domain.model.SalesOrder
import com.wmserp.app.domain.model.SalesOrderItem
import com.wmserp.app.domain.model.ScanLookup
import com.wmserp.app.domain.model.ScanSource
import com.wmserp.app.domain.model.ScanTarget
import com.wmserp.app.domain.model.ScannedCode
import com.wmserp.app.domain.model.Warehouse
import com.wmserp.app.domain.usecase.DispatchLine
import com.wmserp.app.domain.usecase.DispatchSalesOrderUseCase
import com.wmserp.app.domain.usecase.GetSalesOrderUseCase
import com.wmserp.app.domain.usecase.LookupScanUseCase
import com.wmserp.app.domain.usecase.ObserveScannerSettingsUseCase
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

data class DispatchLineState(
    val item: SalesOrderItem,
    val qtyText: String = "0",
    val highlighted: Boolean = false,
) {
    val qty: Double get() = qtyText.replace(',', '.').toDoubleOrNull() ?: 0.0
}

data class DispatchUiState(
    val isLoading: Boolean = true,
    val salesOrder: SalesOrder? = null,
    val lines: List<DispatchLineState> = emptyList(),
    val warehouse: String = "",
    val warehouses: List<Warehouse> = emptyList(),
    val isSubmitting: Boolean = false,
    val error: UiText? = null,
    val message: UiText? = null,
    val completed: DeliveryNote? = null,
    val beep: Boolean = true,
    val vibrate: Boolean = true,
) {
    val totalQty: Double get() = lines.sumOf { it.qty }
    val canSubmit: Boolean get() = !isSubmitting && salesOrder != null && totalQty > 0 && completed == null
}

/** Dispatch flow: pick items against a Sales Order and post a Delivery Note. */
@HiltViewModel
class DispatchViewModel @Inject constructor(
    private val getSalesOrder: GetSalesOrderUseCase,
    private val dispatchSalesOrder: DispatchSalesOrderUseCase,
    private val lookupScan: LookupScanUseCase,
    private val searchWarehouses: SearchWarehousesUseCase,
    observeScannerSettings: ObserveScannerSettingsUseCase,
    private val scanner: ScannerController,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val soName: String = savedStateHandle.get<String>(ARG_SO_NAME).orEmpty()

    private val _uiState = MutableStateFlow(DispatchUiState())
    val uiState: StateFlow<DispatchUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            observeScannerSettings().collect { s -> _uiState.update { it.copy(beep = s.beepOnScan, vibrate = s.vibrateOnScan) } }
        }
        load()
    }

    fun load() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = getSalesOrder(soName)) {
                is AppResult.Success -> {
                    val so = result.data
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            salesOrder = so,
                            lines = so.items.map { item -> DispatchLineState(item) },
                            warehouse = it.warehouse.ifBlank { so.setWarehouse ?: so.items.firstNotNullOfOrNull { i -> i.warehouse }.orEmpty() },
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

    fun dispatchAll() = _uiState.update { state ->
        state.copy(lines = state.lines.map { it.copy(qtyText = it.item.pendingQty.format(), highlighted = false) }, message = null)
    }

    fun clearAll() = _uiState.update { state -> state.copy(lines = state.lines.map { it.copy(qtyText = "0", highlighted = false) }) }

    fun dismissMessage() = _uiState.update { it.copy(message = null, error = null) }

    fun onScanned(code: ScannedCode) {
        val state = _uiState.value
        if (state.salesOrder == null || state.completed != null) return
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
                            _uiState.update { it.copy(error = UiText.Res(R.string.receive_not_on_order, listOf(lookup.item.code, state.salesOrder.name))) }
                        }
                    } else {
                        _uiState.update { it.copy(error = UiText.Res(R.string.scan_item_not_found, listOf(value))) }
                    }
                }
                is AppResult.Failure -> _uiState.update { it.copy(error = result.error.toUiText()) }
            }
        }
    }

    fun submit(asDraft: Boolean) {
        val state = _uiState.value
        val so = state.salesOrder ?: return
        if (!state.canSubmit) return
        _uiState.update { it.copy(isSubmitting = true, error = null, message = null) }
        viewModelScope.launch {
            val result = dispatchSalesOrder(
                salesOrder = so,
                lines = state.lines.map { DispatchLine(it.item.rowName, it.qty, state.warehouse.ifBlank { null }) },
                defaultWarehouse = state.warehouse.ifBlank { null },
                submit = !asDraft,
            )
            when (result) {
                is AppResult.Success -> _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        completed = result.data,
                        message = UiText.Res(if (asDraft) R.string.dispatch_draft_saved else R.string.dispatch_submitted, listOf(result.data.name)),
                    )
                }
                is AppResult.Failure -> _uiState.update { it.copy(isSubmitting = false, error = result.error.toUiText()) }
            }
        }
    }

    private fun addOne(index: Int) {
        _uiState.update { state ->
            val line = state.lines[index]
            val pending = line.item.pendingQty
            if (line.qty + 1 > pending + 1e-9) {
                state.copy(
                    message = UiText.Res(R.string.dispatch_already_picked, listOf(pending.format(), line.item.itemCode)),
                    lines = state.lines.mapIndexed { i, l -> l.copy(highlighted = i == index) },
                )
            } else {
                state.copy(
                    message = UiText.Res(R.string.dispatch_picked_one, listOf(line.item.itemCode)),
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
        const val ARG_SO_NAME = "soName"
    }
}
