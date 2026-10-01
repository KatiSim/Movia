package app.movia.android.domain.legacy

import android.content.Context
import android.net.http.SslError
import android.webkit.*
import app.movia.android.domain.playback.StreamRequestProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Resolves original web-player leaves without opening legacy UI or creating another player. */
internal object LegacyWebEmbedResolver {
    data class Media(val url: String, val headers: Map<String, String>)
    suspend fun resolve(context: Context, url: String, headers: Map<String, String>): List<Media> {
        if (!LegacyProviderEngine.isMediaUrl(url)) return emptyList()
        val found = ConcurrentHashMap<String, Media>()
        var view: WebView? = null
        try {
            withContext(Dispatchers.Main) {
                view = WebView(context.applicationContext).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        mediaPlaybackRequiresUserGesture = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        setGeolocationEnabled(false)
                        headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value?.let { userAgentString = it }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                            callback.invoke(origin, false, false)
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            // Request muted HTML media only to discover its URL; intercepted bytes never reach a second player.
                            view.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.muted=true;var p=v.play();if(p)p.catch(function(){});});", null)
                        }
                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) { handler.cancel() }
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val value = request.url.toString()
                            if (!publicDestination(value)) return blocked()
                            if (isMediaRequest(value)) {
                                if (found.size < 12) {
                                    val sourceHeaders = headers.filterKeys { !it.equals("Cookie", true) }.toMutableMap()
                                    val cookie = CookieManager.getInstance().getCookie(value)
                                        ?: headers.entries.firstOrNull { it.key.equals("Cookie", true) &&
                                            StreamRequestProfile.sameOrigin(value, url) }?.value
                                    if (!cookie.isNullOrBlank() && cookie.length <= 8192 && '\r' !in cookie && '\n' !in cookie) {
                                        sourceHeaders["Cookie"] = cookie
                                    }
                                    request.requestHeaders.filterKeys {
                                        it.equals("Referer", true) || it.equals("Origin", true) || it.equals("User-Agent", true)
                                    }.forEach { (name, value) ->
                                        if (value.length <= 8192 && '\r' !in value && '\n' !in value) sourceHeaders[name] = value
                                    }
                                    found[value] = Media(value, sourceHeaders)
                                }
                                // Capture discovery, then prevent a hidden web player from consuming media.
                                return blocked()
                            }
                            return null
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            !LegacyProviderEngine.isMediaUrl(request.url.toString())
                    }
                    loadUrl(url, headers)
                }
            }
            repeat(48) {
                delay(250)
                if (found.isNotEmpty()) { delay(500); return found.values.toList() }
            }
            return emptyList()
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                view?.apply { stopLoading();loadUrl("about:blank");webViewClient=WebViewClient();destroy() }
            }
        }
    }
    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    internal fun isMediaRequest(url: String): Boolean {
        val path = runCatching { URI(url).path.lowercase() }.getOrDefault("")
        return path.matches(Regex(".*\\.(m3u8|mpd|mp4|m4v|webm)(?:/.*)?")) &&
            !path.matches(Regex(".*(?:/ads?/|preroll|advertisement|/trailers?/).*"))
    }
    private fun publicDestination(url: String): Boolean {
        if (!LegacyProviderEngine.isMediaUrl(url) || url.startsWith("magnet:")) return false
        return runCatching {
            InetAddress.getAllByName(URI(url).host).all {
                LegacyMediaHttp.isPublicAddress(it)
            }
        }.getOrDefault(false)
    }
}
