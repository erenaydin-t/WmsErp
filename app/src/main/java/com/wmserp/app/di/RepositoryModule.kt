package com.wmserp.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.wmserp.app.data.local.FileStocktakingLocalStore
import com.wmserp.app.data.local.SessionStore
import com.wmserp.app.data.remote.ApiCaller
import com.wmserp.app.data.remote.ErpNextApi
import com.wmserp.app.data.remote.ErpNextDataSource
import com.wmserp.app.data.remote.SessionEventBus
import com.wmserp.app.data.remote.interceptor.SessionCookieJar
import com.wmserp.app.data.repository.AnalyticsRepositoryImpl
import com.wmserp.app.data.repository.AndroidSettingsRepository
import com.wmserp.app.data.repository.AuthRepositoryImpl
import com.wmserp.app.data.repository.InventoryRepositoryImpl
import com.wmserp.app.data.repository.PickListRepositoryImpl
import com.wmserp.app.data.repository.ProfileRepositoryImpl
import com.wmserp.app.data.repository.ReceiptRepositoryImpl
import com.wmserp.app.data.repository.StocktakingRepositoryImpl
import com.wmserp.app.data.util.DateProvider
import com.wmserp.app.data.util.SystemDateProvider
import com.wmserp.app.domain.repository.AnalyticsRepository
import com.wmserp.app.domain.repository.AuthRepository
import com.wmserp.app.domain.repository.InventoryRepository
import com.wmserp.app.domain.repository.PickListRepository
import com.wmserp.app.domain.repository.ProfileRepository
import com.wmserp.app.domain.repository.ReceiptRepository
import com.wmserp.app.domain.repository.SettingsRepository
import com.wmserp.app.domain.repository.StocktakingLocalStore
import com.wmserp.app.domain.repository.StocktakingRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideDateProvider(): DateProvider = SystemDateProvider()

    @Provides
    @Singleton
    fun provideAuthRepository(
        api: ErpNextApi,
        dataSource: ErpNextDataSource,
        sessionStore: SessionStore,
        cookieJar: SessionCookieJar,
        apiCaller: ApiCaller,
        eventBus: SessionEventBus,
    ): AuthRepository = AuthRepositoryImpl(api, dataSource, sessionStore, cookieJar, apiCaller, eventBus)

    @Provides
    @Singleton
    fun provideProfileRepository(dataSource: ErpNextDataSource, sessionStore: SessionStore, apiCaller: ApiCaller): ProfileRepository =
        ProfileRepositoryImpl(dataSource, sessionStore, apiCaller)

    @Provides
    @Singleton
    fun provideInventoryRepository(dataSource: ErpNextDataSource, sessionStore: SessionStore, apiCaller: ApiCaller): InventoryRepository =
        InventoryRepositoryImpl(dataSource, sessionStore, apiCaller)

    @Provides
    @Singleton
    fun provideReceiptRepository(dataSource: ErpNextDataSource, apiCaller: ApiCaller): ReceiptRepository =
        ReceiptRepositoryImpl(dataSource, apiCaller)

    @Provides
    @Singleton
    fun providePickListRepository(dataSource: ErpNextDataSource, apiCaller: ApiCaller): PickListRepository =
        PickListRepositoryImpl(dataSource, apiCaller)

    @Provides
    @Singleton
    fun provideAnalyticsRepository(dataSource: ErpNextDataSource, apiCaller: ApiCaller, dateProvider: DateProvider): AnalyticsRepository =
        AnalyticsRepositoryImpl(dataSource, apiCaller, dateProvider)

    @Provides
    @Singleton
    fun provideSettingsRepository(@SettingsDataStore dataStore: DataStore<Preferences>): SettingsRepository =
        AndroidSettingsRepository(dataStore)

    @Provides
    @Singleton
    fun provideStocktakingRepository(dataSource: ErpNextDataSource, apiCaller: ApiCaller): StocktakingRepository =
        StocktakingRepositoryImpl(dataSource, apiCaller)

    /** Rows and the offline count queue of each session, as private JSON files of the app. */
    @Provides
    @Singleton
    fun provideStocktakingLocalStore(@ApplicationContext context: Context, json: Json): StocktakingLocalStore =
        FileStocktakingLocalStore(File(context.filesDir, "stocktaking"), json)
}
