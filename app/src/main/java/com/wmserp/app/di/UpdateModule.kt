package com.wmserp.app.di

import android.content.Context
import com.wmserp.app.BuildConfig
import com.wmserp.app.core.update.AndroidInstalledVersion
import com.wmserp.app.core.update.AppUpdateManager
import com.wmserp.app.data.remote.ApiCaller
import com.wmserp.app.data.repository.AppUpdateRepositoryImpl
import com.wmserp.app.domain.model.InstalledVersion
import com.wmserp.app.domain.repository.AppUpdateRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Plain HTTP client for GitHub: no ERPNext base-URL rewriting, cookies or auth headers. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GitHubClient

@Module
@InstallIn(SingletonComponent::class)
object UpdateModule {

    @Provides
    @Singleton
    @GitHubClient
    fun provideGitHubClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Provides
    @Singleton
    fun provideInstalledVersion(@ApplicationContext context: Context): InstalledVersion = AndroidInstalledVersion(context)

    @Provides
    @Singleton
    fun provideAppUpdateRepository(
        @ApplicationContext context: Context,
        @GitHubClient client: OkHttpClient,
        json: Json,
        apiCaller: ApiCaller,
    ): AppUpdateRepository = AppUpdateRepositoryImpl(
        client = client,
        json = json,
        apiCaller = apiCaller,
        repository = BuildConfig.UPDATE_GITHUB_REPO,
        downloadDir = File(context.filesDir, "updates"),
    )

    @Provides
    @Singleton
    fun provideAppUpdateManager(repository: AppUpdateRepository, installedVersion: InstalledVersion): AppUpdateManager =
        AppUpdateManager(repository, installedVersion, CoroutineScope(SupervisorJob() + Dispatchers.Default))
}
