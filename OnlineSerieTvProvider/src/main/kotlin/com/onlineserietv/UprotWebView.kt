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
import android.webkit.CookieManager
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

    /*
     * WebView persistente.
     *
     * Questa è la parte importante:
     * la stessa WebView viene riutilizzata tra episodi diversi,
     * proprio come quando cambi URL nella stessa scheda del browser.
     */
    private var sharedWebView: WebView? = null

    /*
     * Evita due resolve contemporanee sulla stessa WebView.
     */
    private var inUse = false

    fun setContext(context: Context) {

        val activity =
            context.findActivity()

        if (activity != null) {

            activityRef =
                WeakReference(activity)

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

    suspend fun resolve(
        url: String,
        userAgent: String
    ): String? =
        suspendCancellableCoroutine { continuation ->

            Log.e(
                TAG,
                ">>> UprotWebView.resolve CHIAMATA <<<"
            )

            Log.e(
                TAG,
                "URL = $url"
            )

            val activity =
                activityRef?.get()

            if (activity == null) {

                Log.e(
                    TAG,
                    "Activity CloudStream non disponibile"
                )

                continuation.resume(
                    null
                )

                return@suspendCancellableCoroutine
            }

            activity.runOnUiThread {

                if (inUse) {

                    Log.e(
                        TAG,
                        "UprotWebView già in uso"
                    )

                    if (continuation.isActive) {
                        continuation.resume(
                            null
                        )
                    }

                    return@runOnUiThread
                }

                inUse = true

                var completed =
                    false

                val dialog =
                    Dialog(activity)

                val container =
                    FrameLayout(activity)

                val progressBar =
                    ProgressBar(
                        activity,
                        null,
                        android.R.attr.progressBarStyleHorizontal
                    )

                progressBar.max =
                    100

                /*
                 * Riutilizziamo la stessa WebView.
                 */
                val webView =
                    sharedWebView
                        ?: WebView(activity).also {

                            sharedWebView =
                                it

                            Log.d(
                                TAG,
                                "Creata nuova WebView persistente"
                            )
                        }

                /*
                 * Se la WebView era ancora collegata
                 * a un vecchio parent, la stacchiamo.
                 */
                try {

                    (webView.parent as? ViewGroup)
                        ?.removeView(
                            webView
                        )

                } catch (_: Exception) {
                }

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

                dialog.window?.apply {

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

                    dialog.window?.setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }

                /*
                 * NON distruggiamo più la WebView.
                 */
                fun finish(
                    result: String?
                ) {

                    if (completed) {
                        return
                    }

                    completed =
                        true

                    inUse =
                        false

                    Log.d(
                        TAG,
                        "Risultato WebView = $result"
                    )

                    /*
                     * Fermiamo solo il caricamento corrente.
                     *
                     * Importante:
                     * non usiamo destroy()
                     * e non carichiamo about:blank,
                     * così sessionStorage/stato della tab
                     * resta disponibile.
                     */
                    try {
                        webView.stopLoading()
                    } catch (_: Exception) {
                    }

                    /*
                     * Salviamo i cookie su disco.
                     */
                    try {
                        CookieManager
                            .getInstance()
                            .flush()
                    } catch (_: Exception) {
                    }

                    /*
                     * Stacchiamo la WebView dal dialog,
                     * ma la teniamo viva.
                     */
                    try {

                        (webView.parent as? ViewGroup)
                            ?.removeView(
                                webView
                            )

                    } catch (_: Exception) {
                    }

                    try {

                        if (dialog.isShowing) {
                            dialog.dismiss()
                        }

                    } catch (_: Exception) {
                    }

                    if (
                        continuation.isActive
                    ) {

                        continuation.resume(
                            result
                        )
                    }
                }

                dialog.setOnCancelListener {

                    Log.d(
                        TAG,
                        "Dialog Uprot annullato"
                    )

                    finish(
                        null
                    )
                }

                continuation
                    .invokeOnCancellation {

                        activity.runOnUiThread {

                            Log.d(
                                TAG,
                                "Coroutine Uprot cancellata"
                            )

                            /*
                             * Non distruggiamo la WebView nemmeno qui.
                             */
                            try {

                                (webView.parent as? ViewGroup)
                                    ?.removeView(
                                        webView
                                    )

                            } catch (_: Exception) {
                            }

                            try {

                                if (dialog.isShowing) {
                                    dialog.dismiss()
                                }

                            } catch (_: Exception) {
                            }

                            inUse =
                                false
                        }
                    }

                /*
                 * Cookie.
                 */
                val cookieManager =
                    CookieManager
                        .getInstance()

                cookieManager
                    .setAcceptCookie(
                        true
                    )

                cookieManager
                    .setAcceptThirdPartyCookies(
                        webView,
                        true
                    )

                /*
                 * Configuriamo sempre la WebView.
                 *
                 * Se è già esistente,
                 * le impostazioni vengono semplicemente
                 * confermate.
                 */
                webView.settings.apply {

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
                        WebSettings
                            .MIXED_CONTENT_COMPATIBILITY_MODE

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
                    object :
                        WebChromeClient() {

                        override fun onProgressChanged(
                            view: WebView?,
                            newProgress: Int
                        ) {

                            progressBar.progress =
                                newProgress

                            progressBar.visibility =
                                if (
                                    newProgress >= 100
                                ) {
                                    android.view.View.GONE
                                } else {
                                    android.view.View.VISIBLE
                                }
                        }

                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean {

                            Log.d(
                                TAG,
                                "Popup bloccato"
                            )

                            return false
                        }
                    }

                webView.webViewClient =
                    object :
                        WebViewClient() {

                        private fun checkUrl(
                            targetUrl: String?
                        ): Boolean {

                            val target =
                                targetUrl
                                    ?: return false

                            Log.d(
                                TAG,
                                "NAV = $target"
                            )

                            val uri =
                                try {

                                    android.net.Uri
                                        .parse(
                                            target
                                        )

                                } catch (_: Exception) {

                                    return true
                                }

                            val host =
                                uri.host
                                    ?.lowercase()
                                    .orEmpty()

                            /*
                             * MaxStream finale.
                             */
                            if (
                                host == "maxstream.video" &&
                                (
                                    target.contains(
                                        "/uprots/",
                                        ignoreCase = true
                                    ) ||
                                    target.contains(
                                        "/uprotem/",
                                        ignoreCase = true
                                    ) ||
                                    target.contains(
                                        "/emiuhi/",
                                        ignoreCase = true
                                    )
                                )
                            ) {

                                Log.d(
                                    TAG,
                                    "MAXSTREAM intercettato: $target"
                                )

                                finish(
                                    target
                                )

                                return true
                            }

                            /*
                             * Uprot può navigare liberamente.
                             */
                            if (
                                host == "uprot.net" ||
                                host.endsWith(
                                    ".uprot.net"
                                )
                            ) {

                                return false
                            }

                            /*
                             * Blocchiamo il resto.
                             */
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

                            return checkUrl(
                                url
                            )
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

                            checkUrl(
                                url
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

                            /*
                             * Manteniamo cookie persistenti.
                             */
                            try {

                                CookieManager
                                    .getInstance()
                                    .flush()

                            } catch (_: Exception) {
                            }

                            val cookies =
                                CookieManager
                                    .getInstance()
                                    .getCookie(
                                        "https://uprot.net"
                                    )

                            Log.d(
                                TAG,
                                "Cookie Uprot presenti = ${
                                    !cookies.isNullOrBlank()
                                }"
                            )

                            /*
                             * Cerchiamo MaxStream automaticamente.
                             */
                            fun searchMaxstream(
                                attempt: Int = 0
                            ) {

                                if (
                                    completed ||
                                    attempt >= 30
                                ) {
                                    return
                                }

                                view?.evaluateJavascript(
                                    """
                                    (function() {

                                        var links =
                                            document.querySelectorAll(
                                                'a[href]'
                                            );

                                        for (
                                            var i = 0;
                                            i < links.length;
                                            i++
                                        ) {

                                            var href =
                                                links[i].href || "";

                                            if (
                                                href.indexOf(
                                                    'https://maxstream.video/uprots/'
                                                ) === 0 ||

                                                href.indexOf(
                                                    'https://maxstream.video/uprotem/'
                                                ) === 0 ||

                                                href.indexOf(
                                                    'https://maxstream.video/emiuhi/'
                                                ) === 0
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
                                            ?.removePrefix(
                                                "\""
                                            )
                                            ?.removeSuffix(
                                                "\""
                                            )
                                            ?.replace(
                                                "\\/",
                                                "/"
                                            )
                                            ?.takeIf {

                                                it.startsWith(
                                                    "https://maxstream.video/",
                                                    ignoreCase = true
                                                )
                                            }

                                    if (
                                        !maxstreamUrl.isNullOrBlank()
                                    ) {

                                        Log.d(
                                            TAG,
                                            "MAXSTREAM trovato automaticamente: $maxstreamUrl"
                                        )

                                        finish(
                                            maxstreamUrl
                                        )

                                    } else {

                                        view?.postDelayed(
                                            {
                                                searchMaxstream(
                                                    attempt + 1
                                                )
                                            },
                                            500L
                                        )
                                    }
                                }
                            }

                            searchMaxstream()
                        }
                    }

                /*
                 * Stato prima del cambio episodio.
                 */
                val existingCookies =
                    CookieManager
                        .getInstance()
                        .getCookie(
                            "https://uprot.net"
                        )

                Log.d(
                    TAG,
                    "Cookie Uprot prima del caricamento = ${
                        !existingCookies.isNullOrBlank()
                    }"
                )

                Log.d(
                    TAG,
                    "Riutilizzo WebView persistente = ${
                        sharedWebView === webView
                    }"
                )

                val hasExistingSession =
                        !existingCookies.isNullOrBlank()
                    
                    /*
                     * PRIMO ACCESSO:
                     *
                     * Se non abbiamo ancora cookie Uprot,
                     * sappiamo già che molto probabilmente
                     * servirà il CAPTCHA.
                     *
                     * Mostriamo quindi subito la WebView,
                     * senza aspettare il fast path invisibile.
                     */
                    if (!hasExistingSession) {
                    
                        Log.d(
                            TAG,
                            "Prima sessione Uprot: mostro subito il dialog"
                        )
                    
                        try {
                    
                            if (!dialog.isShowing) {
                                dialog.show()
                            }
                    
                        } catch (e: Exception) {
                    
                            Log.e(
                                TAG,
                                "Errore apertura dialog iniziale: ${e.message}",
                                e
                            )
                    
                            finish(null)
                    
                            return@runOnUiThread
                        }
                    }

                /*
                 * Mostriamo il dialog.
                 *
                 * Per il primo episodio serve per il CAPTCHA.
                 *
                 * Se il secondo episodio eredita correttamente
                 * la sessione della stessa WebView, dovrebbe
                 * apparire direttamente CONTINUE.
                 */
                Log.d(
    TAG,
    "Carico nuova pagina nella WebView persistente: $url"
)

webView.loadUrl(
    url,
    mapOf(
        "Referer" to
            "https://onlineserietv.mom/"
    )
)

/*
 * FAST PATH INVISIBILE
 *
 * Se la sessione della WebView persistente è già valida,
 * lasciamo che Uprot arrivi a MaxStream senza mostrare il dialog.
 */
if (hasExistingSession) {

    webView.postDelayed(
        {

            if (completed) {

                Log.d(
                    TAG,
                    "FAST PATH riuscito: MaxStream trovato senza mostrare Uprot"
                )

                return@postDelayed
            }

            webView.evaluateJavascript(
                """
                (function() {

                    try {

                        var captcha =
                            document.querySelector(
                                '#upcaptcha-form, .upcaptcha-box, #upcaptcha-wrapper'
                            );

                        var maxLinks =
                            document.querySelectorAll(
                                'a[href*="maxstream.video"]'
                            );

                        if (
                            maxLinks &&
                            maxLinks.length > 0
                        ) {
                            return "MAXSTREAM";
                        }

                        if (captcha) {
                            return "CAPTCHA";
                        }

                        return "WAIT";

                    } catch(e) {

                        return "ERROR";
                    }

                })();
                """.trimIndent()
            ) { result ->

                if (completed) {
                    return@evaluateJavascript
                }

                val state =
                    result
                        ?.trim()
                        ?.removePrefix("\"")
                        ?.removeSuffix("\"")

                Log.d(
                    TAG,
                    "FAST PATH stato = $state"
                )

                when (state) {

                    "CAPTCHA" -> {

                        Log.d(
                            TAG,
                            "Sessione non sufficiente: mostro CAPTCHA"
                        )

                        try {

                            if (!dialog.isShowing) {
                                dialog.show()
                            }

                        } catch (e: Exception) {

                            Log.e(
                                TAG,
                                "Errore apertura dialog: ${e.message}",
                                e
                            )

                            finish(null)
                        }
                    }

                    "MAXSTREAM" -> {

                        Log.d(
                            TAG,
                            "MaxStream già disponibile"
                        )
                    }

                    else -> {

                        /*
                         * Aspettiamo un altro secondo.
                         */
                        webView.postDelayed(
                            {

                                if (completed) {
                                    return@postDelayed
                                }

                                webView.evaluateJavascript(
                                    """
                                    (function() {

                                        var captcha =
                                            document.querySelector(
                                                '#upcaptcha-form, .upcaptcha-box, #upcaptcha-wrapper'
                                            );

                                        return captcha
                                            ? "CAPTCHA"
                                            : "NO_CAPTCHA";

                                    })();
                                    """.trimIndent()
                                ) { secondResult ->

                                    if (completed) {
                                        return@evaluateJavascript
                                    }

                                    val secondState =
                                        secondResult
                                            ?.trim()
                                            ?.removePrefix("\"")
                                            ?.removeSuffix("\"")

                                    Log.d(
                                        TAG,
                                        "FAST PATH secondo controllo = $secondState"
                                    )

                                    if (
                                        secondState == "CAPTCHA"
                                    ) {

                                        try {

                                            if (!dialog.isShowing) {

                                                Log.d(
                                                    TAG,
                                                    "CAPTCHA confermato: mostro dialog"
                                                )

                                                dialog.show()
                                            }

                                        } catch (e: Exception) {

                                            Log.e(
                                                TAG,
                                                "Errore apertura dialog: ${e.message}",
                                                e
                                            )

                                            finish(null)
                                        }
                                    }
                                }

                            },
                            1000L
                        )
                    }
                }
            }

        },
        2000L
    )
}
            }
        }

    /*
     * Funzione opzionale:
     * se in futuro vuoi resettare completamente
     * la sessione Uprot senza riavviare CloudStream.
     */
    fun reset() {

        val activity =
            activityRef
                ?.get()
                ?: return

        activity.runOnUiThread {

            try {

                sharedWebView
                    ?.stopLoading()

                sharedWebView
                    ?.destroy()

            } catch (_: Exception) {
            }

            sharedWebView =
                null

            inUse =
                false

            Log.d(
                TAG,
                "WebView Uprot persistente resettata"
            )
        }
    }
}
