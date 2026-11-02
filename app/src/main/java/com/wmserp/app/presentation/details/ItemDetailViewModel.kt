package com.wmserp.app.presentation.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.ItemDetail
import com.wmserp.app.domain.usecase.GetItemDetailUseCase
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

data class ItemDetailUiState(
    /** The item the sheet shows; null when the sheet is closed. */
    val itemCode: String? = null,
    val isLoading: Boolean = false,
    val detail: ItemDetail? = null,
    val error: UiText? = null,
) {
    val isOpen: Boolean get() = itemCode != null
}

/**
 * Backs the item detail bottom sheet. Any screen that lists items (dashboard activity, stock aging,
 * browse lists, order rows) calls [open] with the item code; the sheet loads the item and its stock.
 */
@HiltViewModel
class ItemDetailViewModel @Inject constructor(
    private val getItemDetail: GetItemDetailUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ItemDetailUiState())
    val uiState: StateFlow<ItemDetailUiState> = _uiState.asStateFlow()
    private var job: Job? = null

    fun open(itemCode: String) {
        val code = itemCode.trim()
        if (code.isEmpty()) return
        job?.cancel()
        _uiState.value = ItemDetailUiState(itemCode = code, isLoading = true)
        job = viewModelScope.launch {
            when (val result = getItemDetail(code)) {
                is AppResult.Success -> _uiState.update { if (it.itemCode == code) it.copy(isLoading = false, detail = result.data) else it }
                is AppResult.Failure -> _uiState.update { if (it.itemCode == code) it.copy(isLoading = false, error = result.error.toUiText()) else it }
            }
        }
    }

    fun retry() {
        _uiState.value.itemCode?.let { open(it) }
    }

    fun close() {
        job?.cancel()
        _uiState.value = ItemDetailUiState()
    }
}
