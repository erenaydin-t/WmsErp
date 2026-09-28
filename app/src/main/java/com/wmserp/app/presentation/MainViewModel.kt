package com.wmserp.app.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.SessionEvent
import com.wmserp.app.domain.model.UserSession
import com.wmserp.app.domain.repository.AuthRepository
import com.wmserp.app.domain.usecase.RestoreSessionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SessionStatus {
    data object Loading : SessionStatus
    data class SignedOut(val message: String? = null) : SessionStatus
    data class SignedIn(val session: UserSession) : SessionStatus
}

data class MainUiState(val status: SessionStatus = SessionStatus.Loading)

/** App-level session state: restores the stored session on launch and reacts to expiry/logout. */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val restoreSession: RestoreSessionUseCase,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    @Volatile
    private var restoreCompleted = false

    @Volatile
    private var recovering = false

    init {
        viewModelScope.launch {
            val status = when (val result = restoreSession()) {
                is AppResult.Success -> result.data?.let { SessionStatus.SignedIn(it) } ?: SessionStatus.SignedOut()
                is AppResult.Failure -> SessionStatus.SignedOut(result.error.message)
            }
            restoreCompleted = true
            _uiState.value = MainUiState(status)
        }
        viewModelScope.launch {
            authRepository.events.collect { event ->
                when (event) {
                    SessionEvent.Expired -> recoverFromExpiry()
                    SessionEvent.LoggedOut -> _uiState.value = MainUiState(SessionStatus.SignedOut())
                }
            }
        }
        viewModelScope.launch {
            authRepository.session.collect { session ->
                if (restoreCompleted && session != null && !recovering) {
                    _uiState.value = MainUiState(SessionStatus.SignedIn(session))
                }
            }
        }
    }

    /** Tries a silent re-login (remember me) before sending the user back to the login screen. */
    private suspend fun recoverFromExpiry() {
        if (recovering) return
        recovering = true
        val session = try {
            restoreSession().getOrNull()
        } finally {
            recovering = false
        }
        _uiState.value = if (session != null) {
            MainUiState(SessionStatus.SignedIn(session))
        } else {
            MainUiState(SessionStatus.SignedOut(SESSION_EXPIRED_MESSAGE))
        }
    }

    fun consumeMessage() {
        val status = _uiState.value.status
        if (status is SessionStatus.SignedOut && status.message != null) {
            _uiState.value = MainUiState(SessionStatus.SignedOut())
        }
    }

    companion object {
        const val SESSION_EXPIRED_MESSAGE = "Your session has expired. Please sign in again."
    }
}
