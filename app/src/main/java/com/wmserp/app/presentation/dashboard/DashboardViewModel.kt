package com.wmserp.app.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmserp.app.core.update.AppUpdateManager
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.PickerKpis
import com.wmserp.app.domain.model.UpdateState
import com.wmserp.app.domain.repository.AuthRepository
import com.wmserp.app.domain.usecase.DashboardData
import com.wmserp.app.domain.usecase.GetDashboardUseCase
import com.wmserp.app.domain.usecase.GetPickerKpisUseCase
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.presentation.common.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
    /** Today's picking statistics; null when the wmserp_picking app is not installed or unreachable. */
    val pickerKpis: PickerKpis? = null,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val getDashboard: GetDashboardUseCase,
    private val getPickerKpis: GetPickerKpisUseCase,
    authRepository: AuthRepository,
    private val updateManager: AppUpdateManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    /** In-app updater (GitHub Releases); the check is throttled by the manager. */
    val updateState: StateFlow<UpdateState> = updateManager.state

    init {
        updateManager.checkForUpdate()
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
            val dashboard = async { getDashboard() }
            val kpis = async { getPickerKpis() }
            val kpiResult = kpis.await()
            when (val result = dashboard.await()) {
                is AppResult.Success -> _uiState.update {
                    it.copy(isLoading = false, isRefreshing = false, data = result.data, error = null, pickerKpis = kpiResult.getOrNull() ?: it.pickerKpis)
                }
                is AppResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, isRefreshing = false, error = result.error.toUiText(), pickerKpis = kpiResult.getOrNull() ?: it.pickerKpis)
                }
            }
        }
    }

    fun refresh() = load(refresh = true)

    fun downloadUpdate() {
        updateManager.download()
    }

    fun dismissUpdate() = updateManager.dismiss()
}
