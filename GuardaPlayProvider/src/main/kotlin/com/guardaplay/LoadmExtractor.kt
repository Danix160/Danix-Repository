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

                val activityThreadClass =
                    Class.forName(
                        "android.app.ActivityThread"
                    )

                val currentActivityThreadMethod =
                    activityThreadClass.getMethod(
                        "currentActivityThread"
                    )

                val activityThread =
                    currentActivityThreadMethod.invoke(
                        null
                    )

                val getApplicationMethod =
                    activityThreadClass.getMethod(
                        "getApplication"
                    )

                getApplicationMethod.invoke(
                    activityThread
                ) as? Application

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Impossibile recuperare Application context: ${e.message}",
                    e
                )

                null
            }
        }

       private fun isVideoUrl(
    url: String
): Boolean {

    return try {

        val uri =
            android.net.Uri.parse(url)

        val host =
            uri.host
                ?.lowercase()
                ?: return false

        val path =
            uri.path
                ?.lowercase()
                ?: ""

        /*
         * Escludiamo esplicitamente tracker,
         * pubblicità e analytics.
         */
        val blockedHosts =
            listOf(
                "yandex.",
                "google-analytics.",
                "googletagmanager.",
                "googlesyndication.",
                "doubleclick.",
                "2mdn.net"
            )

        if (
            blockedHosts.any {
                host.contains(it)
            }
        ) {

            Log.d(
                TAG,
                "IGNORATO TRACKER = $url"
            )

            return false
        }

        /*
         * IMPORTANTE:
         *
         * controlliamo solamente il PATH.
         *
         * Non l'intero URL, perché parametri
         * analytics possono contenere ".mp4"
         * senza essere realmente video.
         */
        val isM3u8 =
            path.endsWith(".m3u8")

        val isMp4 =
            path.endsWith(".mp4")

        if (
            isM3u8 ||
            isMp4
        ) {

            Log.e(
                TAG,
                "POSSIBILE STREAM REALE: host=$host path=$path"
            )

            true

        } else {

            false
        }

    } catch (
        e: Exception
    ) {

        Log.e(
            TAG,
            "Errore analisi URL: ${e.message}"
        )

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

        Log.d(
            TAG,
            "=============================="
        )

        Log.d(
            TAG,
            "URL = $url"
        )

        Log.d(
            TAG,
            "REFERER = $referer"
        )

        val videoUrl =
            extractWithWebView(
                embedUrl = url,
                referer = referer
            )

        if (
            videoUrl.isNullOrBlank()
        ) {

            Log.e(
                TAG,
                "Nessun flusso video trovato"
            )

            return
        }

        Log.d(
            TAG,
            "VIDEO TROVATO = $videoUrl"
        )

        val cookies =
            try {

                CookieManager
                    .getInstance()
                    .getCookie(
                        mainUrl
                    )

            } catch (_: Exception) {

                null
            }

        val streamHeaders =
            mutableMapOf(
                "User-Agent" to
                    USER_AGENT,

                "Referer" to
                    "$mainUrl/",

                "Origin" to
                    mainUrl,

                "Accept" to
                    "*/*"
            )

        if (
            !cookies.isNullOrBlank()
        ) {

            streamHeaders[
                "Cookie"
            ] = cookies

            Log.d(
                TAG,
                "Cookie applicati allo stream"
            )
        }

        /*
         * M3U8
         */
        if (
            videoUrl.contains(
                ".m3u8",
                ignoreCase = true
            )
        ) {

            try {

                val links =
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = videoUrl,
                        referer = "$mainUrl/",
                        headers = streamHeaders
                    )

                if (
                    links.isNotEmpty()
                ) {

                    links.forEach { link ->

                        Log.d(
                            TAG,
                            "M3U8 callback = ${link.url}"
                        )

                        callback(
                            link
                        )
                    }

                    return
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "generateM3u8 fallito: ${e.message}"
                )
            }

            /*
             * Fallback M3U8 diretto.
             */
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type =
                        ExtractorLinkType.M3U8
                ) {

                    this.referer =
                        "$mainUrl/"

                    this.headers =
                        streamHeaders

                    this.quality =
                        Qualities.Unknown.value
                }
            )

            return
        }

        /*
         * MP4
         */
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                type =
                    ExtractorLinkType.VIDEO
            ) {

                this.referer =
                    "$mainUrl/"

                this.headers =
                    streamHeaders

                this.quality =
                    Qualities.Unknown.value
            }
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractWithWebView(
        embedUrl: String,
        referer: String?
    ): String? =
        suspendCancellableCoroutine {
                continuation ->

            val handler =
                Handler(
                    Looper.getMainLooper()
                )

            var webView: WebView? =
                null

            var completed =
                false

            var requestCount =
                0

            fun finish(
                result: String?
            ) {

                if (completed) {
                    return
                }

                completed = true

                Log.d(
                    TAG,
                    "WEBVIEW RESULT = $result"
                )

                handler.post {

                    try {

                        webView
                            ?.stopLoading()

                    } catch (_: Exception) {
                    }

                    try {

                        webView
                            ?.loadUrl(
                                "about:blank"
                            )

                    } catch (_: Exception) {
                    }

                    try {

                        webView
                            ?.clearHistory()

                    } catch (_: Exception) {
                    }

                    try {

                        webView
                            ?.removeAllViews()

                    } catch (_: Exception) {
                    }

                    try {

                        webView
                            ?.destroy()

                    } catch (_: Exception) {
                    }

                    webView =
                        null
                }

                if (
                    continuation.isActive
                ) {

                    continuation.resume(
                        result
                    )
                }
            }

            continuation
                .invokeOnCancellation {

                    handler.post {

                        try {

                            webView
                                ?.stopLoading()

                            webView
                                ?.destroy()

                        } catch (_: Exception) {
                        }

                        webView =
                            null
                    }
                }

            handler.post {

                try {

                    val context =
                        getApplicationContext()

                    if (context == null) {

                        Log.e(
                            TAG,
                            "Application context non disponibile"
                        )

                        finish(
                            null
                        )

                        return@post
                    }

                    val cookieManager =
                        CookieManager
                            .getInstance()

                    cookieManager
                        .setAcceptCookie(
                            true
                        )

                    val view =
                        WebView(
                            context
                        )

                    webView =
                        view

                    /*
                     * Software rendering:
                     * utile se WebGL/GPU dà problemi.
                     */
                    view.setLayerType(
                        View.LAYER_TYPE_SOFTWARE,
                        null
                    )

                    cookieManager
                        .setAcceptThirdPartyCookies(
                            view,
                            true
                        )

                    view.settings.apply {

                        javaScriptEnabled =
                            true

                        domStorageEnabled =
                            true

                        databaseEnabled =
                            true

                        javaScriptCanOpenWindowsAutomatically =
                            false

                        mediaPlaybackRequiresUserGesture =
                            false

                        mixedContentMode =
                            WebSettings
                                .MIXED_CONTENT_COMPATIBILITY_MODE

                        userAgentString =
                            USER_AGENT

                        loadWithOverviewMode =
                            true

                        useWideViewPort =
                            true
                    }

                    view.webViewClient =
                        object :
                            WebViewClient() {

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {

                                val target =
                                    request
                                        ?.url
                                        ?.toString()

                                Log.d(
                                    TAG,
                                    "NAV = $target"
                                )

                                /*
                                 * Lasciamo navigare LoadM
                                 * normalmente.
                                 */
                                return false
                            }

                            @Suppress("DEPRECATION")
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                url: String?
                            ): Boolean {

                                Log.d(
                                    TAG,
                                    "NAV OLD = $url"
                                )

                                return false
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {

                                val requestUrl =
                                    request
                                        ?.url
                                        ?.toString()
                                        ?: return super
                                            .shouldInterceptRequest(
                                                view,
                                                request
                                            )

                                requestCount++

                                Log.d(
                                    TAG,
                                    "REQ #$requestCount " +
                                        "${request.method} = " +
                                        requestUrl.take(
                                            500
                                        )
                                )

                                if (
                                    !completed &&
                                    isVideoUrl(
                                        requestUrl
                                    )
                                ) {

                                    Log.e(
                                        TAG,
                                        ">>> VIDEO INTERCETTATO <<<"
                                    )

                                    Log.e(
                                        TAG,
                                        requestUrl
                                    )

                                    /*
                                     * Facciamo terminare la
                                     * richiesta corrente e poi
                                     * chiudiamo la WebView.
                                     */
                                    handler.postDelayed(
                                        {

                                            finish(
                                                requestUrl
                                            )

                                        },
                                        250
                                    )
                                }

                                return super
                                    .shouldInterceptRequest(
                                        view,
                                        request
                                    )
                            }

                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon:
                                    android.graphics.Bitmap?
                            ) {

                                Log.d(
                                    TAG,
                                    "PAGE START = $url"
                                )

                                super
                                    .onPageStarted(
                                        view,
                                        url,
                                        favicon
                                    )
                            }

                            override fun onPageFinished(
                                view: WebView?,
                                url: String?
                            ) {

                                Log.d(
                                    TAG,
                                    "PAGE FINISH = $url"
                                )

                                super
                                    .onPageFinished(
                                        view,
                                        url
                                    )

                                /*
                                 * LoadM spesso richiede
                                 * il click sul player.
                                 */
                                if (
                                    url?.contains(
                                        "loadm.",
                                        ignoreCase = true
                                    ) == true
                                ) {

                                    handler.postDelayed(
                                        {

                                            if (
                                                completed
                                            ) {
                                                return@postDelayed
                                            }

                                            Log.d(
                                                TAG,
                                                "Cerco #player-button"
                                            )

                                            view
                                                ?.evaluateJavascript(
                                                    """
                                                    (function() {

                                                        try {

                                                            var btn =
                                                                document.getElementById(
                                                                    'player-button'
                                                                );

                                                            if (btn) {
                                                                btn.click();
                                                            }

                                                            var video =
                                                                document.querySelector(
                                                                    'video'
                                                                );

                                                            if (video) {

                                                                try {
                                                                    video.muted = true;
                                                                    video.play();
                                                                } catch(e) {}
                                                            }

                                                        } catch(e) {}

                                                    })();
                                                    """.trimIndent(),
                                                    null
                                                )

                                        },
                                        1500
                                    )
                                }
                            }

                            override fun onLoadResource(
                                view: WebView?,
                                url: String?
                            ) {

                                if (
                                    !url.isNullOrBlank()
                                ) {

                                    Log.d(
                                        TAG,
                                        "RESOURCE = ${
                                            url.take(
                                                300
                                            )
                                        }"
                                    )
                                }

                                super
                                    .onLoadResource(
                                        view,
                                        url
                                    )
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request:
                                    WebResourceRequest?,
                                error:
                                    android.webkit.WebResourceError?
                            ) {

                                Log.e(
                                    TAG,
                                    "WEB ERROR = " +
                                        "${error?.description} " +
                                        "URL=${request?.url}"
                                )

                                super
                                    .onReceivedError(
                                        view,
                                        request,
                                        error
                                    )
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request:
                                    WebResourceRequest?,
                                errorResponse:
                                    WebResourceResponse?
                            ) {

                                Log.w(
                                    TAG,
                                    "HTTP ERROR = " +
                                        "${errorResponse?.statusCode} " +
                                        "URL=${request?.url}"
                                )

                                super
                                    .onReceivedHttpError(
                                        view,
                                        request,
                                        errorResponse
                                    )
                            }
                        }

                    val loadHeaders =
                        mutableMapOf<String, String>()

                    if (
                        !referer.isNullOrBlank()
                    ) {

                        loadHeaders[
                            "Referer"
                        ] = referer
                    }

                    Log.d(
                        TAG,
                        "WEBVIEW LOAD = $embedUrl"
                    )

                    view.loadUrl(
                        embedUrl,
                        loadHeaders
                    )

                    /*
                     * Timeout.
                     */
                    handler.postDelayed(
                        {

                            if (
                                !completed
                            ) {

                                Log.e(
                                    TAG,
                                    "TIMEOUT dopo " +
                                        "$TIMEOUT_SECONDS secondi. " +
                                        "Richieste intercettate = " +
                                        requestCount
                                )

                                finish(
                                    null
                                )
                            }

                        },
                        TIMEOUT_SECONDS *
                            1000
                    )

                } catch (
                    e: Exception
                ) {

                    Log.e(
                        TAG,
                        "ERRORE WEBVIEW: ${e.message}",
                        e
                    )

                    finish(
                        null
                    )
                }
            }
        }
}
