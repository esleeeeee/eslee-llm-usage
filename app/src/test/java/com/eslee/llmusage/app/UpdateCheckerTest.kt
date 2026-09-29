package com.eslee.llmusage.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    private val listing = """
        [
          {"tag_name":"v0.2.5","html_url":"https://github.com/esleeeeee/eslee-llm-usage/releases/tag/v0.2.5","draft":false,"prerelease":true,
           "assets":[{"name":"eslee-llm-usage-v0.2.5.apk","browser_download_url":"https://github.com/esleeeeee/eslee-llm-usage/releases/download/v0.2.5/eslee-llm-usage-v0.2.5.apk","size":1}],
           "body":"notes","author":{"login":"esleeeeee"}},
          {"tag_name":"v0.2.4","html_url":"https://github.com/esleeeeee/eslee-llm-usage/releases/tag/v0.2.4","draft":false,"prerelease":true,"assets":[]}
        ]
    """.trimIndent()

    @Test fun aNewerPrereleaseIsOfferedWithItsApk() {
        val found = UpdateChecker.available(UpdateChecker.parse(listing), installed = "0.2.4")
        assertEquals("0.2.5", found?.version)
        assertTrue(found?.apk.orEmpty().endsWith("eslee-llm-usage-v0.2.5.apk"))
        assertEquals("https://github.com/esleeeeee/eslee-llm-usage/releases/tag/v0.2.5", found?.page)
    }

    @Test fun theInstalledOrANewerBuildGetsNoPrompt() {
        val releases = UpdateChecker.parse(listing)
        assertNull(UpdateChecker.available(releases, installed = "0.2.5"))
        assertNull(UpdateChecker.available(releases, installed = "0.3.0"))
    }

    @Test fun draftsAreIgnoredAndTheNewestTagWinsRegardlessOfOrder() {
        val releases = UpdateChecker.parse(
            """[{"tag_name":"v0.2.4","html_url":"p4"},{"tag_name":"v0.2.10","html_url":"p10"},{"tag_name":"v9.9.9","html_url":"draft","draft":true}]""",
        )
        val found = UpdateChecker.available(releases, installed = "0.2.9")
        assertEquals("0.2.10", found?.version)
        assertNull(found?.apk)
    }

    @Test fun versionsCompareNumericallyPartByPart() {
        assertTrue(UpdateChecker.compare("v0.2.10", "0.2.9") > 0)
        assertTrue(UpdateChecker.compare("0.2.4", "v0.2.4") == 0)
        assertTrue(UpdateChecker.compare("1.0", "0.9.9") > 0)
        assertTrue(UpdateChecker.compare("0.2.4-debug", "0.2.4") == 0)
    }

    @Test fun anUnexpectedBodyIsNotAnUpdate() {
        assertNull(UpdateChecker.available(UpdateChecker.parse("<html>rate limited</html>"), installed = "0.2.4"))
        assertNull(UpdateChecker.available(UpdateChecker.parse("[]"), installed = "0.2.4"))
    }

    /** Reported: 0.2.6 never offered 0.2.7. A check that failed must not count as one that found nothing. */
    @Test fun aFailedCheckIsNotMistakenForTheLatestVersion() = kotlinx.coroutines.runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(403).setBody("""{"message":"API rate limit exceeded"}"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(listing))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(listing))
            val checker = UpdateChecker(server.url("/releases").toString(), server.url("/feed").toString())
            val failed = checker.check("0.2.4")
            assertTrue("$failed", failed is UpdateChecker.Outcome.Failed)
            assertEquals("api http 403, feed http 503", (failed as UpdateChecker.Outcome.Failed).reason)
            val newer = checker.check("0.2.4")
            assertTrue(newer is UpdateChecker.Outcome.Newer)
            assertEquals("0.2.5", (newer as UpdateChecker.Outcome.Newer).available.version)
            assertEquals(UpdateChecker.Outcome.Current, checker.check("0.2.5"))
        }
    }

    /**
     * Reported: 0.2.8 never offered 0.2.9. The Releases API allows 60 requests an hour per
     * IP address, which a shared network uses up; the release feed then answers instead.
     */
    @Test fun theReleaseFeedAnswersWhenTheApiRefuses() = kotlinx.coroutines.runBlocking {
        val feed = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <link type="text/html" rel="alternate" href="https://github.com/esleeeeee/eslee-llm-usage/releases"/>
              <entry><title>eslee LLM Usage v0.2.9</title>
                <link rel="alternate" type="text/html" href="https://github.com/esleeeeee/eslee-llm-usage/releases/tag/v0.2.9"/></entry>
              <entry><title>eslee LLM Usage v0.2.10</title>
                <link rel="alternate" type="text/html" href="https://github.com/esleeeeee/eslee-llm-usage/releases/tag/v0.2.10"/></entry>
            </feed>
        """.trimIndent()
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(403).setBody("""{"message":"API rate limit exceeded"}"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(feed))
            val newer = UpdateChecker(server.url("/releases").toString(), server.url("/feed").toString()).check("0.2.9")
            assertTrue("$newer", newer is UpdateChecker.Outcome.Newer)
            val found = (newer as UpdateChecker.Outcome.Newer).available
            assertEquals("0.2.10", found.version)
            assertEquals("https://github.com/esleeeeee/eslee-llm-usage/releases/tag/v0.2.10", found.page)
            assertNull("the feed has no assets; the release page is opened instead", found.apk)
        }
    }
}
