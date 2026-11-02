package com.wmserp.app.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmserp.app.R
import com.wmserp.app.core.scanner.ScannerController
import com.wmserp.app.core.update.AppUpdateManager
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.AppLanguage
import com.wmserp.app.domain.model.ScannerMode
import com.wmserp.app.domain.model.ScannerSettings
import com.wmserp.app.domain.model.UpdateState
import com.wmserp.app.domain.model.UserProfile
import com.wmserp.app.domain.repository.AuthRepository
import com.wmserp.app.domain.usecase.ChangePasswordUseCase
import com.wmserp.app.domain.usecase.GetProfileUseCase
import com.wmserp.app.domain.usecase.LogoutUseCase
import com.wmserp.app.domain.usecase.ObserveAppLanguageUseCase
import com.wmserp.app.domain.usecase.ObserveScannerSettingsUseCase
import com.wmserp.app.domain.usecase.SetAppLanguageUseCase
import com.wmserp.app.domain.usecase.UpdateScannerSettingsUseCase
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.presentation.common.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Profile & settings. The account section is read-only: it shows what the ERPNext `User` holds
 * (name, contact details, roles) and personal details are maintained in ERPNext itself. What the
 * user can change here is their password and the device settings (scanner, language, updates).
 */
data class ProfileUiState(
    val isLoading: Boolean = true,
    val profile: UserProfile? = null,
    val error: UiText? = null,
    val oldPassword: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val passwordVisible: Boolean = false,
    val isChangingPassword: Boolean = false,
    val passwordMessage: UiText? = null,
    val passwordError: UiText? = null,
    val scannerSettings: ScannerSettings = ScannerSettings(),
    val hasHardwareScanner: Boolean = false,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val serverUrl: String = "",
    val isLoggingOut: Boolean = false,
) {
    val canChangePassword: Boolean get() = !isChangingPassword && oldPassword.isNotEmpty() && newPassword.isNotEmpty() && confirmPassword.isNotEmpty()
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val getProfile: GetProfileUseCase,
    private val changePassword: ChangePasswordUseCase,
    private val logout: LogoutUseCase,
    observeScannerSettings: ObserveScannerSettingsUseCase,
    private val updateScannerSettings: UpdateScannerSettingsUseCase,
    observeAppLanguage: ObserveAppLanguageUseCase,
    private val setAppLanguage: SetAppLanguageUseCase,
    authRepository: AuthRepository,
    scanner: ScannerController,
    private val updateManager: AppUpdateManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState(hasHardwareScanner = scanner.hasHardwareScanner))
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    /** In-app updater state shared with the dashboard banner. */
    val updateState: StateFlow<UpdateState> = updateManager.state
    val currentVersion: String get() = updateManager.currentVersion

    fun checkForUpdate() {
        updateManager.checkForUpdate(force = true)
    }

    fun downloadUpdate() {
        updateManager.download()
    }

    fun cancelUpdate() = updateManager.cancelDownload()

    init {
        viewModelScope.launch { observeScannerSettings().collect { s -> _uiState.update { it.copy(scannerSettings = s) } } }
        viewModelScope.launch { observeAppLanguage().collect { l -> _uiState.update { it.copy(language = l) } } }
        viewModelScope.launch { authRepository.session.collect { s -> _uiState.update { it.copy(serverUrl = s?.baseUrl.orEmpty()) } } }
        load()
    }

    /** Reads the profile from the server ([forceRefresh] bypasses the cached copy). */
    fun load(forceRefresh: Boolean = false) {
        _uiState.update { it.copy(isLoading = it.profile == null, error = null) }
        viewModelScope.launch {
            when (val result = getProfile(forceRefresh)) {
                is AppResult.Success -> _uiState.update { it.copy(isLoading = false, profile = result.data, error = null) }
                is AppResult.Failure -> _uiState.update { it.copy(isLoading = false, error = result.error.toUiText()) }
            }
        }
    }

    fun dismissMessages() = _uiState.update { it.copy(passwordMessage = null, passwordError = null, error = null) }

    fun onOldPasswordChange(v: String) = _uiState.update { it.copy(oldPassword = v, passwordError = null, passwordMessage = null) }
    fun onNewPasswordChange(v: String) = _uiState.update { it.copy(newPassword = v, passwordError = null, passwordMessage = null) }
    fun onConfirmPasswordChange(v: String) = _uiState.update { it.copy(confirmPassword = v, passwordError = null, passwordMessage = null) }
    fun togglePasswordVisibility() = _uiState.update { it.copy(passwordVisible = !it.passwordVisible) }

    fun submitPasswordChange() {
        val state = _uiState.value
        if (!state.canChangePassword) return
        _uiState.update { it.copy(isChangingPassword = true, passwordError = null, passwordMessage = null) }
        viewModelScope.launch {
            when (val result = changePassword(state.oldPassword, state.newPassword, state.confirmPassword)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(
                        isChangingPassword = false,
                        oldPassword = "",
                        newPassword = "",
                        confirmPassword = "",
                        passwordMessage = UiText.Res(R.string.profile_password_changed),
                    )
                }
                is AppResult.Failure -> _uiState.update { it.copy(isChangingPassword = false, passwordError = result.error.toUiText()) }
            }
        }
    }

    fun setScannerMode(mode: ScannerMode) = viewModelScope.launch { updateScannerSettings.setMode(mode) }
    fun setBeep(enabled: Boolean) = viewModelScope.launch { updateScannerSettings.setBeep(enabled) }
    fun setVibrate(enabled: Boolean) = viewModelScope.launch { updateScannerSettings.setVibrate(enabled) }
    fun setAskQuantity(enabled: Boolean) = viewModelScope.launch { updateScannerSettings.setAskQuantity(enabled) }
    fun setLanguage(language: AppLanguage) = viewModelScope.launch { setAppLanguage(language) }

    fun signOut() {
        if (_uiState.value.isLoggingOut) return
        _uiState.update { it.copy(isLoggingOut = true) }
        viewModelScope.launch {
            logout()
            _uiState.update { it.copy(isLoggingOut = false) }
        }
    }
}
