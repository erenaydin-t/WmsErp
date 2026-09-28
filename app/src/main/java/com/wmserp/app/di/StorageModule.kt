package com.wmserp.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.wmserp.app.core.security.AesGcmStringCipher
import com.wmserp.app.core.security.SecretKeyProvider
import com.wmserp.app.core.security.StringCipher
import com.wmserp.app.data.local.AndroidKeystoreKeyProvider
import com.wmserp.app.data.local.AndroidSecureStorage
import com.wmserp.app.data.local.SecureStorage
import com.wmserp.app.data.local.SessionStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

private val Context.secureDataStore: DataStore<Preferences> by preferencesDataStore(name = "wmserp_secure")
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "wmserp_settings")

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SecureDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsDataStore

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    @SecureDataStore
    fun provideSecureDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.secureDataStore

    @Provides
    @Singleton
    @SettingsDataStore
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.settingsDataStore

    @Provides
    @Singleton
    fun provideKeyProvider(): SecretKeyProvider = AndroidKeystoreKeyProvider()

    @Provides
    @Singleton
    fun provideStringCipher(keyProvider: SecretKeyProvider): StringCipher = AesGcmStringCipher(keyProvider)

    @Provides
    @Singleton
    fun provideSecureStorage(@SecureDataStore dataStore: DataStore<Preferences>, cipher: StringCipher): SecureStorage =
        AndroidSecureStorage(dataStore, cipher)

    @Provides
    @Singleton
    fun provideSessionStore(storage: SecureStorage): SessionStore = SessionStore(storage)
}
