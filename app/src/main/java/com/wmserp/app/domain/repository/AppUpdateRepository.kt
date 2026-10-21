package com.wmserp.app.domain.repository

import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.AppRelease
import com.wmserp.app.domain.model.DownloadEvent
import kotlinx.coroutines.flow.Flow

interface AppUpdateRepository {
    /** The newest published release that carries an APK, or null when nothing has been published yet. */
    suspend fun getLatestRelease(): AppResult<AppRelease?>

    /**
     * Streams the APK of [release] to local storage, emitting [DownloadEvent.Progress] on the way and
     * [DownloadEvent.Completed] with the file path at the end. Failures are thrown as
     * [com.wmserp.app.domain.common.AppException] or [java.io.IOException].
     */
    fun download(release: AppRelease): Flow<DownloadEvent>
}
