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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Non-sensitive user preferences stored in plain Preferences DataStore. */
class AndroidSettingsRepository(private val dataStore: DataStore<Preferences>) : SettingsRepository {

    override val scannerSettings: Flow<ScannerSettings> = dataStore.data.map { prefs ->
        ScannerSettings(
            mode = prefs[KEY_SCANNER_MODE]?.let { runCatching { ScannerMode.valueOf(it) }.getOrNull() } ?: ScannerMode.AUTO,
            beepOnScan = prefs[KEY_BEEP] ?: true,
            vibrateOnScan = prefs[KEY_VIBRATE] ?: true,
            askQuantityOnScan = prefs[KEY_ASK_QUANTITY] ?: true,
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

    override suspend fun setAskQuantityOnScan(enabled: Boolean) {
        dataStore.edit { it[KEY_ASK_QUANTITY] = enabled }
    }

    override val appLanguage: Flow<AppLanguage> = dataStore.data.map { prefs -> AppLanguage.fromTag(prefs[KEY_LANGUAGE]) }

    override suspend fun setAppLanguage(language: AppLanguage) {
        dataStore.edit { prefs ->
            val tag = language.tag
            if (tag == null) prefs.remove(KEY_LANGUAGE) else prefs[KEY_LANGUAGE] = tag
        }
    }

    override val documentFieldDefaults: Flow<Map<String, String>> =
        dataStore.data.map { prefs -> prefs[KEY_DOCUMENT_DEFAULTS]?.let(::decodeDefaults).orEmpty() }

    override suspend fun setDocumentFieldDefaults(values: Map<String, String>) {
        dataStore.edit { prefs ->
            val merged = prefs[KEY_DOCUMENT_DEFAULTS]?.let(::decodeDefaults).orEmpty() + values
            prefs[KEY_DOCUMENT_DEFAULTS] = Json.encodeToString(MAP_SERIALIZER, merged)
        }
    }

    private fun decodeDefaults(raw: String): Map<String, String> =
        runCatching { Json.decodeFromString(MAP_SERIALIZER, raw) }.getOrDefault(emptyMap())

    private companion object {
        val MAP_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
        val KEY_DOCUMENT_DEFAULTS = stringPreferencesKey("document_field_defaults")
        val KEY_SCANNER_MODE = stringPreferencesKey("scanner_mode")
        val KEY_BEEP = booleanPreferencesKey("scanner_beep")
        val KEY_VIBRATE = booleanPreferencesKey("scanner_vibrate")
        val KEY_ASK_QUANTITY = booleanPreferencesKey("scanner_ask_quantity")
        val KEY_LANGUAGE = stringPreferencesKey("app_language")
    }
}
