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

/**
 * WebView diagnostica per MaxStream.
 *
 * Mostra la pagina quando viene rilevata una challenge/browser-check,
 * senza automatizzare il superamento della protezione e senza estrarre
 * il player o lo stream.
 */
object MaxStreamWebView {

    private const val TAG = "MAXSTREAM_WEBVIEW"

    private var activityRef: WeakReference<Activity>? = null

    fun setContext(context: Context) {
        val activity = context.findActivity()

        if (activity != null) {
            activityRef = WeakReference(activity)
            Log.d(TAG, "Activity registrata: ${activity.javaClass.name}")
        } else {
            Log.e(TAG, "Activity non disponibile")
        }
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this

        while (current is ContextWrapper) {
            if (current is Activity) {
                return current
            }

            val base = current.baseContext

            if (base === current) {
                break
            }

            current = base
        }

        return current as? Activity
    }

    fun openForInspection(
        url: String,
        userAgent: String,
        referer: String?
    ) {

        val activity = activityRef?.get()

        if (activity == null) {
            Log.e(TAG, "Impossibile aprire WebView: Activity non registrata")
            return
        }

        activity.runOnUiThread {

            val dialog = Dialog(activity)
            val container = FrameLayout(activity)
            val webView = WebView(activity)

            val progressBar = ProgressBar(
                activity,
                null,
                android.R.attr.progressBarStyleHorizontal
            )

            progressBar.max = 100

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

            dialog.setContentView(container)

            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.BLACK))
                setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            dialog.setOnShowListener {
                dialog.window?.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true

                loadWithOverviewMode = true
                useWideViewPort = true

                mixedContentMode =
                    WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                userAgentString = userAgent

                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture = true
            }

            webView.webChromeClient =
                object : WebChromeClient() {

                    override fun onProgressChanged(
                        view: WebView?,
                        newProgress: Int
                    ) {

                        progressBar.progress = newProgress

                        progressBar.visibility =
                            if (newProgress >= 100)
                                android.view.View.GONE
                            else
                                android.view.View.VISIBLE
                    }
                }

            webView.webViewClient =
                object : WebViewClient() {

                    private fun isAllowedUrl(
                        url: String?
                    ): Boolean {

                        if (url.isNullOrBlank()) {
                            return false
                        }

                        if (
                            url.startsWith("data:") ||
                            url.startsWith("blob:") ||
                            url.startsWith("about:")
                        ) {
                            return true
                        }

                        return try {

                            val host =
                                android.net.Uri
                                    .parse(url)
                                    .host
                                    ?.lowercase()
                                    .orEmpty()

                            host == "maxstream.video" ||
                                host.endsWith(".maxstream.video") ||
                                host == "maxwe241.site" ||
                                host.endsWith(".maxwe241.site")

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
                        ) { result ->

                            Log.d(
                                TAG,
                                "PLAYER CHECK = $result"
                            )

                            val detected =
                                result.contains(
                                    "\\\"hasVideo\\\":true"
                                ) ||
                                result.contains(
                                    "\\\"hasIframe\\\":true"
                                ) ||
                                result.contains(
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
                        url: String?
                    ): Boolean {

                        Log.d(
                            TAG,
                            "NAV = $url"
                        )

                        if (
                            !isAllowedUrl(
                                url
                            )
                        ) {

                            Log.d(
                                TAG,
                                "NAV ESTERNA BLOCCATA = $url"
                            )

                            return true
                        }

                        return false
                    }

                    override fun onPageFinished(
                        view: WebView?,
                        url: String?
                    ) {

                        Log.d(
                            TAG,
                            "PAGE FINISH = $url"
                        )

                        view?.evaluateJavascript(
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
            }

            dialog.show()

            val extraHeaders =
                mutableMapOf<String, String>()

            if (!referer.isNullOrBlank()) {
                extraHeaders["Referer"] = referer
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
