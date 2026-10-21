package org.gadgetgenie.customer

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import java.lang.ref.WeakReference

/**
 * Minimal JS bridge for one purpose only: receiving the bytes of a blob:/data: PDF the
 * portal generated client-side (receipts, agreements), which WebView's own DownloadListener
 * cannot reach because those URLs only resolve inside the page's own JS context. It exposes
 * nothing else — no page control, no device info, no auth data.
 *
 * Paired with the injected script in MainActivity.BLOB_DOWNLOAD_JS, which intercepts clicks
 * on blob:/data: links, reads them with fetch()+FileReader, and calls [saveBase64].
 */
class DownloadBridge(activity: android.app.Activity) {

    private val activityRef = WeakReference(activity)
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun saveBase64(base64Data: String, filename: String, mimeType: String) {
        val activity = activityRef.get() ?: return
        val comma = base64Data.indexOf(',')
        val payload = if (comma >= 0) base64Data.substring(comma + 1) else base64Data
        val bytes = try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return
        }
        mainHandler.post {
            FileDownloads.saveBytes(activity, bytes, safeFilename(filename), mimeType)
        }
    }

    private fun safeFilename(name: String): String {
        val cleaned = name.ifBlank { "download" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (cleaned.contains('.')) cleaned else "$cleaned.pdf"
    }
}
