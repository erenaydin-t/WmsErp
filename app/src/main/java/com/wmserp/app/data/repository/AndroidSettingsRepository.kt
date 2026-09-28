package com.wmserp.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wmserp.app.domain.model.AppLanguage
import com.wmserp.app.domain.model.ScannerMode
import com.wmserp.app.domain.model.ScannerSettings
import com.wmserp.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Non-sensitive user preferences stored in plain Preferences DataStore. */
class AndroidSettingsRepository(private val dataStore: DataStore<Preferences>) : SettingsRepository {

    override val scannerSettings: Flow<ScannerSettings> = dataStore.data.map { prefs ->
        ScannerSettings(
            mode = prefs[KEY_SCANNER_MODE]?.let { runCatching { ScannerMode.valueOf(it) }.getOrNull() } ?: ScannerMode.AUTO,
            beepOnScan = prefs[KEY_BEEP] ?: true,
            vibrateOnScan = prefs[KEY_VIBRATE] ?: true,
        )
    }

    override suspend fun setScannerMode(mode: ScannerMode) {
        dataStore.edit { it[KEY_SCANNER_MODE] = mode.name }
    }

    override suspend fun setBeepOnScan(enabled: Boolean) {
        dataStore.edit { it[KEY_BEEP] = enabled }
    }

    override suspend fun setVibrateOnScan(enabled: Boolean) {
        dataStore.edit { it[KEY_VIBRATE] = enabled }
    }

    override val appLanguage: Flow<AppLanguage> = dataStore.data.map { prefs -> AppLanguage.fromTag(prefs[KEY_LANGUAGE]) }

    override suspend fun setAppLanguage(language: AppLanguage) {
        dataStore.edit { prefs ->
            val tag = language.tag
            if (tag == null) prefs.remove(KEY_LANGUAGE) else prefs[KEY_LANGUAGE] = tag
        }
    }

    private companion object {
        val KEY_SCANNER_MODE = stringPreferencesKey("scanner_mode")
        val KEY_BEEP = booleanPreferencesKey("scanner_beep")
        val KEY_VIBRATE = booleanPreferencesKey("scanner_vibrate")
        val KEY_LANGUAGE = stringPreferencesKey("app_language")
    }
}
