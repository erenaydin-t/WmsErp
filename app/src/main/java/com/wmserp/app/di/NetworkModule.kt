package com.wmserp.app.di

import com.wmserp.app.BuildConfig
import com.wmserp.app.data.local.SessionStore
import com.wmserp.app.data.remote.ApiCaller
import com.wmserp.app.data.remote.ErpNextApi
import com.wmserp.app.data.remote.ErpNextDataSource
import com.wmserp.app.data.remote.SessionEventBus
import com.wmserp.app.data.remote.interceptor.AuthInterceptor
import com.wmserp.app.data.remote.interceptor.BaseUrlInterceptor
import com.wmserp.app.data.remote.interceptor.CsrfRetryInterceptor
import com.wmserp.app.data.remote.interceptor.SessionCookieJar
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** Placeholder host; [BaseUrlInterceptor] swaps in the ERPNext URL entered at login. */
    private const val PLACEHOLDER_BASE_URL = "https://erpnext.invalid/"

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Provides
    @Singleton
    fun provideSessionEventBus(): SessionEventBus = SessionEventBus()

    @Provides
    @Singleton
    fun provideApiCaller(eventBus: SessionEventBus): ApiCaller = ApiCaller(eventBus)

    @Provides
    @Singleton
    fun provideCookieJar(sessionStore: SessionStore): SessionCookieJar = SessionCookieJar(sessionStore)

    @Provides
    @Singleton
    fun provideOkHttpClient(sessionStore: SessionStore, cookieJar: SessionCookieJar): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .addInterceptor(BaseUrlInterceptor(sessionStore))
            .addInterceptor(AuthInterceptor(sessionStore, "WmsErp/${BuildConfig.VERSION_NAME} (Android)"))
            .addInterceptor(CsrfRetryInterceptor(sessionStore))
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                    redactHeader("Authorization")
                    redactHeader("Cookie")
                    redactHeader("Set-Cookie")
                }
            )
        }
        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideErpNextApi(retrofit: Retrofit): ErpNextApi = retrofit.create(ErpNextApi::class.java)

    @Provides
    @Singleton
    fun provideDataSource(api: ErpNextApi, json: Json): ErpNextDataSource = ErpNextDataSource(api, json)
}
