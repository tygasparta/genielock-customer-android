package org.gadgetgenie.customer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var offlineView: android.view.View

    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isShowingOffline = false

    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingFileCallback: android.webkit.ValueCallback<Array<Uri>>? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val request = pendingPermissionRequest
            pendingPermissionRequest = null
            if (request == null) return@registerForActivityResult
            if (granted) {
                val toGrant = request.resources.filter { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
                if (toGrant.isNotEmpty()) request.grant(toGrant.toTypedArray()) else request.deny()
            } else {
                request.deny()
            }
        }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingFileCallback
            pendingFileCallback = null
            val data = result.data
            val uris = if (result.resultCode == RESULT_OK && data != null) {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
            } else {
                null
            }
            callback?.onReceiveValue(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Show over the Knox Guard lock screen without unlocking the device — required
        // before setContentView so the window already carries these flags on first draw.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        setContentView(R.layout.activity_main)
        applySystemBarAppearance()

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        offlineView = findViewById(R.id.offlineView)
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        configureWebView()
        swipeRefresh.setOnRefreshListener { webView.reload() }
        offlineView.findViewById<android.view.View>(R.id.retryButton).setOnClickListener { retry() }
        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
        }

        if (savedInstanceState == null) {
            loadPortalIfPossible()
        }
    }

    override fun onStart() {
        super.onStart()
        registerNetworkCallback()
    }

    override fun onStop() {
        unregisterNetworkCallback()
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        // The portal uses an httpOnly session cookie — flush so a kill between onPause and
        // the next launch doesn't lose the sign-in.
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        webView.apply {
            (parent as? android.view.ViewGroup)?.removeView(this)
            destroy()
        }
        super.onDestroy()
    }

    private fun applySystemBarAppearance() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        @Suppress("DEPRECATION")
        window.statusBarColor = getColor(R.color.brand_blue)
        @Suppress("DEPRECATION")
        window.navigationBarColor = getColor(R.color.white)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = true
    }

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString + getString(R.string.ua_suffix)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }

        webView.addJavascriptInterface(DownloadBridge(this), "AndroidDownloadBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    openExternally(uri)
                    return true
                }
                val host = uri.host?.lowercase().orEmpty()
                val portalHost = getString(R.string.portal_host)
                val isPortal = host == portalHost || host.endsWith(".$portalHost")
                if (!isPortal) {
                    openExternally(uri)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                hideOfflineView()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                view.evaluateJavascript(BLOB_DOWNLOAD_JS, null)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    swipeRefresh.isRefreshing = false
                    showOfflineView()
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: android.net.http.SslError
            ) {
                // Never proceed past a cert error — cancel unconditionally.
                handler.cancel()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val wantsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                if (!wantsCamera) {
                    request.deny()
                    return
                }
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    this@MainActivity, android.Manifest.permission.CAMERA
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (granted) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                } else {
                    pendingPermissionRequest = request
                    requestCameraPermission.launch(android.Manifest.permission.CAMERA)
                }
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: android.webkit.ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback
                val intent = fileChooserParams.createIntent()
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "image/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    try {
                        fileChooserLauncher.launch(fallback)
                        true
                    } catch (e2: ActivityNotFoundException) {
                        pendingFileCallback = null
                        filePathCallback.onReceiveValue(null)
                        false
                    }
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
            FileDownloads.startManagedDownload(this, url, userAgent, mimeType, filename)
        }
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app found to open this link", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            // Can happen when the device is locked and policy blocks launching external apps.
            Toast.makeText(this, "Can't open that right now — unlock your phone first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadPortalIfPossible() {
        if (isOnline()) {
            webView.loadUrl(getString(R.string.portal_url))
        } else {
            showOfflineView()
        }
    }

    private fun retry() {
        if (isOnline()) {
            hideOfflineView()
            if (webView.url.isNullOrEmpty()) {
                webView.loadUrl(getString(R.string.portal_url))
            } else {
                webView.reload()
            }
        } else {
            Toast.makeText(this, "Still no connection", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showOfflineView() {
        if (isShowingOffline) return
        isShowingOffline = true
        offlineView.visibility = android.view.View.VISIBLE
        swipeRefresh.visibility = android.view.View.INVISIBLE
    }

    private fun hideOfflineView() {
        if (!isShowingOffline) return
        isShowingOffline = false
        offlineView.visibility = android.view.View.GONE
        swipeRefresh.visibility = android.view.View.VISIBLE
    }

    private fun isOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    if (isShowingOffline) {
                        hideOfflineView()
                        webView.loadUrl(getString(R.string.portal_url))
                    }
                }
            }
        }
        networkCallback = callback
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // Already unregistered — nothing to do.
        }
    }

    companion object {
        // Intercepts clicks on blob:/data: links (receipt & agreement PDFs the portal
        // generates client-side) and hands the bytes to DownloadBridge, since WebView's
        // native DownloadListener can't resolve those URL schemes itself.
        private const val BLOB_DOWNLOAD_JS = """
            (function() {
                if (window.__genielockBlobHookInstalled) return;
                window.__genielockBlobHookInstalled = true;
                document.addEventListener('click', function(event) {
                    var el = event.target;
                    while (el && el.tagName !== 'A') { el = el.parentElement; }
                    if (!el || !el.href) return;
                    if (!/^(blob:|data:)/i.test(el.href)) return;
                    event.preventDefault();
                    var filename = el.getAttribute('download') || 'download.pdf';
                    fetch(el.href).then(function(r) { return r.blob(); }).then(function(blob) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            if (window.AndroidDownloadBridge) {
                                window.AndroidDownloadBridge.saveBase64(
                                    reader.result, filename, blob.type || 'application/pdf'
                                );
                            }
                        };
                        reader.readAsDataURL(blob);
                    });
                }, true);
            })();
        """
    }
}
