package com.cb

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
import android.webkit.CookieManager
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

            val cookieManager = CookieManager.getInstance()

            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)

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
                
                    val cookieManager =
                        CookieManager.getInstance()
                
                    val uprotCookies =
                        cookieManager.getCookie(
                            "https://uprot.net"
                        ).orEmpty()
                
                    val maxstreamCookies =
                        cookieManager.getCookie(
                            "https://maxstream.video"
                        ).orEmpty()
                
                    val allCookies =
                        listOf(
                            uprotCookies,
                            maxstreamCookies
                        )
                            .filter { it.isNotBlank() }
                            .joinToString("; ")
                
                    UprotSession.cookieHeader = allCookies
                    UprotSession.userAgent =
                        webView.settings.userAgentString.orEmpty()
                
                    Log.d(
                        TAG,
                        "Cookie sessione salvati = ${allCookies.isNotBlank()}"
                    )
                
                    try {
                        webView.stopLoading()
                    } catch (_: Exception) {
                    }
                
                    try {
                        dialog.dismiss()
                    } catch (_: Exception) {
                    }
                
                    /*
                     * Distruggiamo solo DOPO aver tolto
                     * la WebView dalla finestra.
                     */
                    webView.post {
                        try {
                            webView.destroy()
                        } catch (_: Exception) {
                        }
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
            
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message?
                    ): Boolean {
                        Log.d(TAG, "Popup bloccato")
                        return false
                    }
                }

                        webView.webViewClient =
                object : WebViewClient() {

                    private fun checkUrl(
                                targetUrl: String?
                            ): Boolean {
                            
                                val target = targetUrl ?: return false
                            
                                Log.d(TAG, "NAV = $target")
                            
                                val uri =
                                    try {
                                        android.net.Uri.parse(target)
                                    } catch (_: Exception) {
                                        return true
                                    }
                            
                                val host =
                                    uri.host
                                        ?.lowercase()
                                        .orEmpty()
                            
                                // MaxStream finale valido
                                if (
                                    host.equals(
                                        "maxstream.video",
                                        ignoreCase = true
                                    ) &&
                                    (
                                        target.contains(
                                            "/uprotem/",
                                            ignoreCase = true
                                        ) ||
                                        target.contains(
                                            "/emiuhi/",
                                            ignoreCase = true
                                        ) ||
                                        target.contains(
                                            "/uprots/",
                                            ignoreCase = true
                                        )
                                    )
                                ) {
                            
                                    Log.d(
                                        TAG,
                                        "MAXSTREAM intercettato dalla navigazione: $target"
                                    )
                            
                                    finish(target)
                            
                                    return true
                                }
                            
                                // Navigazione interna Uprot consentita
                                if (
                                    host.equals(
                                        "uprot.net",
                                        ignoreCase = true
                                    ) ||
                                    host.endsWith(
                                        ".uprot.net",
                                        ignoreCase = true
                                    )
                                ) {
                                    return false
                                }
                            
                                // Pubblicità / popup / redirect esterni
                                Log.d(
                                    TAG,
                                    "Navigazione esterna bloccata: $target"
                                )
                            
                                return true
                            }
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        return checkUrl(
                            request?.url?.toString()
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
                        Log.d(TAG, "PAGE START = $url")
                        checkUrl(url)
                    }

                    override fun onPageFinished(
                        view: WebView?,
                        url: String?
                    ) {
                        Log.d(TAG, "PAGE FINISH = $url")

                        val cookies =
                            CookieManager.getInstance()
                                .getCookie("https://uprot.net")

                        Log.d(
                            TAG,
                            "Cookie Uprot dopo pagina presenti = ${
                                !cookies.isNullOrBlank()
                            }"
                        )

                        fun searchMaxstream(attempt: Int = 0) {

                            if (completed || attempt >= 30) {
                                return
                            }
                        
                            view?.evaluateJavascript(
                                """
                                (function() {
                        
                                    // 1. Prima cerchiamo il vero pulsante Continue
                                    var button = document.querySelector('button#buttok');
                        
                                    if (button) {
                                        var parent = button.closest('a[href]');
                        
                                        if (parent && parent.href) {
                                            var href = parent.href;
                        
                                            if (
                                                href.indexOf('https://maxstream.video/uprotem/') === 0 ||
                                                href.indexOf('https://maxstream.video/emiuhi/') === 0 ||
                                                href.indexOf('https://maxstream.video/uprots/') === 0
                                            ) {
                                                return href;
                                            }
                                        }
                                    }
                        
                                    // 2. Cerchiamo un anchor che contenga un button Continue
                                    var anchors = document.querySelectorAll('a[href]');
                        
                                    for (var i = 0; i < anchors.length; i++) {
                        
                                        var a = anchors[i];
                                        var btn = a.querySelector('button');
                        
                                        if (!btn)
                                            continue;
                        
                                        var text =
                                            (btn.innerText || btn.textContent || '')
                                                .replace(/\s+/g, '')
                                                .toUpperCase();
                        
                                        var href = a.href || '';
                        
                                        if (
                                            text.indexOf('CONTINUE') !== -1 &&
                                            (
                                                href.indexOf('https://maxstream.video/uprotem/') === 0 ||
                                                href.indexOf('https://maxstream.video/emiuhi/') === 0 ||
                                                href.indexOf('https://maxstream.video/uprots/') === 0
                                            )
                                        ) {
                                            return href;
                                        }
                                    }
                        
                                    // 3. Come fallback preferiamo i nuovi /uprotem/
                                    for (var j = 0; j < anchors.length; j++) {
                        
                                        var fallbackHref = anchors[j].href || '';
                        
                                        if (
                                            fallbackHref.indexOf(
                                                'https://maxstream.video/uprotem/'
                                            ) === 0 ||
                                            fallbackHref.indexOf(
                                                'https://maxstream.video/emiuhi/'
                                            ) === 0
                                        ) {
                                            return fallbackHref;
                                        }
                                    }
                        
                                    return "";
                                })();
                                """.trimIndent()
                            ) { result ->
                        
                                val maxstreamUrl =
                                    result
                                        ?.trim()
                                        ?.removePrefix("\"")
                                        ?.removeSuffix("\"")
                                        ?.replace("\\/", "/")
                                        ?.takeIf {
                                            it.startsWith(
                                                "https://maxstream.video/",
                                                ignoreCase = true
                                            )
                                        }
                        
                                if (!maxstreamUrl.isNullOrBlank()) {
                        
                                    Log.d(
                                        TAG,
                                        "MAXSTREAM Continue reale trovato: $maxstreamUrl"
                                    )
                        
                                    finish(maxstreamUrl)
                        
                                } else {
                        
                                    view?.postDelayed(
                                        {
                                            searchMaxstream(attempt + 1)
                                        },
                                        500L
                                    )
                                }
                            }
                        }
                        
                        searchMaxstream()
                    }
                }

            // Siamo FUORI da WebViewClient
            Log.d(TAG, "Apro WebView: $url")

            val existingCookies =
                CookieManager.getInstance()
                    .getCookie("https://uprot.net")

            Log.d(
                TAG,
                "Cookie Uprot presenti = ${
                    !existingCookies.isNullOrBlank()
                }"
            )

            dialog.show()

            webView.loadUrl(
                url,
                mapOf(
                    "Referer" to "https://cb01uno.blog/"
                )
            )
        }
    }
}
