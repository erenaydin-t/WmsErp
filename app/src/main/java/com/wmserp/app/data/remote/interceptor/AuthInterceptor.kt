package com.wmserp.app.data.remote.interceptor

import com.wmserp.app.data.local.AuthMode
import com.wmserp.app.data.local.SessionStore
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds standard headers and, when the user chose API key/secret authentication,
 * the `Authorization: token key:secret` header ERPNext expects.
 * Session cookie authentication is handled by [SessionCookieJar].
 */
class AuthInterceptor(
    private val sessionStore: SessionStore,
    private val userAgent: String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val state = sessionStore.current
        val builder = chain.request().newBuilder()
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
        if (state.authMode == AuthMode.TOKEN && state.apiKey != null && state.apiSecret != null) {
            builder.header("Authorization", "token ${state.apiKey}:${state.apiSecret}")
        }
        state.csrfToken?.let { builder.header("X-Frappe-CSRF-Token", it) }
        return chain.proceed(builder.build())
    }
}
