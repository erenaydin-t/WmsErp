package com.wmserp.app.data.repository

import com.wmserp.app.data.local.AuthMode
import com.wmserp.app.data.local.SessionState
import com.wmserp.app.data.local.SessionStore
import com.wmserp.app.data.remote.ApiCaller
import com.wmserp.app.data.remote.ErpNextApi
import com.wmserp.app.data.remote.ErpNextDataSource
import com.wmserp.app.data.remote.ErpNextErrorParser
import com.wmserp.app.data.remote.SessionEventBus
import com.wmserp.app.data.remote.dto.UserDto
import com.wmserp.app.data.remote.interceptor.SessionCookieJar
import com.wmserp.app.data.util.ignoringErrors
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppException
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.common.ErrorCode
import com.wmserp.app.domain.model.Credentials
import com.wmserp.app.domain.model.LoginPrefill
import com.wmserp.app.domain.model.SessionEvent
import com.wmserp.app.domain.model.UserSession
import com.wmserp.app.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class AuthRepositoryImpl(
    private val api: ErpNextApi,
    private val dataSource: ErpNextDataSource,
    private val sessionStore: SessionStore,
    private val cookieJar: SessionCookieJar,
    private val apiCaller: ApiCaller,
    private val eventBus: SessionEventBus,
) : AuthRepository {

    override val session: Flow<UserSession?> = sessionStore.state.map { it.toUserSession() }.distinctUntilChanged()

    override val events: Flow<SessionEvent> = eventBus.events

    override suspend fun login(baseUrl: String, credentials: Credentials, rememberMe: Boolean): AppResult<UserSession> {
        cookieJar.clear()
        sessionStore.update { s ->
            s.copy(
                baseUrl = baseUrl,
                authMode = if (credentials is Credentials.ApiToken) AuthMode.TOKEN else AuthMode.SESSION,
                userId = null,
                fullName = null,
                sid = null,
                sidExpiresAtMillis = null,
                csrfToken = null,
                apiKey = (credentials as? Credentials.ApiToken)?.apiKey,
                apiSecret = (credentials as? Credentials.ApiToken)?.apiSecret,
                rememberMe = rememberMe,
            )
        }
        return when (credentials) {
            is Credentials.Password -> loginWithPassword(baseUrl, credentials, rememberMe)
            is Credentials.ApiToken -> loginWithToken(baseUrl, rememberMe)
        }
    }

    private suspend fun loginWithPassword(
        baseUrl: String,
        credentials: Credentials.Password,
        rememberMe: Boolean,
    ): AppResult<UserSession> = apiCaller.call(notifyOnAuthFailure = false) {
        val response = api.login(credentials.username, credentials.password)
        if (!response.isSuccessful) {
            val body = ignoringErrors { response.errorBody()?.string() }
            val error = ErpNextErrorParser.parse(response.code(), body)
            throw AppException(
                if (error is AppError.Unauthorized || response.code() == 401) {
                    AppError.Unauthorized("Invalid username or password", ErrorCode.INVALID_CREDENTIALS)
                } else {
                    error
                }
            )
        }
        val fullNameFromLogin = response.body()?.fullName?.takeIf { it.isNotBlank() }
        if (sessionStore.current.sid == null) {
            throw AppException(AppError.Server("Login succeeded but ERPNext did not return a session cookie", code = ErrorCode.NO_SESSION_COOKIE))
        }
        val userId = dataSource.loggedUser()?.takeIf { it.isNotBlank() && it != "Guest" } ?: credentials.username
        val fullName = fullNameFromLogin ?: userId
        sessionStore.update { s ->
            s.copy(
                userId = userId,
                fullName = fullName,
                rememberMe = rememberMe,
                savedUsername = credentials.username,
                savedPassword = if (rememberMe) credentials.password else null,
            )
        }
        UserSession(userId = userId, fullName = fullName, baseUrl = baseUrl)
    }

    private suspend fun loginWithToken(baseUrl: String, rememberMe: Boolean): AppResult<UserSession> {
        val result = apiCaller.call(notifyOnAuthFailure = false) {
            val userId = dataSource.loggedUser()?.takeIf { it.isNotBlank() && it != "Guest" }
                ?: throw AppException(AppError.Unauthorized("Invalid API key or secret", ErrorCode.INVALID_API_TOKEN))
            val user = ignoringErrors { dataSource.getDocOrNull<UserDto>("User", userId) }
            val fullName = user?.fullName?.takeIf { it.isNotBlank() } ?: userId
            sessionStore.update { s ->
                s.copy(
                    userId = userId,
                    fullName = fullName,
                    rememberMe = rememberMe,
                    savedUsername = userId,
                    savedPassword = null,
                    // Token credentials are only persisted when the user asked to be remembered.
                    apiKey = s.apiKey,
                    apiSecret = s.apiSecret,
                )
            }
            UserSession(userId, fullName, baseUrl)
        }
        if (result is AppResult.Failure) {
            sessionStore.update { it.copy(apiKey = null, apiSecret = null, userId = null) }
            if (result.error is AppError.Unauthorized) {
                return AppResult.Failure(AppError.Unauthorized("Invalid API key or secret", ErrorCode.INVALID_API_TOKEN))
            }
        }
        return result
    }

    override suspend fun restoreSession(): AppResult<UserSession?> {
        val state = sessionStore.load()
        if (state.baseUrl == null) return AppResult.Success(null)

        if (!state.isAuthenticated) return reLoginIfRemembered(state)

        val check = apiCaller.call(notifyOnAuthFailure = false) { dataSource.loggedUser() }
        return when (check) {
            is AppResult.Success -> {
                val user = check.data
                if (user.isNullOrBlank() || user == "Guest") reLoginIfRemembered(state) else AppResult.Success(state.toUserSession())
            }
            is AppResult.Failure -> when (check.error) {
                is AppError.Unauthorized -> reLoginIfRemembered(state)
                // Offline: keep the cached session and let individual screens surface network errors.
                else -> AppResult.Success(state.toUserSession())
            }
        }
    }

    private suspend fun reLoginIfRemembered(state: SessionState): AppResult<UserSession?> {
        val baseUrl = state.baseUrl ?: return AppResult.Success(null)
        val username = state.savedUsername
        val password = state.savedPassword
        if (state.authMode == AuthMode.SESSION && state.rememberMe && !username.isNullOrBlank() && !password.isNullOrEmpty()) {
            return when (val result = login(baseUrl, Credentials.Password(username, password), rememberMe = true)) {
                is AppResult.Success -> AppResult.Success(result.data)
                is AppResult.Failure -> {
                    if (result.error is AppError.Unauthorized) {
                        sessionStore.clearSession(keepCredentials = false)
                        AppResult.Success(null)
                    } else {
                        AppResult.Failure(result.error)
                    }
                }
            }
        }
        sessionStore.clearSession(keepCredentials = false)
        return AppResult.Success(null)
    }

    override suspend fun logout(): AppResult<Unit> {
        val state = sessionStore.current
        if (state.isAuthenticated && state.authMode == AuthMode.SESSION) {
            ignoringErrors { api.logout() }
        }
        cookieJar.clear()
        sessionStore.clearSession(keepCredentials = false)
        eventBus.emit(SessionEvent.LoggedOut)
        return AppResult.Success(Unit)
    }

    override suspend fun getLoginPrefill(): LoginPrefill {
        val state = if (sessionStore.current.baseUrl == null) sessionStore.load() else sessionStore.current
        return LoginPrefill(
            baseUrl = state.baseUrl.orEmpty(),
            username = state.savedUsername.orEmpty(),
            rememberMe = state.rememberMe,
        )
    }

    override suspend fun changePassword(oldPassword: String, newPassword: String): AppResult<Unit> {
        val result = apiCaller.call(notifyOnAuthFailure = false) {
            api.updatePassword(oldPassword = oldPassword, newPassword = newPassword, logoutAllSessions = 0)
            Unit
        }
        return when (result) {
            is AppResult.Success -> {
                if (sessionStore.current.rememberMe && sessionStore.current.savedPassword != null) {
                    sessionStore.update { it.copy(savedPassword = newPassword) }
                }
                // ERPNext may rotate the session cookie after a password change.
                sessionStore.flush()
                result
            }
            is AppResult.Failure -> if (result.error is AppError.Unauthorized) {
                AppResult.Failure(AppError.Validation("Current password is incorrect", ErrorCode.CURRENT_PASSWORD_INCORRECT))
            } else {
                result
            }
        }
    }
}
