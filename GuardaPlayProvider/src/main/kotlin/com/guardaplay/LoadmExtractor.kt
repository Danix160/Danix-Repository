package com.guardaplay

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class LoadmExtractor : ExtractorApi() {

    override val name = "LoadM"
    override val mainUrl = "https://loadm.cam"
    override val requiresReferer = true

    companion object {
        private const val TAG = "LOADM_DEBUG"
        private const val TIMEOUT_SECONDS = 30L

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/127.0.0.0 Safari/537.36"

        private fun getApplicationContext(): Context? {
            return try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val currentActivityThreadMethod = activityThreadClass.getMethod("currentActivityThread")
                val activityThread = currentActivityThreadMethod.invoke(null)
                val getApplicationMethod = activityThreadClass.getMethod("getApplication")
                getApplicationMethod.invoke(activityThread) as? Application
            } catch (e: Exception) {
                Log.e(TAG, "Impossibile recuperare Application context: ${e.message}", e)
                null
            }
        }

        private fun isVideoUrl(url: String): Boolean {
            return try {
                val uri = android.net.Uri.parse(url)
                val host = uri.host?.lowercase() ?: return false
                val path = uri.path?.lowercase() ?: ""

                val blockedHosts = listOf(
                    "yandex.",
                    "google-analytics.",
                    "googletagmanager.",
                    "googlesyndication.",
                    "doubleclick.",
                    "2mdn.net",
                    "facebook.",
                    "adnxs."
                )

                if (blockedHosts.any { host.contains(it) }) {
                    return false
                }

                // Escludiamo asset statici noti e segmenti/font singoli
                if (
                    path.endsWith(".js") || path.endsWith(".css") ||
                    path.endsWith(".png") || path.endsWith(".jpg") ||
                    path.endsWith(".jpeg") || path.endsWith(".vtt") ||
                    path.endsWith(".gif") || path.endsWith(".ico") ||
                    path.endsWith(".woff") || path.endsWith(".woff2") ||
                    path.endsWith(".html")
                ) {
                    return false
                }

                // Intercetta .m3u8 standard OPPURE playlist camuffate da .txt (master/index/cf-master)
                val isM3u8 = path.contains(".m3u8") ||
                    ((path.endsWith(".txt") || path.contains(".txt")) &&
                        (path.contains("master") || path.contains("index") || path.contains("playlist") || path.contains("hls") || path.contains("cf-")))

                val isMp4 = path.endsWith(".mp4") || path.contains(".mp4")

                if (isM3u8 || isMp4) {
                    Log.e(TAG, "POSSIBILE STREAM REALE: host=$host path=$path")
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore analisi URL: ${e.message}")
                false
            }
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "==============================")
        Log.d(TAG, "URL = $url")
        Log.d(TAG, "REFERER = $referer")

        val videoUrl = extractWithWebView(embedUrl = url, referer = referer)

        if (videoUrl.isNullOrBlank()) {
            Log.e(TAG, "Nessun flusso video trovato")
            return
        }

        Log.d(TAG, "VIDEO TROVATO = $videoUrl")

        val cookies = try {
            CookieManager.getInstance().getCookie(mainUrl)
        } catch (_: Exception) {
            null
        }

        val streamHeaders = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "Accept" to "*/*"
        )

        if (!cookies.isNullOrBlank()) {
            streamHeaders["Cookie"] = cookies
            Log.d(TAG, "Cookie applicati allo stream")
        }

        val isHls = videoUrl.contains(".m3u8", ignoreCase = true) ||
            videoUrl.contains(".txt", ignoreCase = true) ||
            videoUrl.contains("master", ignoreCase = true) ||
            videoUrl.contains("index", ignoreCase = true)

        if (isHls) {
            try {
                val links = M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = videoUrl,
                    referer = "$mainUrl/",
                    headers = streamHeaders
                )

                if (links.isNotEmpty()) {
                    links.forEach { link ->
                        Log.d(TAG, "M3U8 callback = ${link.url}")
                        callback(link)
                    }
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "generateM3u8 fallito: ${e.message}")
            }

            // Fallback M3U8 diretto
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.headers = streamHeaders
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        // MP4
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "$mainUrl/"
                this.headers = streamHeaders
                this.quality = Qualities.Unknown.value
            }
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractWithWebView(
        embedUrl: String,
        referer: String?
    ): String? = suspendCancellableCoroutine { continuation ->

        val handler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        var completed = false
        var videoFound = false
        var requestCount = 0

        fun finish(result: String?) {
            if (completed) return
            completed = true

            Log.d(TAG, "WEBVIEW RESULT = $result")

            handler.post {
                try { webView?.stopLoading() } catch (_: Exception) {}
                try { webView?.loadUrl("about:blank") } catch (_: Exception) {}
                try { webView?.clearHistory() } catch (_: Exception) {}
                try { webView?.removeAllViews() } catch (_: Exception) {}
                try { webView?.destroy() } catch (_: Exception) {}
                webView = null
            }

            if (continuation.isActive) {
                continuation.resume(result)
            }
        }

        continuation.invokeOnCancellation {
            handler.post {
                try {
                    webView?.stopLoading()
                    webView?.destroy()
                } catch (_: Exception) {}
                webView = null
            }
        }

        handler.post {
            try {
                val context = getApplicationContext()
                if (context == null) {
                    Log.e(TAG, "Application context non disponibile")
                    finish(null)
                    return@post
                }

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)

                val view = WebView(context)
                webView = view

                view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                cookieManager.setAcceptThirdPartyCookies(view, true)

                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    javaScriptCanOpenWindowsAutomatically = false
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    userAgentString = USER_AGENT
                    loadWithOverviewMode = true
                    useWideViewPort = true
                }

                val playScript = """
                    (function() {
                        if (window._loadmLoopRunning) return;
                        window._loadmLoopRunning = true;

                        var clickCount = 0;
                        var triggerInterval = setInterval(function() {
                            clickCount++;
                            if (clickCount > 40) {
                                clearInterval(triggerInterval);
                                return;
                            }

                            try {
                                // 1. Click selettori noti
                                var targets = [
                                    '#player-button',
                                    '.vds-play-button',
                                    '[data-media-provider]',
                                    '.play-btn',
                                    'button[aria-label*="Play"]',
                                    'button[aria-label*="play"]',
                                    '.jw-display-icon-container'
                                ];

                                for (var i = 0; i < targets.length; i++) {
                                    var el = document.querySelector(targets[i]);
                                    if (el) {
                                        el.click();
                                    }
                                }

                                // 2. Click fittizio al centro pagina
                                var centerEl = document.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2);
                                if (centerEl) {
                                    centerEl.click();
                                }

                                // 3. Play forzato su tutti i tag video
                                var videos = document.querySelectorAll('video');
                                for (var j = 0; j < videos.length; j++) {
                                    var v = videos[j];
                                    v.muted = true;
                                    v.playsInline = true;
                                    var promise = v.play();
                                    if (promise !== undefined) {
                                        promise.catch(function() {});
                                    }
                                }
                            } catch(err) {}
                        }, 500);
                    })();
                """.trimIndent()

                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = false

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val requestUrl = request?.url?.toString()
                            ?: return super.shouldInterceptRequest(view, request)

                        requestCount++

                        if (!videoFound && !completed && isVideoUrl(requestUrl)) {
                            videoFound = true
                            Log.e(TAG, ">>> VIDEO INTERCETTATO <<<")
                            Log.e(TAG, requestUrl)

                            handler.postDelayed({
                                finish(requestUrl)
                            }, 250)
                        }

                        // Appena intercetta le prime chiamate API di loadM, prova subito ad iniettare
                        if (!videoFound && (requestUrl.contains("loadm.cam/api/") || requestUrl.contains("vidstack"))) {
                            handler.post {
                                view?.evaluateJavascript(playScript, null)
                            }
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(playScript, null)
                    }
                }

                val loadHeaders = mutableMapOf<String, String>()
                if (!referer.isNullOrBlank()) {
                    loadHeaders["Referer"] = referer
                }

                Log.d(TAG, "WEBVIEW LOAD = $embedUrl")
                view.loadUrl(embedUrl, loadHeaders)

                // Timer di backup indipendenti: forzano l'avvio anche se onPageFinished non scatta mai
                handler.postDelayed({ if (!completed) view.evaluateJavascript(playScript, null) }, 1500)
                handler.postDelayed({ if (!completed) view.evaluateJavascript(playScript, null) }, 3000)

                handler.postDelayed({
                    if (!completed) {
                        Log.e(TAG, "TIMEOUT dopo $TIMEOUT_SECONDS secondi. Richieste intercettate = $requestCount")
                        finish(null)
                    }
                }, TIMEOUT_SECONDS * 1000)

            } catch (e: Exception) {
                Log.e(TAG, "ERRORE WEBVIEW: ${e.message}", e)
                finish(null)
            }
        }
    }
}
