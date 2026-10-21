# Keep the JS download bridge — its methods are invoked by WebView reflection and would
# otherwise be stripped/renamed by R8, silently breaking PDF downloads from blob:/data: URLs.
-keepclassmembers class org.gadgetgenie.customer.DownloadBridge {
    @android.webkit.JavascriptInterface <methods>;
}
