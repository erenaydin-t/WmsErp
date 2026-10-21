package com.wmserp.app.presentation.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.OrderDoctype
import com.wmserp.app.domain.model.OrderDocument
import com.wmserp.app.domain.usecase.GetDocumentUseCase
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.presentation.common.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A document to open: its doctype and name. */
data class DocumentRef(val doctype: OrderDoctype, val name: String)

data class DocumentDetailUiState(
    val ref: DocumentRef? = null,
    val isLoading: Boolean = false,
    val document: OrderDocument? = null,
    val error: UiText? = null,
) {
    val isOpen: Boolean get() = ref != null
}

/** Backs the read-only document bottom sheet (Sales Order, Purchase Order, Receipt, Delivery Note, Pick List). */
@HiltViewModel
class DocumentDetailViewModel @Inject constructor(
    private val getDocument: GetDocumentUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DocumentDetailUiState())
    val uiState: StateFlow<DocumentDetailUiState> = _uiState.asStateFlow()
    private var job: Job? = null

    fun open(doctype: OrderDoctype, name: String) = open(DocumentRef(doctype, name.trim()))

    /** Opens the sheet with what the list already knows, then loads the rows. */
    fun open(document: OrderDocument) {
        val ref = DocumentRef(document.doctype, document.name)
        open(ref, preview = document)
    }

    fun open(ref: DocumentRef, preview: OrderDocument? = null) {
        if (ref.name.isEmpty()) return
        job?.cancel()
        _uiState.value = DocumentDetailUiState(ref = ref, isLoading = true, document = preview)
        job = viewModelScope.launch {
            when (val result = getDocument(ref.doctype, ref.name)) {
                is AppResult.Success -> _uiState.update { if (it.ref == ref) it.copy(isLoading = false, document = result.data) else it }
                is AppResult.Failure -> _uiState.update { if (it.ref == ref) it.copy(isLoading = false, error = result.error.toUiText()) else it }
            }
        }
    }

    fun retry() {
        _uiState.value.ref?.let { open(it, preview = _uiState.value.document) }
    }

    fun close() {
        job?.cancel()
        _uiState.value = DocumentDetailUiState()
    }
}
