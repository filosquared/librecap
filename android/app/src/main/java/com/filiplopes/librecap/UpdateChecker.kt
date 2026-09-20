package com.filiplopes.librecap

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class ReleaseAsset(
    val name: String,
    @SerializedName("browser_download_url") val browserDownloadUrl: String
)

data class AppRelease(
    @SerializedName("tag_name") val tagName: String,
    val name: String?,
    @SerializedName("html_url") val htmlUrl: String,
    val assets: List<ReleaseAsset> = emptyList(),
    val prerelease: Boolean = false,
    val draft: Boolean = false
) {
    val displayName: String
        get() = name?.trim()?.takeIf { it.isNotEmpty() } ?: tagName

    val installUrl: String
        get() = assets.firstOrNull { it.name.equals("LibreCap-Android-debug.apk", ignoreCase = true) }?.browserDownloadUrl
            ?: assets.firstOrNull {
                it.name.endsWith(".apk", ignoreCase = true) && !it.name.contains("unsigned", ignoreCase = true)
            }?.browserDownloadUrl
            ?: htmlUrl
}

class GitHubReleaseChecker(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson()
) {
    suspend fun fetchLatest(): AppRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "LibreCap Android Update Checker")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val release = response.body?.string()?.let { gson.fromJson(it, AppRelease::class.java) } ?: return@withContext null
            release.takeIf {
                !it.draft && !it.prerelease && it.htmlUrl.startsWith("https://github.com/")
            }
        }
    }

    companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/filosquared/librecap/releases/latest"

        fun isNewer(latest: String, current: String): Boolean {
            val latestParts = versionParts(latest)
            val currentParts = versionParts(current)
            if (latestParts.isEmpty() || currentParts.isEmpty()) return false

            for (index in 0 until maxOf(latestParts.size, currentParts.size)) {
                val latestPart = latestParts.getOrElse(index) { 0 }
                val currentPart = currentParts.getOrElse(index) { 0 }
                if (latestPart != currentPart) return latestPart > currentPart
            }
            return false
        }

        private fun versionParts(value: String): List<Int> =
            Regex("\\d+").findAll(value).mapNotNull { it.value.toIntOrNull() }.toList()
    }
}
