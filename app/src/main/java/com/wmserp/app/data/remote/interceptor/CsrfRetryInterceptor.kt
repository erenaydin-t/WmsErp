package com.wmserp.app.data.remote.interceptor

import com.wmserp.app.data.local.SessionStore
import com.wmserp.app.data.remote.ErpNextErrorParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Frappe enforces CSRF tokens for cookie-authenticated, non-GET requests once a token has been
 * generated for the session. If the server answers with `CSRFTokenError`, this interceptor fetches a
 * token via `frappe.sessions.get_csrf_token`, stores it and retries the original request once.
 */
class CsrfRetryInterceptor(private val sessionStore: SessionStore) : Interceptor {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (request.method == "GET" || response.isSuccessful || response.code !in setOf(400, 403, 417)) return response

        val peeked = response.peekBody(PEEK_BYTES).string()
        if (ErpNextErrorParser.exceptionTypeOf(peeked) != "CSRFTokenError") return response
        response.close()

        val token = fetchToken(chain, request) ?: return chain.proceed(request)
        sessionStore.updateInMemory { it.copy(csrfToken = token) }
        val retried = request.newBuilder().header("X-Frappe-CSRF-Token", token).build()
        return chain.proceed(retried)
    }

    private fun fetchToken(chain: Interceptor.Chain, original: Request): String? {
        val tokenUrl = original.url.newBuilder()
            .encodedPath(original.url.encodedPath.substringBefore("/api/") + "/api/method/frappe.sessions.get_csrf_token")
            .query(null)
            .build()
        val tokenRequest = Request.Builder().url(tokenUrl).get().build()
        return runCatching {
            chain.proceed(tokenRequest).use { r ->
                if (!r.isSuccessful) return null
                val body = r.body?.string() ?: return null
                json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content
            }
        }.getOrNull()
    }

    private companion object {
        const val PEEK_BYTES = 64L * 1024
    }
}
