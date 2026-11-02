package com.wmserp.app.data.repository

import com.wmserp.app.data.remote.ApiCaller
import com.wmserp.app.data.remote.github.GitHubReleaseDto
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppException
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.common.ErrorCode
import com.wmserp.app.domain.model.AppRelease
import com.wmserp.app.domain.model.DownloadEvent
import com.wmserp.app.domain.repository.AppUpdateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Talks to the GitHub Releases API of the repository that publishes the app (unauthenticated: the
 * repository is public and the rate limit of 60 requests/hour is far above what the app needs).
 */
class AppUpdateRepositoryImpl(
    private val client: OkHttpClient,
    private val json: Json,
    private val apiCaller: ApiCaller,
    /** `owner/repo`, e.g. `erenaydin-t/WmsErp`. */
    private val repository: String,
    private val downloadDir: File,
    private val apiBaseUrl: String = "https://api.github.com",
) : AppUpdateRepository {

    override suspend fun getLatestRelease(): AppResult<AppRelease?> = apiCaller.call {
        val request = Request.Builder()
            .url("${apiBaseUrl.trimEnd('/')}/repos/$repository/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        runInterruptible(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> null
                    !response.isSuccessful -> throw AppException(serverError(response.code, "GitHub releases"))
                    else -> json.decodeFromString(GitHubReleaseDto.serializer(), response.body?.string().orEmpty()).toRelease()
                }
            }
        }
    }

    override fun download(release: AppRelease): Flow<DownloadEvent> = flow {
        downloadDir.mkdirs()
        val target = File(downloadDir, release.apkName.ifBlank { "wmserp-${release.version}.apk" })
        if (target.isFile && release.apkSizeBytes > 0 && target.length() == release.apkSizeBytes) {
            emit(DownloadEvent.Progress(target.length(), target.length()))
            emit(DownloadEvent.Completed(target.absolutePath))
            return@flow
        }
        // Other releases' leftovers are useless now; keep storage tidy before writing a new file.
        downloadDir.listFiles()?.filter { it.name != target.name }?.forEach { it.delete() }
        val partial = File(downloadDir, target.name + ".part")
        val request = Request.Builder().url(release.apkUrl).header("Accept", "application/octet-stream").build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw AppException(serverError(response.code, "APK download"))
                val body = response.body ?: throw AppException(AppError.Server("Empty APK download", code = ErrorCode.INVALID_RESPONSE))
                val total = body.contentLength().takeIf { it > 0 } ?: release.apkSizeBytes
                var downloaded = 0L
                var lastReported = -1L
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (downloaded - lastReported >= PROGRESS_STEP_BYTES || downloaded == total) {
                                lastReported = downloaded
                                emit(DownloadEvent.Progress(downloaded, total))
                            }
                        }
                    }
                }
                if (total > 0 && downloaded != total) throw IOException("Download interrupted after $downloaded of $total bytes")
            }
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) throw IOException("Could not move the downloaded APK into place")
            emit(DownloadEvent.Progress(target.length(), target.length()))
            emit(DownloadEvent.Completed(target.absolutePath))
        } finally {
            partial.delete()
        }
    }.flowOn(Dispatchers.IO)

    private fun serverError(code: Int, what: String): AppError = when (code) {
        403, 429 -> AppError.Server("GitHub rate limit reached, try again later", code, ErrorCode.SERVER_UNAVAILABLE)
        in 500..599 -> AppError.Server("GitHub is unavailable (HTTP $code)", code, ErrorCode.SERVER_UNAVAILABLE)
        else -> AppError.Server("$what failed (HTTP $code)", code, ErrorCode.SERVER_ERROR)
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 256 * 1024L

        /** Picks the APK asset of a release; the release build is preferred when several are attached. */
        fun GitHubReleaseDto.toRelease(): AppRelease? {
            if (draft) return null
            val apk = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
                .sortedBy { if (it.name.contains("debug", ignoreCase = true)) 1 else 0 }
                .firstOrNull() ?: return null
            return AppRelease(
                version = tagName.trim().removePrefix("v").removePrefix("V"),
                tag = tagName,
                title = name?.takeIf { it.isNotBlank() } ?: tagName,
                notes = body.orEmpty().trim(),
                apkUrl = apk.browserDownloadUrl,
                apkName = apk.name,
                apkSizeBytes = apk.size,
                publishedAt = publishedAt,
            )
        }
    }
}
