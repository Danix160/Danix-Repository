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

enum class MaxStreamWebViewResult {
    READY,
    CANCELLED,
    TIMEOUT
}

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

            return MaxStreamWebViewResult.CANCELLED
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
                                                const video =
                                                    document.querySelector('video');

                                                const iframe =
                                                    document.querySelector(
                                                        'iframe[src], iframe[data-src]'
                                                    );

                                                const player =
                                                    document.querySelector(
                                                        '[id*="player"], ' +
                                                        '[class*="player"], ' +
                                                        '[id*="video"], ' +
                                                        '[class*="video"]'
                                                    );

                                                const result = {
                                                    title:
                                                        document.title || '',

                                                    hasVideo:
                                                        !!video,

                                                    hasIframe:
                                                        !!iframe,

                                                    hasPlayer:
                                                        !!player,

                                                    videoReady:
                                                        video
                                                            ? video.readyState
                                                            : -1,

                                                    videoPaused:
                                                        video
                                                            ? video.paused
                                                            : true,

                                                    videoDuration:
                                                        (
                                                            video &&
                                                            Number.isFinite(video.duration)
                                                        )
                                                            ? Math.round(video.duration)
                                                            : -1
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

                                        val detected =
                                            playerResult.contains(
                                                "\\\"hasVideo\\\":true"
                                            ) ||
                                                playerResult.contains(
                                                    "\\\"hasIframe\\\":true"
                                                ) ||
                                                playerResult.contains(
                                                    "\\\"hasPlayer\\\":true"
                                                )

                                        if (detected) {

                                            Log.d(
                                                TAG,
                                                ">>> PLAYER/VIDEO RILEVATO NELLA PAGINA <<<"
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

                                                            complete(
                                                                MaxStreamWebViewResult.READY
                                                            )
                                                        },
                                                        3500
                                                    )
                                                }
                                            }
                                        }
                                }
                            }

                        dialog.setOnDismissListener {

                            try {
                                webView.stopLoading()
                            } catch (_: Exception) {
                            }

                            try {
                                webView.destroy()
                            } catch (_: Exception) {
                            }

                            if (
                                !completed.get()
                            ) {

                                complete(
                                    MaxStreamWebViewResult.CANCELLED
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
            ?: MaxStreamWebViewResult.TIMEOUT
    }
}
