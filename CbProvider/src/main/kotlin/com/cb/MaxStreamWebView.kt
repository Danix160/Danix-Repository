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

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {

                        Log.d(
                            TAG,
                            "NAV = ${request?.url}"
                        )

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
