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

            /*
             * Manteniamo esplicitamente i cookie persistenti.
             */
            try {
                cookieManager.flush()
            } catch (_: Exception) {
            }

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
                } catch (_: Exception) {
                }
            
                try {
            
                    if (dialog.isShowing) {
                        dialog.dismiss()
                    }
            
                } catch (_: Exception) {
                }
            
                try {
                    webView.destroy()
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
                    
                        val uri = try {
                            android.net.Uri.parse(target)
                        } catch (_: Exception) {
                            return true
                        }
                    
                        val host = uri.host?.lowercase().orEmpty()
                    
                        // Link finale valido
                        if (
                            host == "maxstream.video" &&
                            (
                                target.contains("/uprots/", ignoreCase = true) ||
                                target.contains("/uprotem/", ignoreCase = true) ||
                                target.contains("/emiuhi/", ignoreCase = true)
                            )
                        ) {
                            Log.d(TAG, "MAXSTREAM intercettato: $target")
                        
                            finish(target)
                            return true
                        }
                    
                        // Uprot deve poter navigare normalmente
                        if (
                            host == "uprot.net" ||
                            host.endsWith(".uprot.net")
                        ) {
                            return false
                        }
                    
                        // Tutto il resto viene bloccato
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
                                try {
                                    CookieManager
                                        .getInstance()
                                        .flush()
                                } catch (_: Exception) {
                                }

                        Log.d(
                            TAG,
                            "Cookie Uprot dopo pagina presenti = ${
                                !cookies.isNullOrBlank()
                            }"
                        )

                        fun searchMaxstream(attempt: Int = 0) {
                        
                            if (completed || attempt >= 20) {
                                return
                            }
                        
                            view?.evaluateJavascript(
                                """
                                (function() {
                                    var links = document.querySelectorAll('a[href]');
                        
                                    for (var i = 0; i < links.length; i++) {
                                        var href = links[i].href || "";
                        
                                        if (
                                            href.indexOf('https://maxstream.video/uprots/') === 0 ||
                                            href.indexOf('https://maxstream.video/uprotem/') === 0 ||
                                            href.indexOf('https://maxstream.video/emiuhi/') === 0
                                        ) {
                                            return href;
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
                                        "MAXSTREAM trovato automaticamente: $maxstreamUrl"
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

/*
 * FAST PATH
 *
 * Carichiamo Uprot senza mostrare subito il dialog.
 *
 * Se la sessione/cookie già presenti sono ancora validi,
 * Uprot potrebbe portarci direttamente a MaxStream.
 *
 * In quel caso finish() verrà chiamato dal WebViewClient
 * e l'utente non vedrà nessun CAPTCHA/dialog.
 */
webView.loadUrl(
    url,
    mapOf(
        "Referer" to "https://onlineserietv.mom/"
    )
)

/*
 * Aspettiamo un po' prima di mostrare la UI.
 *
 * Se entro questo tempo non abbiamo trovato MaxStream,
 * molto probabilmente Uprot sta aspettando l'interazione
 * dell'utente/CAPTCHA.
 */
webView.postDelayed(
    {

        if (completed) {
            Log.d(
                TAG,
                "FAST PATH riuscito: MaxStream trovato senza mostrare il dialog"
            )
            return@postDelayed
        }

        val currentUrl =
            try {
                webView.url
            } catch (_: Exception) {
                null
            }

        Log.d(
            TAG,
            "FAST PATH terminato. URL corrente = $currentUrl"
        )

        webView.evaluateJavascript(
            """
            (function() {

                try {

                    var captcha =
                        document.querySelector(
                            '#upcaptcha-form, .upcaptcha-box, [class*="captcha"], [id*="captcha"]'
                        );

                    return captcha ? "CAPTCHA" : "NO_CAPTCHA";

                } catch(e) {
                    return "ERROR";
                }

            })();
            """.trimIndent()
        ) { result ->

            val state =
                result
                    ?.trim()
                    ?.removePrefix("\"")
                    ?.removeSuffix("\"")

            Log.d(
                TAG,
                "FAST PATH stato pagina = $state"
            )

            if (completed) {
                return@evaluateJavascript
            }

            if (state == "CAPTCHA") {

                try {

                    if (!dialog.isShowing) {

                        Log.d(
                            TAG,
                            "CAPTCHA presente: mostro dialog Uprot"
                        )

                        dialog.show()
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Errore apertura dialog Uprot: ${e.message}",
                        e
                    )

                    finish(null)
                }

            } else {

                Log.d(
                    TAG,
                    "Nessun CAPTCHA rilevato: continuo fast path"
                )
            }
        }

    },
    2500L
)
        }
    }
}
