package com.wmserp.app.core.update

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppException
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.AppRelease
import com.wmserp.app.domain.model.DownloadEvent
import com.wmserp.app.domain.model.InstalledVersion
import com.wmserp.app.domain.model.UpdateState
import com.wmserp.app.domain.repository.AppUpdateRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    private val repository: AppUpdateRepository = mockk()
    private val installed = object : InstalledVersion {
        override val versionName = "1.1.40"
        override val versionCode = 40L
    }
    private var now = 1_000_000L
    private val release = AppRelease(
        version = "1.1.57", tag = "v1.1.57", title = "WMS ERP 1.1.57", notes = "Fixes",
        apkUrl = "https://github.com/erenaydin-t/WmsErp/releases/download/v1.1.57/wmserp-1.1.57.apk",
        apkName = "wmserp-1.1.57.apk", apkSizeBytes = 1_000L,
    )

    private fun TestScope.manager() = AppUpdateManager(repository, installed, this, clock = { now })

    @Test
    fun `offers a newer release and downloads it until it is ready to install`() = runTest {
        coEvery { repository.getLatestRelease() } returns AppResult.Success(release)
        every { repository.download(release) } returns flowOf(
            DownloadEvent.Progress(250, 1_000), DownloadEvent.Progress(1_000, 1_000), DownloadEvent.Completed("/data/updates/wmserp-1.1.57.apk"),
        )
        val manager = manager()

        manager.checkForUpdate()
        assertEquals(UpdateState.Checking, manager.state.value)
        advanceUntilIdle()
        assertEquals(UpdateState.Available(release), manager.state.value)

        manager.download()
        advanceUntilIdle()
        assertEquals(UpdateState.ReadyToInstall(release, "/data/updates/wmserp-1.1.57.apk"), manager.state.value)
        assertNull(manager.checkForUpdate(force = true))
        assertEquals("1.1.40", manager.currentVersion)
    }

    @Test
    fun `the same or an older release means up to date, no release at all is reported as such`() = runTest {
        val manager = manager()
        for (candidate in listOf(release.copy(version = "1.1.40"), release.copy(version = "1.0.99"))) {
            coEvery { repository.getLatestRelease() } returns AppResult.Success(candidate)
            manager.checkForUpdate(force = true)
            advanceUntilIdle()
            assertEquals(UpdateState.UpToDate("1.1.40"), manager.state.value)
        }
        coEvery { repository.getLatestRelease() } returns AppResult.Success(null)
        manager.checkForUpdate(force = true)
        advanceUntilIdle()
        assertEquals(UpdateState.NoRelease, manager.state.value)
    }

    @Test
    fun `automatic checks are throttled and fail silently while forced checks report`() = runTest {
        coEvery { repository.getLatestRelease() } returns AppResult.Failure(AppError.Network("offline"))
        val manager = manager()

        manager.checkForUpdate()
        advanceUntilIdle()
        assertEquals(UpdateState.Idle, manager.state.value)
        assertNull(manager.checkForUpdate())
        coVerify(exactly = 1) { repository.getLatestRelease() }

        now += AppUpdateManager.CHECK_INTERVAL_MS + 1
        manager.checkForUpdate()
        advanceUntilIdle()
        coVerify(exactly = 2) { repository.getLatestRelease() }

        manager.checkForUpdate(force = true)
        advanceUntilIdle()
        val failed = manager.state.value as UpdateState.Failed
        assertTrue(failed.error is AppError.Network)
        assertNull(failed.release)
    }

    @Test
    fun `dismiss hides the banner and survives a re-check of the same version only`() = runTest {
        coEvery { repository.getLatestRelease() } returns AppResult.Success(release)
        val manager = manager()
        manager.checkForUpdate(force = true)
        advanceUntilIdle()

        manager.dismiss()
        assertEquals(UpdateState.Available(release, dismissed = true), manager.state.value)

        manager.checkForUpdate(force = true)
        advanceUntilIdle()
        assertEquals(UpdateState.Available(release, dismissed = true), manager.state.value)

        val newer = release.copy(version = "1.1.60", tag = "v1.1.60")
        coEvery { repository.getLatestRelease() } returns AppResult.Success(newer)
        manager.checkForUpdate(force = true)
        advanceUntilIdle()
        assertEquals(UpdateState.Available(newer), manager.state.value)
    }

    @Test
    fun `download failures keep the release for a retry and cancelling returns to the offer`() = runTest {
        coEvery { repository.getLatestRelease() } returns AppResult.Success(release)
        every { repository.download(release) } returns flow {
            emit(DownloadEvent.Progress(10, 1_000))
            throw AppException(AppError.Server("GitHub is unavailable (HTTP 502)", 502))
        }
        val manager = manager()
        manager.checkForUpdate(force = true)
        advanceUntilIdle()

        manager.download()
        advanceUntilIdle()
        val failed = manager.state.value as UpdateState.Failed
        assertEquals(release, failed.release)
        assertTrue(failed.error is AppError.Server)

        every { repository.download(release) } returns flow {
            emit(DownloadEvent.Progress(1, 1_000))
            awaitCancellation()
        }
        manager.download()
        runCurrent()
        assertEquals(UpdateState.Downloading(release, 0.001f, 1), manager.state.value)
        manager.cancelDownload()
        advanceUntilIdle()
        assertEquals(UpdateState.Available(release), manager.state.value)

        manager.dismiss()
        every { repository.download(release) } returns flowOf(DownloadEvent.Completed("/data/updates/wmserp-1.1.57.apk"))
        manager.download()
        advanceUntilIdle()
        assertTrue(manager.state.value is UpdateState.ReadyToInstall)
    }
}
