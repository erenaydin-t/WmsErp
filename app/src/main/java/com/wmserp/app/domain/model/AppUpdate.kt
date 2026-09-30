package com.wmserp.app.domain.model

import com.wmserp.app.domain.common.AppError

/** A published build of the app: a GitHub Release that carries an APK asset. */
data class AppRelease(
    /** Version without the `v` prefix, e.g. `1.1.57`. */
    val version: String,
    val tag: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkSizeBytes: Long,
    val publishedAt: String? = null,
) {
    val versionNumber: VersionNumber get() = VersionNumber.parse(version)
}

/**
 * Dotted numeric version such as `1.1.57`. A leading `v` is ignored, missing parts count as zero and
 * a suffix like `-dev` or `-rc1` marks a pre-release that sorts below the plain version.
 */
data class VersionNumber(val parts: List<Int>, val preRelease: Boolean = false) : Comparable<VersionNumber> {

    override fun compareTo(other: VersionNumber): Int {
        val size = maxOf(parts.size, other.parts.size)
        for (index in 0 until size) {
            val diff = (parts.getOrNull(index) ?: 0).compareTo(other.parts.getOrNull(index) ?: 0)
            if (diff != 0) return diff
        }
        return (!preRelease).compareTo(!other.preRelease)
    }

    override fun toString(): String = parts.joinToString(".") + if (preRelease) "-pre" else ""

    companion object {
        val ZERO = VersionNumber(listOf(0))

        fun parse(raw: String?): VersionNumber {
            val text = raw.orEmpty().trim().removePrefix("v").removePrefix("V")
            if (text.isEmpty()) return ZERO
            val numeric = text.takeWhile { it.isDigit() || it == '.' }
            val parts = numeric.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.isEmpty()) return ZERO
            return VersionNumber(parts, preRelease = numeric.length < text.length)
        }
    }
}

/** What is installed on this device. */
interface InstalledVersion {
    val versionName: String
    val versionCode: Long
}

/** Progress of an APK download. */
sealed interface DownloadEvent {
    data class Progress(val downloadedBytes: Long, val totalBytes: Long) : DownloadEvent {
        val fraction: Float get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    data class Completed(val apkPath: String) : DownloadEvent
}

/** State of the in-app updater, shared by the dashboard banner and the profile card. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val version: String) : UpdateState

    /** GitHub has no release with an APK yet (nothing was published from `main` so far). */
    data object NoRelease : UpdateState
    data class Available(val release: AppRelease, val dismissed: Boolean = false) : UpdateState
    data class Downloading(val release: AppRelease, val fraction: Float, val downloadedBytes: Long) : UpdateState
    data class ReadyToInstall(val release: AppRelease, val apkPath: String) : UpdateState
    data class Failed(val error: AppError, val release: AppRelease? = null) : UpdateState

    /** The release this state is about, if any (offered, downloading, downloaded or failed). */
    val offeredRelease: AppRelease?
        get() = when (this) {
            is Available -> release
            is Downloading -> release
            is ReadyToInstall -> release
            is Failed -> release
            else -> null
        }
}
