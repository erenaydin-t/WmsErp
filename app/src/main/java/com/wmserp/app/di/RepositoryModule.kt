package com.wmserp.app.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
import com.wmserp.app.data.repository.OrderRepositoryImpl
import com.wmserp.app.data.repository.PickListRepositoryImpl
import com.wmserp.app.data.repository.ProfileRepositoryImpl
import com.wmserp.app.data.util.DateProvider
import com.wmserp.app.data.util.SystemDateProvider
import com.wmserp.app.domain.repository.AnalyticsRepository
import com.wmserp.app.domain.repository.AuthRepository
import com.wmserp.app.domain.repository.InventoryRepository
import com.wmserp.app.domain.repository.OrderRepository
import com.wmserp.app.domain.repository.PickListRepository
import com.wmserp.app.domain.repository.ProfileRepository
import com.wmserp.app.domain.repository.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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
    fun provideOrderRepository(dataSource: ErpNextDataSource, apiCaller: ApiCaller, dateProvider: DateProvider): OrderRepository =
        OrderRepositoryImpl(dataSource, apiCaller, dateProvider)

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
}
