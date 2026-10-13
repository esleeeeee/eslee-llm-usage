package com.eslee.llmusage.app

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reported: the update button handed the APK link to Chrome, which never asked to
 * download it. The app now fetches the release itself into the public Downloads
 * folder. This fetches a small file of a published release the same way, through
 * GitHub's redirect to its file host, and checks it lands whole.
 */
@RunWith(AndroidJUnit4::class)
class UpdateDownloadTest {
    @Test fun aReleaseFileIsDownloadedIntoDownloads(): Unit = runBlocking {
        if (!UpdateDownload.supported) return@runBlocking
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(DownloadManager::class.java)
        val name = "eslee-llm-usage-download-test-${System.currentTimeMillis()}.json"
        val id = UpdateDownload.start(context,
            "https://github.com/esleeeeee/eslee-llm-usage/releases/download/v0.2.13/eslee-llm-usage-v0.2.13-verification.json",
            name, "eslee LLM Usage download test", mimeType = "application/json")
        try {
            assertEquals(UpdateDownload.State.DONE, withTimeout(90_000) { UpdateDownload.await(context, id) })
            manager.query(DownloadManager.Query().setFilterById(id)).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(622L, row.getLong(row.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
                val local = row.getString(row.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)).orEmpty()
                assertTrue("saved outside Downloads: $local", local.contains(Environment.DIRECTORY_DOWNLOADS) && local.endsWith(name))
            }
            // Reported: the finished notification did not open the installer, so the app opens it.
            // A fresh install has not been allowed to install updates yet; Android's switch for that opens first.
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val allowSetting = instrumentation.addMonitor(
                android.content.IntentFilter(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply { addDataScheme("package") }, null, true)
            val installer = instrumentation.addMonitor(
                android.content.IntentFilter(android.content.Intent.ACTION_VIEW).apply { addDataType(UpdateDownload.APK_TYPE) }, null, true)
            try {
                val opened = UpdateDownload.install(context, id)
                assertEquals(context.packageManager.canRequestPackageInstalls(), opened)
                val monitor = if (opened) installer else allowSetting
                assertTrue("neither the installer nor the allow-install setting was opened", instrumentation.checkMonitorHit(monitor, 1))
            } finally {
                instrumentation.removeMonitor(allowSetting)
                instrumentation.removeMonitor(installer)
            }
        } finally {
            // Removing the download deletes the file it wrote.
            manager.remove(id)
        }
    }
}
