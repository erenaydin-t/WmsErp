package com.wmserp.app.domain.repository

import com.wmserp.app.domain.model.AppLanguage
import com.wmserp.app.domain.model.ScannerMode
import com.wmserp.app.domain.model.ScannerSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val scannerSettings: Flow<ScannerSettings>
    suspend fun setScannerMode(mode: ScannerMode)
    suspend fun setBeepOnScan(enabled: Boolean)
    suspend fun setVibrateOnScan(enabled: Boolean)
    suspend fun setAskQuantityOnScan(enabled: Boolean)

    val appLanguage: Flow<AppLanguage>
    suspend fun setAppLanguage(language: AppLanguage)

    /** Answers given for required document fields, keyed `Doctype.fieldname` (see RequiredField.key). */
    val documentFieldDefaults: Flow<Map<String, String>>
    suspend fun setDocumentFieldDefaults(values: Map<String, String>)
}
