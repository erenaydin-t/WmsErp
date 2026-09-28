package com.wmserp.app.presentation.scan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmserp.app.R
import com.wmserp.app.core.scanner.ScannerController
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.ScanLookup
import com.wmserp.app.domain.model.ScanSource
import com.wmserp.app.domain.model.ScanTarget
import com.wmserp.app.domain.model.ScannedCode
import com.wmserp.app.domain.model.ScannerMode
import com.wmserp.app.domain.model.StockEntryType
import com.wmserp.app.domain.model.Warehouse
import com.wmserp.app.domain.usecase.CreateStockEntryUseCase
import com.wmserp.app.domain.usecase.LookupScanUseCase
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

data class TransferFormState(
    val visible: Boolean = false,
    val fromWarehouse: String = "",
    val toWarehouse: String = "",
    val qtyText: String = "1",
    val warehouses: List<Warehouse> = emptyList(),
    val loadingWarehouses: Boolean = false,
    val submitting: Boolean = false,
    val error: UiText? = null,
) {
    val qty: Double get() = qtyText.replace(',', '.').toDoubleOrNull() ?: 0.0
    val canSubmit: Boolean get() = !submitting && fromWarehouse.isNotBlank() && toWarehouse.isNotBlank() && fromWarehouse != toWarehouse && qty > 0
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
    private val createStockEntry: CreateStockEntryUseCase,
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

    fun openTransfer() {
        val item = (_uiState.value.result as? ScanLookup.ItemFound) ?: return
        val from = item.stock.maxByOrNull { it.actualQty }?.warehouse.orEmpty()
        _uiState.update { it.copy(transfer = TransferFormState(visible = true, fromWarehouse = from, loadingWarehouses = true)) }
        viewModelScope.launch {
            val warehouses = searchWarehouses("").getOrNull().orEmpty()
            _uiState.update { it.copy(transfer = it.transfer.copy(warehouses = warehouses, loadingWarehouses = false)) }
        }
    }

    fun closeTransfer() = _uiState.update { it.copy(transfer = TransferFormState()) }
    fun onTransferFromChange(value: String) = _uiState.update { it.copy(transfer = it.transfer.copy(fromWarehouse = value, error = null)) }
    fun onTransferToChange(value: String) = _uiState.update { it.copy(transfer = it.transfer.copy(toWarehouse = value, error = null)) }
    fun onTransferQtyChange(value: String) = _uiState.update { it.copy(transfer = it.transfer.copy(qtyText = value, error = null)) }

    fun submitTransfer() {
        val state = _uiState.value
        val item = (state.result as? ScanLookup.ItemFound)?.item ?: return
        val form = state.transfer
        if (!form.canSubmit) return
        _uiState.update { it.copy(transfer = it.transfer.copy(submitting = true, error = null)) }
        viewModelScope.launch {
            val result = createStockEntry(
                type = StockEntryType.MATERIAL_TRANSFER,
                itemCode = item.code,
                qty = form.qty,
                sourceWarehouse = form.fromWarehouse,
                targetWarehouse = form.toWarehouse,
                submit = true,
                remarks = "Created from WMS ERP mobile scan",
            )
            when (result) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            transfer = TransferFormState(),
                            message = UiText.Res(
                                R.string.transfer_success,
                                listOf(result.data.name.orEmpty(), form.qtyText, item.code, form.toWarehouse),
                            ),
                        )
                    }
                    // Refresh stock levels for the item, keeping the confirmation message visible.
                    state.lastScan?.let { startLookup(it.copy(source = ScanSource.MANUAL), state.target, clearMessage = false) }
                }
                is AppResult.Failure -> _uiState.update { it.copy(transfer = it.transfer.copy(submitting = false, error = result.error.toUiText())) }
            }
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
        is ScanLookup.PurchaseOrderFound -> ScanHistoryEntry(
            lookup.code, ScanTarget.PURCHASE_ORDER,
            UiText.Res(R.string.history_po_summary, listOf(lookup.purchaseOrder.supplierName, lookup.purchaseOrder.status)),
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
