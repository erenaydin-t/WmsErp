package com.wmserp.app.data.remote.interceptor

import com.wmserp.app.data.local.SessionStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Thrown when a request is attempted before an ERPNext server URL has been configured. */
class NoServerConfiguredException(message: String) : IOException(message)

/**
 * Rewrites every request so that it targets the ERPNext instance the user configured at login.
 * Retrofit is created with a placeholder base URL; only the relative path/query is kept.
 */
class BaseUrlInterceptor(private val sessionStore: SessionStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val baseUrl = sessionStore.current.baseUrl
            ?: throw NoServerConfiguredException("No ERPNext server configured. Please sign in again.")
        val base = baseUrl.toHttpUrlOrNull()
            ?: throw NoServerConfiguredException("The configured ERPNext URL is invalid: $baseUrl")

        val basePath = base.encodedPath.trimEnd('/')
        val newUrl = request.url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .encodedPath(basePath + request.url.encodedPath)
            .build()
        return chain.proceed(request.newBuilder().url(newUrl).build())
    }
}
