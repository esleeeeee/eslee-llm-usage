package com.eslee.llmusage.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Downloads a release through the system download manager into the phone's public
 * Downloads folder, where its file app lists it and the finished notification opens
 * the installer. Handing the link to Chrome depended on Chrome asking "download this
 * file anyway?", and on the user's phone it never asked, so the update never arrived
 * (reported 2026-10-12).
 */
object UpdateDownload {
    const val APK_TYPE = "application/vnd.android.package-archive"

    /** From Android 10 the download manager writes to Downloads without a storage permission; older phones keep the browser. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    enum class State { RUNNING, DONE, FAILED }

    fun start(context: Context, url: String, fileName: String, title: String, mimeType: String = APK_TYPE): Long =
        context.getSystemService(DownloadManager::class.java).enqueue(
            DownloadManager.Request(url.toUri())
                .setTitle(title)
                .setMimeType(mimeType)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName),
        )

    /**
     * Opens the system installer for a finished download. Until the user has allowed
     * this app to install updates, it opens that setting instead and returns false;
     * the installer is then one more tap away.
     */
    fun install(context: Context, id: Long): Boolean {
        val apk = context.getSystemService(DownloadManager::class.java).getUriForDownloadedFile(id) ?: return false
        val intent = if (context.packageManager.canRequestPackageInstalls()) {
            Intent(Intent.ACTION_VIEW).setDataAndType(apk, APK_TYPE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess && intent.action == Intent.ACTION_VIEW
    }

    /** A download the user cancelled from its notification is gone from the manager, which counts as failed. */
    fun state(context: Context, id: Long): State =
        context.getSystemService(DownloadManager::class.java).query(DownloadManager.Query().setFilterById(id)).use { row ->
            if (!row.moveToFirst()) return State.FAILED
            when (row.getInt(row.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> State.DONE
                DownloadManager.STATUS_FAILED -> State.FAILED
                else -> State.RUNNING
            }
        }

    /** Waits for the download to end; if the screen goes away first, the system notification carries on. */
    suspend fun await(context: Context, id: Long): State = withContext(Dispatchers.IO) {
        var current = state(context, id)
        while (current == State.RUNNING) {
            delay(1_000)
            current = state(context, id)
        }
        current
    }
}
