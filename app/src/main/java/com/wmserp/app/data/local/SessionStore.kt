package com.wmserp.app.data.local

import com.wmserp.app.domain.model.UserSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Which credentials are used for API calls. */
enum class AuthMode { SESSION, TOKEN }

/**
 * Snapshot of everything the networking layer needs to talk to ERPNext.
 * Kept in memory for synchronous access from OkHttp interceptors and persisted through [SecureStorage].
 */
data class SessionState(
    val baseUrl: String? = null,
    val authMode: AuthMode = AuthMode.SESSION,
    val userId: String? = null,
    val fullName: String? = null,
    val sid: String? = null,
    val sidExpiresAtMillis: Long? = null,
    val csrfToken: String? = null,
    val apiKey: String? = null,
    val apiSecret: String? = null,
    val rememberMe: Boolean = false,
    val savedUsername: String? = null,
    val savedPassword: String? = null,
) {
    val isAuthenticated: Boolean
        get() = baseUrl != null && userId != null && (
            (authMode == AuthMode.TOKEN && apiKey != null && apiSecret != null) ||
                (authMode == AuthMode.SESSION && sid != null)
            )

    fun toUserSession(): UserSession? =
        if (isAuthenticated) UserSession(userId!!, fullName ?: userId, baseUrl!!) else null
}

/**
 * Single source of truth for the current server + credentials.
 * All mutations go through [update] and are mirrored to [SecureStorage].
 */
class SessionStore(private val storage: SecureStorage) {

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state
    private val mutex = Mutex()

    /** Snapshot of what is currently written to [storage]; used to compute minimal writes. */
    private var persisted = SessionState()

    /** Current in-memory snapshot (safe to call from interceptors). */
    val current: SessionState get() = _state.value

    /** Loads the persisted state into memory. Returns the loaded state. */
    suspend fun load(): SessionState = mutex.withLock {
        val loaded = SessionState(
            baseUrl = storage.read(KEY_BASE_URL),
            authMode = storage.read(KEY_AUTH_MODE)?.let { runCatching { AuthMode.valueOf(it) }.getOrNull() } ?: AuthMode.SESSION,
            userId = storage.read(KEY_USER_ID),
            fullName = storage.read(KEY_FULL_NAME),
            sid = storage.read(KEY_SID),
            sidExpiresAtMillis = storage.read(KEY_SID_EXPIRES)?.toLongOrNull(),
            csrfToken = storage.read(KEY_CSRF),
            apiKey = storage.read(KEY_API_KEY),
            apiSecret = storage.read(KEY_API_SECRET),
            rememberMe = storage.read(KEY_REMEMBER_ME) == "true",
            savedUsername = storage.read(KEY_SAVED_USERNAME),
            savedPassword = storage.read(KEY_SAVED_PASSWORD),
        )
        _state.value = loaded
        persisted = loaded
        loaded
    }

    /** Applies [transform] to the current state and persists the result. */
    suspend fun update(transform: (SessionState) -> SessionState): SessionState = mutex.withLock {
        val next = transform(_state.value)
        _state.value = next
        persist(persisted, next)
        persisted = next
        next
    }

    /** Non-suspending in-memory only update, used by interceptors (csrf/sid refresh). Persisted lazily on next [update]. */
    fun updateInMemory(transform: (SessionState) -> SessionState) {
        _state.update(transform)
    }

    /** Persists the in-memory state (used after [updateInMemory]). */
    suspend fun flush() = mutex.withLock {
        val current = _state.value
        persist(persisted, current)
        persisted = current
    }

    /** Clears the authenticated session but keeps server URL and remembered username for the login form. */
    suspend fun clearSession(keepCredentials: Boolean) = update { s ->
        s.copy(
            userId = null,
            fullName = null,
            sid = null,
            sidExpiresAtMillis = null,
            csrfToken = null,
            apiKey = if (keepCredentials) s.apiKey else null,
            apiSecret = if (keepCredentials) s.apiSecret else null,
            savedPassword = if (keepCredentials) s.savedPassword else null,
        )
    }

    suspend fun clearAll() = mutex.withLock {
        _state.value = SessionState()
        persisted = SessionState()
        storage.clear()
    }

    private suspend fun persist(previous: SessionState, next: SessionState) {
        suspend fun put(key: String, old: String?, new: String?) {
            if (old == new) return
            if (new == null) storage.remove(key) else storage.write(key, new)
        }
        put(KEY_BASE_URL, previous.baseUrl, next.baseUrl)
        put(KEY_AUTH_MODE, previous.authMode.name, next.authMode.name)
        put(KEY_USER_ID, previous.userId, next.userId)
        put(KEY_FULL_NAME, previous.fullName, next.fullName)
        put(KEY_SID, previous.sid, next.sid)
        put(KEY_SID_EXPIRES, previous.sidExpiresAtMillis?.toString(), next.sidExpiresAtMillis?.toString())
        put(KEY_CSRF, previous.csrfToken, next.csrfToken)
        put(KEY_API_KEY, previous.apiKey, next.apiKey)
        put(KEY_API_SECRET, previous.apiSecret, next.apiSecret)
        put(KEY_REMEMBER_ME, previous.rememberMe.toString(), next.rememberMe.toString())
        put(KEY_SAVED_USERNAME, previous.savedUsername, next.savedUsername)
        put(KEY_SAVED_PASSWORD, previous.savedPassword, next.savedPassword)
    }

    companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_AUTH_MODE = "auth_mode"
        const val KEY_USER_ID = "user_id"
        const val KEY_FULL_NAME = "full_name"
        const val KEY_SID = "sid"
        const val KEY_SID_EXPIRES = "sid_expires"
        const val KEY_CSRF = "csrf_token"
        const val KEY_API_KEY = "api_key"
        const val KEY_API_SECRET = "api_secret"
        const val KEY_REMEMBER_ME = "remember_me"
        const val KEY_SAVED_USERNAME = "saved_username"
        const val KEY_SAVED_PASSWORD = "saved_password"
    }
}
