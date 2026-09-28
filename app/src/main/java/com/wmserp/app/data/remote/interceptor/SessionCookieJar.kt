package com.wmserp.app.data.remote.interceptor

import com.wmserp.app.data.local.AuthMode
import com.wmserp.app.data.local.SessionStore
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Keeps Frappe's `sid` session cookie (and friends) in memory and mirrors the `sid`
 * into [SessionStore] so the session survives process death.
 */
class SessionCookieJar(private val sessionStore: SessionStore) : CookieJar {

    private val cookies = mutableMapOf<String, Cookie>()
    private val lock = Any()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            cookies.forEach { cookie ->
                if (cookie.value.isBlank() || cookie.value == "Guest") {
                    this.cookies.remove(cookie.name)
                } else {
                    this.cookies[cookie.name] = cookie
                }
                if (cookie.name == SID) {
                    val sid = cookie.value.takeIf { it.isNotBlank() && it != "Guest" }
                    sessionStore.updateInMemory { it.copy(sid = sid, sidExpiresAtMillis = if (sid == null) null else cookie.expiresAt) }
                }
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val state = sessionStore.current
        if (state.authMode == AuthMode.TOKEN) return emptyList()
        synchronized(lock) {
            // Re-hydrate the sid cookie from persisted state (e.g. after app restart).
            if (cookies[SID] == null && state.sid != null) {
                cookies[SID] = buildSidCookie(url, state.sid, state.sidExpiresAtMillis)
            }
            val now = System.currentTimeMillis()
            return cookies.values.filter { it.expiresAt > now && it.matches(url) }
        }
    }

    fun clear() = synchronized(lock) { cookies.clear() }

    private fun buildSidCookie(url: HttpUrl, sid: String, expiresAt: Long?): Cookie =
        Cookie.Builder()
            .name(SID)
            .value(sid)
            .domain(url.host)
            .path("/")
            .expiresAt(expiresAt ?: (System.currentTimeMillis() + THREE_DAYS_MILLIS))
            .build()

    companion object {
        const val SID = "sid"
        private const val THREE_DAYS_MILLIS = 3L * 24 * 60 * 60 * 1000
    }
}
