package com.wmserp.app.presentation.picking

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmserp.app.R
import com.wmserp.app.core.scanner.ScannerController
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.GeneratedDocument
import com.wmserp.app.domain.model.PICK_QTY_TOLERANCE
import com.wmserp.app.domain.model.PickList
import com.wmserp.app.domain.model.PickListItem
import com.wmserp.app.domain.model.PickProgressLine
import com.wmserp.app.domain.model.PickRowStatus
import com.wmserp.app.domain.model.PickingStatus
import com.wmserp.app.domain.model.ScanSource
import com.wmserp.app.domain.model.ScannedCode
import com.wmserp.app.domain.model.isPickComplete
import com.wmserp.app.domain.model.pickRowStatus
import com.wmserp.app.domain.repository.AuthRepository
import com.wmserp.app.domain.usecase.CompletePickingUseCase
import com.wmserp.app.domain.usecase.GeneratePickDocumentUseCase
import com.wmserp.app.domain.usecase.GetPickListUseCase
import com.wmserp.app.domain.usecase.ObserveScannerSettingsUseCase
import com.wmserp.app.domain.usecase.PickScanResult
import com.wmserp.app.domain.usecase.ResolvePickScanUseCase
import com.wmserp.app.domain.usecase.SavePickProgressUseCase
import com.wmserp.app.domain.usecase.StartPickingUseCase
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.presentation.common.toUiText
import com.wmserp.app.presentation.orders.format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

/** One pick list row with the quantity/batch being edited on the device. */
data class PickLineState(
    val item: PickListItem,
    val qtyText: String = item.pickedQty.format(),
    val batchText: String = item.batchNo.orEmpty(),
    val highlighted: Boolean = false,
) {
    val qty: Double get() = qtyText.replace(',', '.').toDoubleOrNull() ?: 0.0
    val status: PickRowStatus get() = pickRowStatus(qty, item.requiredQty)
    val isComplete: Boolean get() = isPickComplete(qty, item.requiredQty, item.optional)

    /** True when the device holds a quantity or batch that has not been saved to ERPNext yet. */
    val isDirty: Boolean get() = abs(qty - item.pickedQty) > PICK_QTY_TOLERANCE || batchText.trim() != item.batchNo.orEmpty()

    fun toProgressLine(): PickProgressLine = PickProgressLine(item.rowName, qty, batchText.trim().ifBlank { null })
}

data class PickListUiState(
    val isLoading: Boolean = true,
    val pickList: PickList? = null,
    val lines: List<PickLineState> = emptyList(),
    val currentUser: String? = null,
    val isStarting: Boolean = false,
    val isSaving: Boolean = false,
    val isCompleting: Boolean = false,
    val isGenerating: Boolean = false,
    val error: UiText? = null,
    val message: UiText? = null,
    val beep: Boolean = true,
    val vibrate: Boolean = true,
) {
    val status: PickingStatus? get() = pickList?.pickingStatus
    val isBusy: Boolean get() = isStarting || isSaving || isCompleting || isGenerating
    val totalRequired: Double get() = lines.sumOf { it.item.requiredQty }
    val totalPicked: Double get() = lines.sumOf { it.qty }
    val progress: Float get() = if (totalRequired <= 0.0) 0f else (totalPicked / totalRequired).toFloat().coerceIn(0f, 1f)
    val hasUnsavedChanges: Boolean get() = lines.any { it.isDirty }
    val allRowsComplete: Boolean get() = lines.isNotEmpty() && lines.all { it.isComplete }
    val generatedDocument: GeneratedDocument? get() = pickList?.generatedDocument

    val canStart: Boolean get() = !isBusy && status == PickingStatus.READY_TO_PICK
    val canSave: Boolean get() = !isBusy && status == PickingStatus.PICKING && hasUnsavedChanges
    /** "Complete Picking" stays disabled until every mandatory row has its required quantity. */
    val canComplete: Boolean get() = !isBusy && status == PickingStatus.PICKING && allRowsComplete
    val canGenerate: Boolean
        get() = !isBusy && status == PickingStatus.PICKED && pickList?.purpose?.targetDocument != null && generatedDocument == null
}

/**
 * Drives one pick list through Ready to Pick -> Picking -> Picked -> document generation.
 * Scans (hardware wedge, intent, camera or manual) increment the matching row by one.
 */
@HiltViewModel
class PickListViewModel @Inject constructor(
    private val getPickList: GetPickListUseCase,
    private val startPickingUseCase: StartPickingUseCase,
    private val savePickProgress: SavePickProgressUseCase,
    private val completePickingUseCase: CompletePickingUseCase,
    private val generatePickDocument: GeneratePickDocumentUseCase,
    private val resolvePickScan: ResolvePickScanUseCase,
    observeScannerSettings: ObserveScannerSettingsUseCase,
    authRepository: AuthRepository,
    private val scanner: ScannerController,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val name: String = savedStateHandle.get<String>(ARG_NAME).orEmpty()

    private val _uiState = MutableStateFlow(PickListUiState())
    val uiState: StateFlow<PickListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            observeScannerSettings().collect { s -> _uiState.update { it.copy(beep = s.beepOnScan, vibrate = s.vibrateOnScan) } }
        }
        viewModelScope.launch {
            authRepository.session.collect { session -> _uiState.update { it.copy(currentUser = session?.userId) } }
        }
        load()
    }

    fun load() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = getPickList(name)) {
                is AppResult.Success -> _uiState.update { it.withPickList(result.data).copy(isLoading = false) }
                is AppResult.Failure -> _uiState.update { it.copy(isLoading = false, error = result.error.toUiText()) }
            }
        }
    }

    fun startPicking() {
        val state = _uiState.value
        val pickList = state.pickList ?: return
        if (!state.canStart) return
        _uiState.update { it.copy(isStarting = true, error = null, message = null) }
        viewModelScope.launch {
            when (val result = startPickingUseCase(pickList)) {
                is AppResult.Success -> _uiState.update {
                    it.withPickList(result.data).copy(isStarting = false, message = UiText.Res(R.string.pick_started_message))
                }
                is AppResult.Failure -> _uiState.update { it.copy(isStarting = false, error = result.error.toUiText()) }
            }
        }
    }

    fun setQty(rowName: String, text: String) = _uiState.update { state ->
        state.copy(lines = state.lines.map { if (it.item.rowName == rowName) it.copy(qtyText = text, highlighted = false) else it })
    }

    fun increment(rowName: String) = adjust(rowName, +1.0)
    fun decrement(rowName: String) = adjust(rowName, -1.0)

    fun setBatch(rowName: String, text: String) = _uiState.update { state ->
        state.copy(lines = state.lines.map { if (it.item.rowName == rowName) it.copy(batchText = text) else it })
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null, error = null) }

    /** A scanned item barcode / item code / batch label adds one to the matching row. */
    fun onScanned(code: ScannedCode) {
        val state = _uiState.value
        val pickList = state.pickList ?: return
        if (pickList.pickingStatus != PickingStatus.PICKING || state.isBusy) return
        if (code.source != ScanSource.MANUAL) scanner.feedback(state.beep, state.vibrate)
        viewModelScope.launch {
            val result = resolvePickScan(pickList, code.value) { row ->
                _uiState.value.lines.firstOrNull { it.item.rowName == row.rowName }?.qty ?: row.pickedQty
            }
            when (result) {
                is AppResult.Success -> when (val scan = result.data) {
                    is PickScanResult.Matched -> addOne(scan.row.rowName, scan.batchNo)
                    is PickScanResult.NotOnList -> _uiState.update { it.copy(error = UiText.Res(R.string.pick_not_on_list, listOf(scan.itemCode ?: scan.code))) }
                    is PickScanResult.Unknown -> _uiState.update { it.copy(error = UiText.Res(R.string.scan_item_not_found, listOf(scan.code))) }
                }
                is AppResult.Failure -> _uiState.update { it.copy(error = result.error.toUiText()) }
            }
        }
    }

    /** Syncs the partial quantities with ERPNext so picking can be paused and resumed elsewhere. */
    fun saveProgress() {
        val state = _uiState.value
        val pickList = state.pickList ?: return
        if (!state.canSave) return
        _uiState.update { it.copy(isSaving = true, error = null, message = null) }
        viewModelScope.launch {
            when (val result = savePickProgress(pickList, state.lines.map { it.toProgressLine() })) {
                is AppResult.Success -> _uiState.update { it.withPickList(result.data).copy(isSaving = false, message = UiText.Res(R.string.pick_saved)) }
                is AppResult.Failure -> _uiState.update { it.copy(isSaving = false, error = result.error.toUiText()) }
            }
        }
    }

    fun completePicking() {
        val state = _uiState.value
        val pickList = state.pickList ?: return
        if (!state.canComplete) return
        _uiState.update { it.copy(isCompleting = true, error = null, message = null) }
        viewModelScope.launch {
            when (val result = completePickingUseCase(pickList, state.lines.map { it.toProgressLine() })) {
                is AppResult.Success -> _uiState.update {
                    it.withPickList(result.data).copy(isCompleting = false, message = UiText.Res(R.string.pick_completed_message))
                }
                is AppResult.Failure -> _uiState.update { it.copy(isCompleting = false, error = result.error.toUiText()) }
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

    /** Replaces the pick list from the server, keeping unsaved quantities the user typed meanwhile. */
    private fun PickListUiState.withPickList(fresh: PickList): PickListUiState {
        val previous = lines.associateBy { it.item.rowName }
        val merged = fresh.items.map { item ->
            val old = previous[item.rowName]
            if (fresh.pickingStatus == PickingStatus.PICKING && old != null && old.isDirty && !isSaving && !isCompleting) {
                old.copy(item = item, highlighted = false)
            } else {
                PickLineState(item)
            }
        }
        return copy(pickList = fresh, lines = merged)
    }

    private fun addOne(rowName: String, batchNo: String?) = _uiState.update { state ->
        val index = state.lines.indexOfFirst { it.item.rowName == rowName }
        if (index < 0) return@update state
        val line = state.lines[index]
        val required = line.item.requiredQty
        if (line.qty + 1 > required + PICK_QTY_TOLERANCE) {
            state.copy(
                message = UiText.Res(R.string.pick_line_complete, listOf(line.item.itemCode, required.format())),
                lines = state.lines.mapIndexed { i, l -> l.copy(highlighted = i == index) },
            )
        } else {
            state.copy(
                message = UiText.Res(R.string.pick_line_added, listOf(line.item.itemCode)),
                error = null,
                lines = state.lines.mapIndexed { i, l ->
                    if (i == index) l.copy(qtyText = (l.qty + 1).format(), batchText = batchNo ?: l.batchText, highlighted = true) else l.copy(highlighted = false)
                },
            )
        }
    }

    private fun adjust(rowName: String, delta: Double) = _uiState.update { state ->
        state.copy(
            lines = state.lines.map {
                if (it.item.rowName == rowName) {
                    it.copy(qtyText = (it.qty + delta).coerceIn(0.0, it.item.requiredQty).format(), highlighted = false)
                } else {
                    it
                }
            }
        )
    }

    companion object {
        const val ARG_NAME = "pickListName"
    }
}
