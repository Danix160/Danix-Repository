package com.onlineserietv

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
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
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

object UprotWebView {

    private const val TAG = "UPROT_WEBVIEW"

    private var activityRef: WeakReference<Activity>? = null

    fun setContext(context: Context) {
        val activity = context.findActivity()

        if (activity != null) {
            activityRef = WeakReference(activity)

            Log.e(
                TAG,
                "Activity CloudStream registrata: ${activity.javaClass.name}"
            )
        } else {
            Log.e(
                TAG,
                "Impossibile trovare Activity dal context: ${context.javaClass.name}"
            )
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

    suspend fun resolve(
        url: String,
        userAgent: String
    ): String? = suspendCancellableCoroutine { continuation ->

        Log.e(TAG, ">>> UprotWebView.resolve CHIAMATA <<<")
        Log.e(TAG, "URL = $url")

        val activity = activityRef?.get()

        if (activity == null) {
            Log.e(TAG, "Activity CloudStream non disponibile")
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        Log.e(
            TAG,
            "Uso Activity: ${activity.javaClass.name}"
        )

        activity.runOnUiThread {

            var completed = false

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

            fun finish(result: String?) {

                if (completed)
                    return

                completed = true

                Log.d(
                    TAG,
                    "Risultato WebView = $result"
                )

                try {
                    webView.stopLoading()
                    webView.destroy()
                } catch (_: Exception) {
                }

                try {
                    dialog.dismiss()
                } catch (_: Exception) {
                }

                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            dialog.setOnCancelListener {
                finish(null)
            }

            continuation.invokeOnCancellation {
                activity.runOnUiThread {
                    try {
                        dialog.dismiss()
                    } catch (_: Exception) {
                    }
                }
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

                    private fun checkUrl(
                        targetUrl: String?
                    ): Boolean {

                        val target =
                            targetUrl ?: return false

                        Log.d(
                            TAG,
                            "NAV = $target"
                        )

                        if (
                            target.contains(
                                "maxstream.video/uprots/",
                                ignoreCase = true
                            ) ||
                            target.contains(
                                "maxstream.video/emiuhi/",
                                ignoreCase = true
                            )
                        ) {

                            finish(target)

                            return true
                        }

                        return false
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {

                        return checkUrl(
                            request
                                ?.url
                                ?.toString()
                        )
                    }

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        url: String?
                    ): Boolean {

                        return checkUrl(url)
                    }

                    override fun onPageStarted(
                        view: WebView?,
                        url: String?,
                        favicon: Bitmap?
                    ) {

                        Log.d(
                            TAG,
                            "PAGE START = $url"
                        )

                        checkUrl(url)
                    }

                    override fun onPageFinished(
                        view: WebView?,
                        url: String?
                    ) {

                        Log.d(
                            TAG,
                            "PAGE FINISH = $url"
                        )
                    }
                }

            Log.d(
                TAG,
                "Apro WebView: $url"
            )

            dialog.show()

            webView.loadUrl(
                url,
                mapOf(
                    "Referer" to
                        "https://onlineserietv.mom/"
                )
            )
        }
    }
}
