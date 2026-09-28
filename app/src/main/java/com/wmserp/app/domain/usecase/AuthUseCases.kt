package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.common.UrlNormalizer
import com.wmserp.app.domain.model.Credentials
import com.wmserp.app.domain.model.LoginPrefill
import com.wmserp.app.domain.model.UserSession
import com.wmserp.app.domain.repository.AuthRepository
import javax.inject.Inject

/** Validates the login form, normalises the server URL and performs the ERPNext login. */
class LoginUseCase @Inject constructor(private val authRepository: AuthRepository) {

    suspend operator fun invoke(
        rawUrl: String,
        credentials: Credentials,
        rememberMe: Boolean,
    ): AppResult<UserSession> {
        val baseUrl = UrlNormalizer.normalize(rawUrl)
            ?: return AppResult.Failure(AppError.Validation("Enter a valid ERPNext URL, e.g. https://erp.company.com"))

        when (credentials) {
            is Credentials.Password -> {
                if (credentials.username.isBlank()) {
                    return AppResult.Failure(AppError.Validation("Username or email is required"))
                }
                if (credentials.password.isEmpty()) {
                    return AppResult.Failure(AppError.Validation("Password is required"))
                }
            }
            is Credentials.ApiToken -> {
                if (credentials.apiKey.isBlank() || credentials.apiSecret.isBlank()) {
                    return AppResult.Failure(AppError.Validation("API key and API secret are required"))
                }
            }
        }
        val trimmed = when (credentials) {
            is Credentials.Password -> credentials.copy(username = credentials.username.trim())
            is Credentials.ApiToken -> credentials.copy(apiKey = credentials.apiKey.trim(), apiSecret = credentials.apiSecret.trim())
        }
        return authRepository.login(baseUrl, trimmed, rememberMe)
    }
}

class LogoutUseCase @Inject constructor(private val authRepository: AuthRepository) {
    suspend operator fun invoke(): AppResult<Unit> = authRepository.logout()
}

class RestoreSessionUseCase @Inject constructor(private val authRepository: AuthRepository) {
    suspend operator fun invoke(): AppResult<UserSession?> = authRepository.restoreSession()
}

class GetLoginPrefillUseCase @Inject constructor(private val authRepository: AuthRepository) {
    suspend operator fun invoke(): LoginPrefill = authRepository.getLoginPrefill()
}

class ChangePasswordUseCase @Inject constructor(private val authRepository: AuthRepository) {
    suspend operator fun invoke(oldPassword: String, newPassword: String, confirmPassword: String): AppResult<Unit> {
        if (oldPassword.isEmpty()) return AppResult.Failure(AppError.Validation("Current password is required"))
        if (newPassword.length < MIN_PASSWORD_LENGTH) {
            return AppResult.Failure(AppError.Validation("New password must be at least $MIN_PASSWORD_LENGTH characters"))
        }
        if (newPassword != confirmPassword) return AppResult.Failure(AppError.Validation("Passwords do not match"))
        if (newPassword == oldPassword) return AppResult.Failure(AppError.Validation("New password must differ from the current one"))
        return authRepository.changePassword(oldPassword, newPassword)
    }

    companion object {
        const val MIN_PASSWORD_LENGTH = 8
    }
}
