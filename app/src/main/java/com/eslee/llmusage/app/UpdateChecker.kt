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
 * Asks GitHub, prereleases included, whether a build newer than the installed one
 * has been published. Nothing about the device is sent: it is the same public
 * listing the release page shows.
 *
 * The Releases API answers 60 unauthenticated requests an hour per IP address, and
 * a shared office network can use that up. The release feed is a plain page outside
 * that limit, so it is asked whenever the API does not answer.
 */
class UpdateChecker(
    private val endpoint: String = RELEASES,
    private val feed: String = FEED,
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

    /**
     * What a check found. A failed check is kept apart from "no newer build":
     * counting a check made offline as done used to silence the next ones.
     */
    sealed interface Outcome {
        data class Newer(val available: Available) : Outcome
        data object Current : Outcome
        /** Neither source answered; [reason] says how each failed, for the diagnostics. */
        data class Failed(val reason: String) : Outcome
    }

    suspend fun check(installed: String): Outcome = withContext(Dispatchers.IO) {
        val api = listing(endpoint, "application/vnd.github+json", ::parse)
        val releases = api.releases ?: run {
            val fromFeed = listing(feed, "application/atom+xml", ::parseFeed)
            fromFeed.releases ?: return@withContext Outcome.Failed("api ${api.problem}, feed ${fromFeed.problem}")
        }
        available(releases, installed)?.let { Outcome.Newer(it) } ?: Outcome.Current
    }

    /** One source's answer: the releases it lists, or why there are none. An empty listing says nothing. */
    private class Listing(val releases: List<Release>?, val problem: String)

    // Any failure is an answer of "unknown": the check runs from the screen, where a throw would end the app.
    private fun listing(url: String, accept: String, read: (String) -> List<Release>): Listing = try {
        client.newCall(Request.Builder().url(url).header("Accept", accept).build()).execute().use { response ->
            if (!response.isSuccessful) Listing(null, "http ${response.code}")
            else read(response.body?.string().orEmpty()).let { if (it.isEmpty()) Listing(null, "unreadable") else Listing(it, "") }
        }
    } catch (error: Exception) {
        Listing(null, error.javaClass.simpleName)
    }

    companion object {
        const val RELEASES = "https://api.github.com/repos/esleeeeee/eslee-llm-usage/releases?per_page=5"
        const val FEED = "https://github.com/esleeeeee/eslee-llm-usage/releases.atom"
        private val json = Json { ignoreUnknownKeys = true }
        private val feedEntry = Regex("""href="([^"]*/releases/tag/([^"/]+))"""")

        fun parse(body: String): List<Release> = runCatching { json.decodeFromString<List<Release>>(body) }.getOrDefault(emptyList())

        /** The feed names each release by its page; it carries no assets, so the page is what gets opened. */
        fun parseFeed(body: String): List<Release> =
            feedEntry.findAll(body).map { Release(tag = it.groupValues[2], page = it.groupValues[1]) }.distinctBy { it.tag }.toList()

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
