package com.cb

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

enum class MaxStreamWebViewStatus {
    PLAYER_FOUND,
    CANCELLED,
    TIMEOUT
}

data class MaxStreamWebViewResult(
    val status: MaxStreamWebViewStatus,
    val finalUrl: String? = null,
    val playerUrl: String? = null,
    val playerHost: String? = null,
    val iframeCount: Int = 0,
    val videoCount: Int = 0,
    val sourceCount: Int = 0
)

/**
 * WebView diagnostica per MaxStream.
 *
 * Mostra la pagina quando viene rilevata una challenge/browser-check,
 * blocca le navigazioni esterne/pubblicitarie e rileva la presenza
 * di un video/iframe/player.
 *
 * Non estrae l'URL dello stream e non automatizza il superamento
 * della protezione del sito.
 */
object MaxStreamWebView {

    private const val TAG = "MAXSTREAM_WEBVIEW"
    private const val TIMEOUT_MS = 45_000L

    private var activityRef: WeakReference<Activity>? = null

    fun setContext(context: Context) {

        val activity =
            context.findActivity()

        if (activity != null) {

            activityRef =
                WeakReference(activity)

            Log.d(
                TAG,
                "Activity registrata: ${activity.javaClass.name}"
            )

        } else {

            Log.e(
                TAG,
                "Activity non disponibile"
            )
        }
    }

    private fun Context.findActivity(): Activity? {

        var current: Context? =
            this

        while (current is ContextWrapper) {

            if (current is Activity) {
                return current
            }

            val base =
                current.baseContext

            if (base === current) {
                break
            }

            current =
                base
        }

        return current as? Activity
    }

    suspend fun openForInspection(
        url: String,
        userAgent: String,
        referer: String?
    ): MaxStreamWebViewResult {

        val activity =
            activityRef
                ?.get()

        if (activity == null) {

            Log.e(
                TAG,
                "Impossibile aprire WebView: Activity non registrata"
            )

            return MaxStreamWebViewResult(
                status = MaxStreamWebViewStatus.CANCELLED
            )
        }

        val result =
            withTimeoutOrNull(
                TIMEOUT_MS
            ) {

                suspendCancellableCoroutine<MaxStreamWebViewResult> { continuation ->

                    activity.runOnUiThread {

                        val completed =
                            AtomicBoolean(false)

                        val dialog =
                            Dialog(activity)

                        val container =
                            FrameLayout(activity)

                        val webView =
                            WebView(activity)

                        val progressBar =
                            ProgressBar(
                                activity,
                                null,
                                android.R.attr.progressBarStyleHorizontal
                            )

                        fun complete(
                            value: MaxStreamWebViewResult
                        ) {

                            if (
                                !completed.compareAndSet(
                                    false,
                                    true
                                )
                            ) {
                                return
                            }

                            Log.d(
                                TAG,
                                "RISULTATO = $value"
                            )

                            if (continuation.isActive) {
                                continuation.resume(value)
                            }

                            activity.runOnUiThread {
                                try {
                                    if (dialog.isShowing) {
                                        dialog.dismiss()
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        }

                        continuation.invokeOnCancellation {

                            if (
                                completed.compareAndSet(
                                    false,
                                    true
                                )
                            ) {

                                activity.runOnUiThread {
                                    try {
                                        if (dialog.isShowing) {
                                            dialog.dismiss()
                                        }
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        }

                        progressBar.max =
                            100

                        container.addView(
                            webView,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )

                        container.addView(
                            progressBar,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                12
                            )
                        )

                        dialog.setContentView(
                            container
                        )

                        dialog.window
                            ?.apply {

                                setBackgroundDrawable(
                                    ColorDrawable(
                                        Color.BLACK
                                    )
                                )

                                setLayout(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            }

                        dialog.setOnShowListener {

                            dialog.window
                                ?.setLayout(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                        }

                        webView.settings
                            .apply {

                                javaScriptEnabled =
                                    true

                                domStorageEnabled =
                                    true

                                databaseEnabled =
                                    true

                                loadWithOverviewMode =
                                    true

                                useWideViewPort =
                                    true

                                mixedContentMode =
                                    WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                                userAgentString =
                                    userAgent

                                javaScriptCanOpenWindowsAutomatically =
                                    false

                                setSupportMultipleWindows(
                                    false
                                )

                                mediaPlaybackRequiresUserGesture =
                                    true
                            }

                        webView.webChromeClient =
                            object : WebChromeClient() {

                                override fun onProgressChanged(
                                    view: WebView?,
                                    newProgress: Int
                                ) {

                                    progressBar.progress =
                                        newProgress

                                    progressBar.visibility =
                                        if (newProgress >= 100) {
                                            android.view.View.GONE
                                        } else {
                                            android.view.View.VISIBLE
                                        }
                                }
                            }

                        webView.webViewClient =
                            object : WebViewClient() {

                                private var readySequenceStarted =
                                    false

                                private fun isAllowedUrl(
                                    targetUrl: String?
                                ): Boolean {

                                    if (targetUrl.isNullOrBlank()) {
                                        return false
                                    }

                                    if (
                                        targetUrl.startsWith("data:") ||
                                        targetUrl.startsWith("blob:") ||
                                        targetUrl.startsWith("about:")
                                    ) {
                                        return true
                                    }

                                    return try {

                                        val host =
                                            android.net.Uri
                                                .parse(
                                                    targetUrl
                                                )
                                                .host
                                                ?.lowercase()
                                                .orEmpty()

                                        host == "maxstream.video" ||
                                            host.endsWith(
                                                ".maxstream.video"
                                            ) ||
                                            host == "maxwe241.site" ||
                                            host.endsWith(
                                                ".maxwe241.site"
                                            )

                                    } catch (_: Exception) {

                                        false
                                    }
                                }

                                private fun inspectPlayer(
                                    view: WebView?
                                ) {

                                    if (view == null) {
                                        return
                                    }

                                    val script =
                                        """
                                        (function() {
                                            try {

                                                const videos =
                                                    Array.from(
                                                        document.querySelectorAll('video')
                                                    );

                                                const sources =
                                                    Array.from(
                                                        document.querySelectorAll('source')
                                                    );

                                                const iframes =
                                                    Array.from(
                                                        document.querySelectorAll('iframe')
                                                    );

                                                const players =
                                                    Array.from(
                                                        document.querySelectorAll(
                                                            '[id*="player"], ' +
                                                            '[class*="player"], ' +
                                                            '[id*="video"], ' +
                                                            '[class*="video"]'
                                                        )
                                                    );

                                                const iframeInfo =
                                                    iframes.map(function(frame) {

                                                        const src =
                                                            frame.getAttribute('src') || '';

                                                        const dataSrc =
                                                            frame.getAttribute('data-src') || '';

                                                        const candidate =
                                                            src || dataSrc;

                                                        let host = '';

                                                        if (
                                                            candidate &&
                                                            candidate !== 'javascript:false' &&
                                                            candidate !== 'about:blank'
                                                        ) {

                                                            try {
                                                                host =
                                                                    new URL(
                                                                        candidate,
                                                                        window.location.href
                                                                    ).hostname;
                                                            } catch (e) {
                                                                host = '';
                                                            }
                                                        }

                                                        return {
                                                            src: src,
                                                            dataSrc: dataSrc,
                                                            host: host,
                                                            id:
                                                                frame.id || '',
                                                            name:
                                                                frame.getAttribute('name') || '',
                                                            className:
                                                                frame.className || '',
                                                            hidden:
                                                                (
                                                                    frame.hidden ||
                                                                    frame.style.display === 'none' ||
                                                                    frame.style.visibility === 'hidden'
                                                                ),
                                                            outerHTML:
                                                                frame.outerHTML
                                                                    ? frame.outerHTML.substring(0, 1200)
                                                                    : ''
                                                        };
                                                    });

                                                const videoInfo =
                                                    videos.map(function(video) {

                                                        return {
                                                            currentSrc:
                                                                video.currentSrc || '',

                                                            src:
                                                                video.getAttribute('src') || '',

                                                            readyState:
                                                                video.readyState,

                                                            paused:
                                                                video.paused,

                                                            duration:
                                                                Number.isFinite(video.duration)
                                                                    ? Math.round(video.duration)
                                                                    : -1,

                                                            id:
                                                                video.id || '',

                                                            className:
                                                                video.className || '',

                                                            outerHTML:
                                                                video.outerHTML
                                                                    ? video.outerHTML.substring(0, 1200)
                                                                    : ''
                                                        };
                                                    });

                                                const sourceInfo =
                                                    sources.map(function(source) {

                                                        return {
                                                            src:
                                                                source.getAttribute('src') || '',

                                                            type:
                                                                source.getAttribute('type') || '',

                                                            outerHTML:
                                                                source.outerHTML
                                                                    ? source.outerHTML.substring(0, 800)
                                                                    : ''
                                                        };
                                                    });

                                                const realIframes =
                                                    iframeInfo.filter(function(frame) {

                                                        const candidate =
                                                            frame.src || frame.dataSrc;

                                                        if (!candidate) {
                                                            return false;
                                                        }

                                                        if (
                                                            candidate === 'javascript:false' ||
                                                            candidate === 'about:blank'
                                                        ) {
                                                            return false;
                                                        }

                                                        if (
                                                            candidate.startsWith('javascript:')
                                                        ) {
                                                            return false;
                                                        }

                                                        return true;
                                                    });

                                                const result = {
                                                    title:
                                                        document.title || '',

                                                    location:
                                                        window.location.href,

                                                    iframeCount:
                                                        iframeInfo.length,

                                                    realIframeCount:
                                                        realIframes.length,

                                                    videoCount:
                                                        videoInfo.length,

                                                    sourceCount:
                                                        sourceInfo.length,

                                                    playerElementCount:
                                                        players.length,

                                                    iframes:
                                                        iframeInfo,

                                                    realIframes:
                                                        realIframes,

                                                    videos:
                                                        videoInfo,

                                                    sources:
                                                        sourceInfo
                                                };

                                                return JSON.stringify(result);

                                            } catch (e) {

                                                return JSON.stringify({
                                                    error:
                                                        String(e)
                                                });
                                            }
                                        })();
                                        """.trimIndent()

                                    view.evaluateJavascript(
                                        script
                                    ) { playerResult ->

                                        Log.d(
                                            TAG,
                                            "PLAYER CHECK = $playerResult"
                                        )

                                        val hasRealVideo =
                                            playerResult.contains(
                                                "\\\"videoCount\\\":1"
                                            ) ||
                                                Regex(
                                                    """\\\"videoCount\\\":([1-9][0-9]*)"""
                                                )
                                                    .containsMatchIn(
                                                        playerResult
                                                    )

                                        val hasRealIframe =
                                            Regex(
                                                """\\\"realIframeCount\\\":([1-9][0-9]*)"""
                                            )
                                                .containsMatchIn(
                                                    playerResult
                                                )

                                        val hasSource =
                                            Regex(
                                                """\\\"sourceCount\\\":([1-9][0-9]*)"""
                                            )
                                                .containsMatchIn(
                                                    playerResult
                                                )

                                        if (
                                            hasRealVideo ||
                                            hasRealIframe ||
                                            hasSource
                                        ) {
                                        
                                            Log.d(
                                                TAG,
                                                ">>> ELEMENTO PLAYER REALE RILEVATO NEL DOM <<<"
                                            )
                                        
                                            val iframeCount =
                                                Regex(
                                                    """\\\"iframeCount\\\":([0-9]+)"""
                                                )
                                                    .find(playerResult)
                                                    ?.groupValues
                                                    ?.getOrNull(1)
                                                    ?.toIntOrNull()
                                                    ?: 0

                                            val videoCount =
                                                Regex(
                                                    """\\\"videoCount\\\":([0-9]+)"""
                                                )
                                                    .find(playerResult)
                                                    ?.groupValues
                                                    ?.getOrNull(1)
                                                    ?.toIntOrNull()
                                                    ?: 0

                                            val sourceCount =
                                                Regex(
                                                    """\\\"sourceCount\\\":([0-9]+)"""
                                                )
                                                    .find(playerResult)
                                                    ?.groupValues
                                                    ?.getOrNull(1)
                                                    ?.toIntOrNull()
                                                    ?: 0

                                            val playerUrl =
                                                Regex(
                                                    """\\\"(?:src|dataSrc)\\\":\\\"(https?:[^\\\"]+)\\\""""
                                                )
                                                    .findAll(playerResult)
                                                    .mapNotNull {
                                                        it.groupValues
                                                            .getOrNull(1)
                                                            ?.replace("\\/", "/")
                                                            ?.takeIf { candidate ->
                                                                candidate.contains(
                                                                    "maxstream.video/emiuhihi/",
                                                                    ignoreCase = true
                                                                )
                                                            }
                                                    }
                                                    .firstOrNull()

                                            val playerHost =
                                                playerUrl
                                                    ?.let { candidate ->
                                                        try {
                                                            android.net.Uri
                                                                .parse(candidate)
                                                                .host
                                                        } catch (_: Exception) {
                                                            null
                                                        }
                                                    }

                                            complete(
                                                MaxStreamWebViewResult(
                                                    status =
                                                        MaxStreamWebViewStatus.PLAYER_FOUND,

                                                    finalUrl =
                                                        view.url,

                                                    playerUrl =
                                                        playerUrl,

                                                    playerHost =
                                                        playerHost,

                                                    iframeCount =
                                                        iframeCount,

                                                    videoCount =
                                                        videoCount,

                                                    sourceCount =
                                                        sourceCount
                                                )
                                            )
                                        } else {

                                            Log.d(
                                                TAG,
                                                "Nessun player reale: ignorati iframe placeholder/javascript:false"
                                            )
                                        }
                                    }
                                }

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

                                    if (
                                        !isAllowedUrl(
                                            target
                                        )
                                    ) {

                                        Log.d(
                                            TAG,
                                            "NAV ESTERNA BLOCCATA = $target"
                                        )

                                        return true
                                    }

                                    return false
                                }

                                @Suppress("DEPRECATION")
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    targetUrl: String?
                                ): Boolean {

                                    Log.d(
                                        TAG,
                                        "NAV = $targetUrl"
                                    )

                                    if (
                                        !isAllowedUrl(
                                            targetUrl
                                        )
                                    ) {

                                        Log.d(
                                            TAG,
                                            "NAV ESTERNA BLOCCATA = $targetUrl"
                                        )

                                        return true
                                    }

                                    return false
                                }

                                override fun onPageFinished(
                                    view: WebView?,
                                    finishedUrl: String?
                                ) {

                                    Log.d(
                                        TAG,
                                        "PAGE FINISH = $finishedUrl"
                                    )

                                    view
                                        ?.evaluateJavascript(
                                            "document.title"
                                        ) { title ->

                                            Log.d(
                                                TAG,
                                                "TITLE = $title"
                                            )

                                            if (
                                                title.contains(
                                                    "MaxStream Streaming Video Service",
                                                    ignoreCase = true
                                                )
                                            ) {

                                                Log.d(
                                                    TAG,
                                                    ">>> PAGINA MAXSTREAM REALE RAGGIUNTA <<<"
                                                )

                                                inspectPlayer(
                                                    view
                                                )

                                                if (
                                                    !readySequenceStarted
                                                ) {

                                                    readySequenceStarted =
                                                        true

                                                    /*
                                                     * Continuiamo a osservare il DOM:
                                                     * non restituiamo READY solo perché
                                                     * esiste un iframe placeholder.
                                                     */
                                                    inspectPlayer(
                                                        view
                                                    )

                                                    view.postDelayed(
                                                        {
                                                            inspectPlayer(
                                                                view
                                                            )
                                                        },
                                                        1500
                                                    )

                                                    view.postDelayed(
                                                        {
                                                            inspectPlayer(
                                                                view
                                                            )
                                                        },
                                                        3500
                                                    )

                                                    view.postDelayed(
                                                        {
                                                            inspectPlayer(
                                                                view
                                                            )
                                                        },
                                                        7000
                                                    )
                                                }
                                            }
                                        }
                                }
                            }

                        dialog.setOnDismissListener {

                            val lastUrl =
                                try {
                                    webView.url
                                } catch (_: Exception) {
                                    null
                                }

                            try {
                                webView.stopLoading()
                            } catch (_: Exception) {
                            }

                            try {
                                webView.webChromeClient =
                                    null

                                webView.webViewClient =
                                    WebViewClient()
                            } catch (_: Exception) {
                            }

                            webView.postDelayed(
                                {
                                    try {
                                        webView.destroy()
                                    } catch (_: Exception) {
                                    }
                                },
                                500
                            )

                            if (
                                !completed.get()
                            ) {

                                complete(
                                    MaxStreamWebViewResult(
                                        status =
                                            MaxStreamWebViewStatus.CANCELLED,

                                        finalUrl =
                                            lastUrl
                                    )
                                )
                            }
                        }

                        dialog.show()

                        val extraHeaders =
                            mutableMapOf<String, String>()

                        if (!referer.isNullOrBlank()) {

                            extraHeaders["Referer"] =
                                referer
                        }

                        Log.d(
                            TAG,
                            "Apro pagina diagnostica: $url"
                        )

                        webView.loadUrl(
                            url,
                            extraHeaders
                        )
                    }
                }
            }

        return result
            ?: MaxStreamWebViewResult(
                status =
                    MaxStreamWebViewStatus.TIMEOUT
            )
    }
}
