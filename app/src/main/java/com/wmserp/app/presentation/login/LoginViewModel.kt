package com.wmserp.app.presentation.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.Credentials
import com.wmserp.app.domain.model.UserSession
import com.wmserp.app.domain.usecase.GetLoginPrefillUseCase
import com.wmserp.app.domain.usecase.LoginUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val rememberMe: Boolean = false,
    val passwordVisible: Boolean = false,
    val showAdvanced: Boolean = false,
    val useApiToken: Boolean = false,
    val apiKey: String = "",
    val apiSecret: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val prefillLoaded: Boolean = false,
) {
    val isInsecureUrl: Boolean get() = url.trim().startsWith("http://", ignoreCase = true)

    val canSubmit: Boolean
        get() = !isLoading && url.isNotBlank() && if (useApiToken) {
            apiKey.isNotBlank() && apiSecret.isNotBlank()
        } else {
            username.isNotBlank() && password.isNotEmpty()
        }
}

sealed interface LoginEvent {
    data class LoggedIn(val session: UserSession) : LoginEvent
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val loginUseCase: LoginUseCase,
    private val getLoginPrefill: GetLoginPrefillUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<LoginEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<LoginEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            val prefill = getLoginPrefill()
            _uiState.update {
                it.copy(
                    url = if (it.url.isBlank()) prefill.baseUrl else it.url,
                    username = if (it.username.isBlank()) prefill.username else it.username,
                    rememberMe = prefill.rememberMe,
                    prefillLoaded = true,
                )
            }
        }
    }

    fun onUrlChange(value: String) = _uiState.update { it.copy(url = value, error = null) }
    fun onUsernameChange(value: String) = _uiState.update { it.copy(username = value, error = null) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, error = null) }
    fun onRememberMeChange(value: Boolean) = _uiState.update { it.copy(rememberMe = value) }
    fun togglePasswordVisibility() = _uiState.update { it.copy(passwordVisible = !it.passwordVisible) }
    fun toggleAdvanced() = _uiState.update { it.copy(showAdvanced = !it.showAdvanced) }
    fun onUseApiTokenChange(value: Boolean) = _uiState.update { it.copy(useApiToken = value, error = null) }
    fun onApiKeyChange(value: String) = _uiState.update { it.copy(apiKey = value, error = null) }
    fun onApiSecretChange(value: String) = _uiState.update { it.copy(apiSecret = value, error = null) }
    fun dismissError() = _uiState.update { it.copy(error = null) }

    fun login() {
        val state = _uiState.value
        if (!state.canSubmit) return
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val credentials = if (state.useApiToken) {
                Credentials.ApiToken(state.apiKey, state.apiSecret)
            } else {
                Credentials.Password(state.username, state.password)
            }
            when (val result = loginUseCase(state.url, credentials, state.rememberMe)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(isLoading = false, password = "", error = null) }
                    _events.tryEmit(LoginEvent.LoggedIn(result.data))
                }
                is AppResult.Failure -> _uiState.update { it.copy(isLoading = false, error = result.error.message) }
            }
        }
    }
}
