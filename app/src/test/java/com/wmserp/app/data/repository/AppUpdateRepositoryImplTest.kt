package com.wmserp.app.data.repository

import com.wmserp.app.data.remote.ApiCaller
import com.wmserp.app.data.remote.SessionEventBus
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppException
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.common.ErrorCode
import com.wmserp.app.domain.model.AppRelease
import com.wmserp.app.domain.model.DownloadEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class AppUpdateRepositoryImplTest {

    private val server = MockWebServer()
    private lateinit var dir: File
    private lateinit var repository: AppUpdateRepositoryImpl

    private val latestJson = """
        {"tag_name":"v1.1.57","name":"WMS ERP 1.1.57","body":"fix: batches\n","draft":false,"prerelease":false,"published_at":"2026-09-28T20:30:00Z",
         "assets":[
           {"name":"wmserp-1.1.57-debug.apk","browser_download_url":"https://example.invalid/debug.apk","size":33000000,"content_type":"application/vnd.android.package-archive"},
           {"name":"wmserp-1.1.57.apk","browser_download_url":"https://example.invalid/release.apk","size":12829085,"content_type":"application/vnd.android.package-archive"},
           {"name":"mapping.txt","browser_download_url":"https://example.invalid/mapping.txt","size":10}
         ]}
    """.trimIndent()

    @Before
    fun setUp() {
        server.start()
        dir = createTempDirectory("wmserp-updates").toFile()
        repository = AppUpdateRepositoryImpl(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true; isLenient = true },
            apiCaller = ApiCaller(SessionEventBus()),
            repository = "erenaydin-t/WmsErp",
            downloadDir = dir,
            apiBaseUrl = server.url("/").toString(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    @Test
    fun `latest release picks the release apk and strips the tag prefix`() = runTest {
        server.enqueue(MockResponse().setBody(latestJson))

        val release = (repository.getLatestRelease() as AppResult.Success).data!!

        assertEquals("1.1.57", release.version)
        assertEquals("v1.1.57", release.tag)
        assertEquals("wmserp-1.1.57.apk", release.apkName)
        assertEquals("https://example.invalid/release.apk", release.apkUrl)
        assertEquals(12829085L, release.apkSizeBytes)
        assertEquals("fix: batches", release.notes)
        val request = server.takeRequest()
        assertEquals("/repos/erenaydin-t/WmsErp/releases/latest", request.path)
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
    }

    @Test
    fun `no release, a draft or a release without apk yields null`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        server.enqueue(MockResponse().setBody("""{"tag_name":"v1.1.58","assets":[{"name":"notes.txt","browser_download_url":"https://example.invalid/n","size":1}]}"""))
        server.enqueue(MockResponse().setBody("""{"tag_name":"v1.1.59","draft":true,"assets":[{"name":"a.apk","browser_download_url":"https://example.invalid/a","size":1}]}"""))

        repeat(3) { assertNull((repository.getLatestRelease() as AppResult.Success).data) }
    }

    @Test
    fun `github errors are reported as server failures`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"API rate limit exceeded"}"""))

        val unavailable = (repository.getLatestRelease() as AppResult.Failure).error as AppError.Server
        val limited = (repository.getLatestRelease() as AppResult.Failure).error as AppError.Server

        assertEquals(ErrorCode.SERVER_UNAVAILABLE, unavailable.code)
        assertEquals(503, unavailable.httpCode)
        assertEquals(ErrorCode.SERVER_UNAVAILABLE, limited.code)
    }

    @Test
    fun `download streams the apk with progress and reuses a complete file`() = runTest {
        val payload = ByteArray(700_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))
        val release = release(payload.size.toLong())

        val events = repository.download(release).toList()

        val completed = events.last() as DownloadEvent.Completed
        val file = File(completed.apkPath)
        assertEquals(File(dir, "wmserp-1.1.57.apk").absolutePath, file.absolutePath)
        assertArrayEquals(payload, file.readBytes())
        val progress = events.filterIsInstance<DownloadEvent.Progress>()
        assertTrue(progress.size >= 3)
        assertEquals(progress.map { it.downloadedBytes }.sorted(), progress.map { it.downloadedBytes })
        assertEquals(1f, progress.last().fraction, 0f)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
        assertEquals("/apk", server.takeRequest().path)

        // The finished file is reused: no second request.
        val again = repository.download(release).toList()
        assertEquals(completed, again.last())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a failed download throws and leaves no partial file`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            repository.download(release(10)).toList()
            fail("expected an AppException")
        } catch (e: AppException) {
            assertEquals(ErrorCode.SERVER_UNAVAILABLE, e.error.code)
        }
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    private fun release(size: Long) = AppRelease(
        version = "1.1.57", tag = "v1.1.57", title = "WMS ERP 1.1.57", notes = "",
        apkUrl = server.url("/apk").toString(), apkName = "wmserp-1.1.57.apk", apkSizeBytes = size,
    )
}
