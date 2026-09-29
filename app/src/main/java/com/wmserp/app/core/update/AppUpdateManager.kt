package com.wmserp.app.core.update

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppException
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.AppRelease
import com.wmserp.app.domain.model.DownloadEvent
import com.wmserp.app.domain.model.InstalledVersion
import com.wmserp.app.domain.model.UpdateState
import com.wmserp.app.domain.model.VersionNumber
import com.wmserp.app.domain.repository.AppUpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Single source of truth for the in-app updater. Lives for the whole process so that a download
 * started from the dashboard banner keeps running while the user moves to another screen.
 *
 * Flow: [checkForUpdate] -> [UpdateState.Available] -> [download] -> [UpdateState.ReadyToInstall];
 * the screens hand the downloaded file to the Android package installer.
 */
class AppUpdateManager(
    private val repository: AppUpdateRepository,
    private val installedVersion: InstalledVersion,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String get() = installedVersion.versionName

    /** Wall-clock time of the last network check; null until the first one. */
    private var lastCheckAt: Long? = null
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    /**
     * Looks up the latest GitHub release. Automatic checks (the default) run at most once every
     * [CHECK_INTERVAL_MS] and stay silent on failure; a [force]d check from the settings screen always
     * hits the network and reports errors.
     */
    fun checkForUpdate(force: Boolean = false): Job? {
        val current = _state.value
        if (current is UpdateState.Downloading || current is UpdateState.ReadyToInstall) return null
        if (checkJob?.isActive == true) return checkJob
        val last = lastCheckAt
        if (!force && last != null && clock() - last < CHECK_INTERVAL_MS) return null
        lastCheckAt = clock()
        val previous = current
        _state.value = UpdateState.Checking
        return scope.launch {
            when (val result = repository.getLatestRelease()) {
                is AppResult.Success -> _state.value = evaluate(result.data, previous)
                is AppResult.Failure -> _state.value = if (force) UpdateState.Failed(result.error) else previous.orIdle()
            }
        }.also { checkJob = it }
    }

    private fun evaluate(release: AppRelease?, previous: UpdateState): UpdateState {
        if (release == null) return UpdateState.NoRelease
        if (release.versionNumber <= VersionNumber.parse(installedVersion.versionName)) {
            return UpdateState.UpToDate(installedVersion.versionName)
        }
        val dismissed = previous is UpdateState.Available && previous.dismissed && previous.release.version == release.version
        return UpdateState.Available(release, dismissed)
    }

    /** Downloads the APK of the release currently offered (from [UpdateState.Available] or after a failure). */
    fun download(): Job? {
        val release = _state.value.offeredRelease ?: return null
        if (downloadJob?.isActive == true) return downloadJob
        _state.value = UpdateState.Downloading(release, 0f, 0L)
        return scope.launch {
            try {
                repository.download(release).collect { event ->
                    _state.value = when (event) {
                        is DownloadEvent.Progress -> UpdateState.Downloading(release, event.fraction, event.downloadedBytes)
                        is DownloadEvent.Completed -> UpdateState.ReadyToInstall(release, event.apkPath)
                    }
                }
            } catch (e: CancellationException) {
                _state.value = UpdateState.Available(release)
                throw e
            } catch (e: AppException) {
                _state.value = UpdateState.Failed(e.error, release)
            } catch (e: IOException) {
                _state.value = UpdateState.Failed(AppError.Network(e.message ?: "Download failed"), release)
            }
        }.also { downloadJob = it }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** Hides the dashboard banner for the offered version; the settings screen still shows it. */
    fun dismiss() = _state.update { if (it is UpdateState.Available) it.copy(dismissed = true) else it }

    private fun UpdateState.orIdle(): UpdateState = if (this is UpdateState.Checking) UpdateState.Idle else this

    companion object {
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
