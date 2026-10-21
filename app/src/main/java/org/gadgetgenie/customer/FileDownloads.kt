package org.gadgetgenie.customer

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * Saves files the portal hands us (receipts, agreement PDFs) to the device's Downloads
 * location. We only hold INTERNET / ACCESS_NETWORK_STATE / CAMERA permissions — no
 * WRITE_EXTERNAL_STORAGE — so every path below is one that Android allows without it:
 * the MediaStore Downloads collection on API 29+, and DownloadManager.addCompletedDownload()
 * against our own app-specific external files dir on API 26-28.
 */
object FileDownloads {

    /** Handles a normal https:// download the WebView's DownloadListener caught. */
    fun startManagedDownload(
        context: Context,
        url: String,
        userAgent: String,
        mimeType: String?,
        suggestedName: String
    ) {
        val cookie = CookieManager.getInstance().getCookie(url)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            if (!cookie.isNullOrEmpty()) addRequestHeader("Cookie", cookie)
            addRequestHeader("User-Agent", userAgent)
            setMimeType(mimeType)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setTitle(suggestedName)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            // App-specific dir: never needs WRITE_EXTERNAL_STORAGE, on any API level.
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, suggestedName)
        }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
        Toast.makeText(context, "Downloading $suggestedName…", Toast.LENGTH_SHORT).show()
    }

    /** Handles a blob:/data: URL the page handed us as raw bytes via the JS bridge. */
    fun saveBytes(context: Context, bytes: ByteArray, filename: String, mimeType: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStoreDownloads(context, bytes, filename, mimeType)
        } else {
            saveToAppFilesAndRegister(context, bytes, filename, mimeType)
        }
    }

    private fun saveToMediaStoreDownloads(
        context: Context,
        bytes: ByteArray,
        filename: String,
        mimeType: String
    ) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: run {
            Toast.makeText(context, "Couldn't save $filename", Toast.LENGTH_SHORT).show()
            return
        }
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        Toast.makeText(context, "Saved $filename to Downloads", Toast.LENGTH_SHORT).show()
    }

    private fun saveToAppFilesAndRegister(
        context: Context,
        bytes: ByteArray,
        filename: String,
        mimeType: String
    ) {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        dir.mkdirs()
        val file = File(dir, filename)
        FileOutputStream(file).use { it.write(bytes) }

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.addCompletedDownload(
            filename,
            "GenieLock Customer",
            true,
            mimeType,
            file.absolutePath,
            bytes.size.toLong(),
            true
        )
        Toast.makeText(context, "Saved $filename to Downloads", Toast.LENGTH_SHORT).show()
    }
}
