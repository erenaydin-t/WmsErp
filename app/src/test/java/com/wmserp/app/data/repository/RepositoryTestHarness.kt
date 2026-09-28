package com.wmserp.app.data.repository

import com.wmserp.app.data.local.InMemorySecureStorage
import com.wmserp.app.data.local.SessionStore
import com.wmserp.app.data.remote.ApiCaller
import com.wmserp.app.data.remote.ErpNextApi
import com.wmserp.app.data.remote.ErpNextDataSource
import com.wmserp.app.data.remote.SessionEventBus
import com.wmserp.app.data.remote.interceptor.AuthInterceptor
import com.wmserp.app.data.remote.interceptor.BaseUrlInterceptor
import com.wmserp.app.data.remote.interceptor.CsrfRetryInterceptor
import com.wmserp.app.data.remote.interceptor.SessionCookieJar
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Wires the real Retrofit/OkHttp stack (interceptors, cookie jar, JSON) against a MockWebServer. */
class RepositoryTestHarness {
    val server = MockWebServer()
    val storage = InMemorySecureStorage()
    val sessionStore = SessionStore(storage)
    val eventBus = SessionEventBus()
    val apiCaller = ApiCaller(eventBus)
    val cookieJar = SessionCookieJar(sessionStore)
    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false; encodeDefaults = false }

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor(BaseUrlInterceptor(sessionStore))
        .addInterceptor(AuthInterceptor(sessionStore, "WmsErp-test"))
        .addInterceptor(CsrfRetryInterceptor(sessionStore))
        .build()

    val api: ErpNextApi = Retrofit.Builder()
        .baseUrl("https://erpnext.invalid/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ErpNextApi::class.java)

    val dataSource = ErpNextDataSource(api, json)

    fun start() {
        server.start()
    }

    /** Base URL of the mock server without trailing slash, as the app would store it. */
    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    fun shutdown() {
        server.shutdown()
    }
}
