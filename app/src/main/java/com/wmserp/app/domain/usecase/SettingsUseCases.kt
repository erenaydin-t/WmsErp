package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.model.AppLanguage
import com.wmserp.app.domain.model.ScannerMode
import com.wmserp.app.domain.model.ScannerSettings
import com.wmserp.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveScannerSettingsUseCase @Inject constructor(private val settingsRepository: SettingsRepository) {
    operator fun invoke(): Flow<ScannerSettings> = settingsRepository.scannerSettings
}

class UpdateScannerSettingsUseCase @Inject constructor(private val settingsRepository: SettingsRepository) {
    suspend fun setMode(mode: ScannerMode) = settingsRepository.setScannerMode(mode)
    suspend fun setBeep(enabled: Boolean) = settingsRepository.setBeepOnScan(enabled)
    suspend fun setVibrate(enabled: Boolean) = settingsRepository.setVibrateOnScan(enabled)
}

class ObserveAppLanguageUseCase @Inject constructor(private val settingsRepository: SettingsRepository) {
    operator fun invoke(): Flow<AppLanguage> = settingsRepository.appLanguage
}

class SetAppLanguageUseCase @Inject constructor(private val settingsRepository: SettingsRepository) {
    suspend operator fun invoke(language: AppLanguage) = settingsRepository.setAppLanguage(language)
}
