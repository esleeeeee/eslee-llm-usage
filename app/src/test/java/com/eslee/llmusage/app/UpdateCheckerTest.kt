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
}
