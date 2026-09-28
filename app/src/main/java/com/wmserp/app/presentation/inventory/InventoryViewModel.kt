package com.wmserp.app.presentation.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.InventoryAnalytics
import com.wmserp.app.domain.usecase.GetInventoryAnalyticsUseCase
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

enum class AnalyticsTab {
    DELIVERY_DELAYS,
    ACTIVITY_HEATMAP,
    STOCK_AGING,
}

data class InventoryUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val analytics: InventoryAnalytics? = null,
    val error: UiText? = null,
    val selectedTab: AnalyticsTab = AnalyticsTab.DELIVERY_DELAYS,
)

@HiltViewModel
class InventoryViewModel @Inject constructor(
    private val getInventoryAnalytics: GetInventoryAnalyticsUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InventoryUiState())
    val uiState: StateFlow<InventoryUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    init {
        load()
    }

    fun selectTab(tab: AnalyticsTab) = _uiState.update { it.copy(selectedTab = tab) }

    fun load(refresh: Boolean = false) {
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = !refresh && it.analytics == null, isRefreshing = refresh, error = null) }
        loadJob = viewModelScope.launch {
            when (val result = getInventoryAnalytics()) {
                is AppResult.Success -> _uiState.update { it.copy(isLoading = false, isRefreshing = false, analytics = result.data) }
                is AppResult.Failure -> _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = result.error.toUiText()) }
            }
        }
    }

    fun refresh() = load(refresh = true)
}
