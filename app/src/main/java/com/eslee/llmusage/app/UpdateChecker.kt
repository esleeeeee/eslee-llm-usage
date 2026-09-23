package com.eslee.llmusage.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Asks GitHub Releases, prereleases included, whether a build newer than the
 * installed one has been published. Nothing about the device is sent: it is
 * the same public listing the release page shows.
 */
class UpdateChecker(
    private val endpoint: String = RELEASES,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build(),
) {
    @Serializable data class Asset(val name: String, @SerialName("browser_download_url") val url: String)
    @Serializable data class Release(
        @SerialName("tag_name") val tag: String,
        @SerialName("html_url") val page: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )
    /** A newer build: its version, the release page, and the APK when the release carries one. */
    data class Available(val version: String, val page: String, val apk: String?)

    suspend fun check(installed: String): Available? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(endpoint).header("Accept", "application/vnd.github+json").build()).execute().use { response ->
                if (!response.isSuccessful) null else available(parse(response.body?.string().orEmpty()), installed)
            }
        }.getOrNull()
    }

    companion object {
        const val RELEASES = "https://api.github.com/repos/esleeeeee/eslee-llm-usage/releases?per_page=5"
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(body: String): List<Release> = runCatching { json.decodeFromString<List<Release>>(body) }.getOrDefault(emptyList())

        fun available(releases: List<Release>, installed: String): Available? {
            val newest = releases.filter { !it.draft }.maxWithOrNull { a, b -> compare(a.tag, b.tag) } ?: return null
            if (compare(newest.tag, installed) <= 0) return null
            return Available(newest.tag.removePrefix("v"), newest.page, newest.assets.firstOrNull { it.name.endsWith(".apk") }?.url)
        }

        /** Numeric, part by part, so v0.2.10 is newer than v0.2.9. */
        fun compare(a: String, b: String): Int {
            val x = parts(a)
            val y = parts(b)
            for (index in 0 until maxOf(x.size, y.size)) {
                val difference = (x.getOrNull(index) ?: 0) - (y.getOrNull(index) ?: 0)
                if (difference != 0) return difference
            }
            return 0
        }

        fun parts(version: String): List<Int> =
            version.trim().removePrefix("v").removePrefix("V").split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    }
}
