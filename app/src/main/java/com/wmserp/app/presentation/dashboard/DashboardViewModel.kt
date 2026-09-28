package com.wmserp.app.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.repository.AuthRepository
import com.wmserp.app.domain.usecase.DashboardData
import com.wmserp.app.domain.usecase.GetDashboardUseCase
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

data class DashboardUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val data: DashboardData? = null,
    val error: UiText? = null,
    val greetingName: String = "",
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val getDashboard: GetDashboardUseCase,
    authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            authRepository.session.collect { session ->
                val name = session?.fullName?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() } ?: session?.userId.orEmpty()
                _uiState.update { it.copy(greetingName = name) }
            }
        }
        load()
    }

    fun load(refresh: Boolean = false) {
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = !refresh && it.data == null, isRefreshing = refresh, error = null) }
        loadJob = viewModelScope.launch {
            when (val result = getDashboard()) {
                is AppResult.Success -> _uiState.update { it.copy(isLoading = false, isRefreshing = false, data = result.data, error = null) }
                is AppResult.Failure -> _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = result.error.toUiText()) }
            }
        }
    }

    fun refresh() = load(refresh = true)
}
