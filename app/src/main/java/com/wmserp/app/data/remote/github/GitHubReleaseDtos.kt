package com.wmserp.app.data.remote.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /repos/{owner}/{repo}/releases/latest` (subset). */
@Serializable
data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
data class GitHubAssetDto(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0,
    @SerialName("content_type") val contentType: String? = null,
)
