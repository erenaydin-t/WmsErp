package com.wmserp.app.domain.repository

import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.Credentials
import com.wmserp.app.domain.model.LoginPrefill
import com.wmserp.app.domain.model.SessionEvent
import com.wmserp.app.domain.model.UserSession
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    /** Emits the current session or null when signed out. */
    val session: Flow<UserSession?>

    /** One-off session events (expiry, logout). */
    val events: Flow<SessionEvent>

    suspend fun login(baseUrl: String, credentials: Credentials, rememberMe: Boolean): AppResult<UserSession>

    /** Attempts to resume a stored session (or silently re-login with remembered credentials). */
    suspend fun restoreSession(): AppResult<UserSession?>

    suspend fun logout(): AppResult<Unit>

    suspend fun getLoginPrefill(): LoginPrefill

    suspend fun changePassword(oldPassword: String, newPassword: String): AppResult<Unit>
}
