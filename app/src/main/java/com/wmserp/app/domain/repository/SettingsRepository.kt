package com.wmserp.app.domain.repository

import com.wmserp.app.domain.model.ScannerMode
import com.wmserp.app.domain.model.ScannerSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val scannerSettings: Flow<ScannerSettings>
    suspend fun setScannerMode(mode: ScannerMode)
    suspend fun setBeepOnScan(enabled: Boolean)
    suspend fun setVibrateOnScan(enabled: Boolean)
}
