package com.cb

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

class MaxStream : ExtractorApi() {

    override val name = "MaxStream"

    override val mainUrl = "https://maxstream.video"

    override val requiresReferer = true

    companion object {

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/139.0.0.0 Mobile Safari/537.36"
    }

    /**
     * Metodo preso dall'idea del nuovo extractor Streamflix.
     *
     * Cerca strutture tipo:
     *
     * sources: [{ src: "https://....m3u8" }]
     *
     * oppure:
     *
     * sources: [{ "src": "https://....m3u8" }]
     */
    private fun extractStreamflixSource(
        html: String
    ): String? {

        val patterns =
            listOf(
                Regex(
                    """sources\s*:\s*\[\s*\{\s*[sS]rc\s*:\s*["']([^"']+)["']""",
                    RegexOption.IGNORE_CASE
                ),

                Regex(
                    """sources\s*:\s*\[\s*\{\s*["']src["']\s*:\s*["']([^"']+)["']""",
                    RegexOption.IGNORE_CASE
                ),

                Regex(
                    """sources\s*=\s*\[\s*\{\s*[sS]rc\s*:\s*["']([^"']+)["']""",
                    RegexOption.IGNORE_CASE
                )
            )

        patterns.forEach { regex ->

            val source =
                regex
                    .find(html)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.replace("\\/", "/")
                    ?.replace("\\u0026", "&")
                    ?.replace("&amp;", "&")
                    ?.trim()

            if (!source.isNullOrBlank()) {

                Log.e(
                    "MAXSTREAM_DEBUG",
                    "SOURCE STREAMFLIX REGEX = $source"
                )

                return source
            }
        }

        return null
    }

    /**
     * Invia a CloudStream il link trovato.
     *
     * Restituisce true se il link è stato gestito.
     */

     private fun extractHexIframe(html: String): String? {

        val rawHex =
            Regex(
                """var\s+rawHex\s*=\s*["']([0-9a-fA-F]+)["']"""
            )
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?: return null
    
        return try {
    
            val decoded =
                rawHex
                    .chunked(2)
                    .map {
                        it.toInt(16).toChar()
                    }
                    .joinToString("")
    
            val finalUrl =
                decoded.reversed()
    
            Log.e(
                "MAXSTREAM_DEBUG",
                "HEX IFRAME DECODED = $finalUrl"
            )
    
            finalUrl
    
        } catch (
            e: Exception
        ) {
    
            Log.e(
                "MAXSTREAM_DEBUG",
                "Errore decode HEX: ${e.message}"
            )
    
            null
        }
    }
     
    private suspend fun sendStream(
        streamUrl: String?,
        playerReferer: String,
        baseHeaders: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        if (streamUrl.isNullOrBlank()) {
            return false
        }

        val cleanUrl =
            streamUrl
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .trim()

        if (cleanUrl.isBlank()) {
            return false
        }

        Log.e(
            "MAXSTREAM_DEBUG",
            "======================================"
        )

        Log.e(
            "MAXSTREAM_DEBUG",
            "STREAM TROVATO = $cleanUrl"
        )

        Log.e(
            "MAXSTREAM_DEBUG",
            "STREAM REFERER = $playerReferer"
        )

        val streamHeaders =
            baseHeaders
                .toMutableMap()
                .apply {
                    this["Referer"] =
                        playerReferer
                }

        val isM3u8 =
            cleanUrl.contains(
                ".m3u8",
                ignoreCase = true
            )

        if (isM3u8) {

            Log.e(
                "MAXSTREAM_DEBUG",
                "TIPO STREAM = M3U8"
            )

            try {

                val links =
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = cleanUrl,
                        referer = playerReferer,
                        headers = streamHeaders
                    )

                if (links.isNotEmpty()) {

                    Log.e(
                        "MAXSTREAM_DEBUG",
                        "M3U8Helper ha generato ${links.size} link"
                    )

                    links.forEach(callback)

                } else {

                    Log.e(
                        "MAXSTREAM_DEBUG",
                        "M3U8Helper vuoto, invio link M3U8 diretto"
                    )

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = cleanUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer =
                                playerReferer

                            this.headers =
                                streamHeaders

                            this.quality =
                                Qualities.Unknown.value
                        }
                    )
                }

            } catch (
                e: Exception
            ) {

                Log.e(
                    "MAXSTREAM_DEBUG",
                    "Errore M3U8Helper: ${e.message}"
                )

                /*
                 * Se M3U8Helper fallisce,
                 * proviamo comunque il link diretto.
                 */
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = cleanUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer =
                            playerReferer

                        this.headers =
                            streamHeaders

                        this.quality =
                            Qualities.Unknown.value
                    }
                )
            }

        } else {

            Log.e(
                "MAXSTREAM_DEBUG",
                "TIPO STREAM = VIDEO"
            )

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = cleanUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer =
                        playerReferer

                    this.headers =
                        streamHeaders

                    this.quality =
                        Qualities.Unknown.value
                }
            )
        }

        Log.e(
            "MAXSTREAM_DEBUG",
            "STREAM INVIATO A CLOUDSTREAM"
        )

        return true
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        Log.e(
            "MAXSTREAM_DEBUG",
            "======================================"
        )

        Log.e(
            "MAXSTREAM_DEBUG",
            "MAXSTREAM START"
        )

        Log.e(
            "MAXSTREAM_DEBUG",
            "URL = $url"
        )

        Log.e(
            "MAXSTREAM_DEBUG",
            "REFERER = $referer"
        )

        /*
         * Utilizziamo lo stesso User-Agent usato dalla
         * WebView Uprot, quando disponibile.
         */
        val sessionUserAgent =
            UprotSession.userAgent
                .takeIf {
                    it.isNotBlank()
                }
                ?: USER_AGENT

        /*
         * Recuperiamo anche i cookie generati durante
         * la navigazione Uprot / MaxStream.
         */
        val sessionCookies =
            UprotSession.cookieHeader

        val headers =
            mutableMapOf(
                "User-Agent" to
                    sessionUserAgent,

                "Referer" to
                    (
                        referer
                            ?: mainUrl
                        ),

                "Accept" to
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",

                "Accept-Language" to
                    "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7"
            )

        if (
            sessionCookies.isNotBlank()
        ) {

            headers["Cookie"] =
                sessionCookies

            Log.d(
                "MAXSTREAM_DEBUG",
                "Cookie WebView applicati a MaxStream"
            )
        }

        /*
         * =====================================================
         * PRIMO ACCESSO MAXSTREAM
         * =====================================================
         */

        val response =
            try {

                app.get(
                    url,
                    headers = headers
                )

            } catch (
                e: Exception
            ) {

                Log.e(
                    "MAXSTREAM_DEBUG",
                    "Errore GET MaxStream: ${e.message}"
                )

                return
            }

        var html =
            response.text

        var playerReferer =
            response.url

        Log.d(
            "MAXSTREAM_DEBUG",
            "STATUS = ${response.code}"
        )

        Log.d(
            "MAXSTREAM_DEBUG",
            "FINAL URL = ${response.url}"
        )

        Log.d(
            "MAXSTREAM_DEBUG",
            "HTML LENGTH = ${html.length}"
        )

        /*
         * =====================================================
         * NUOVO METODO STREAMFLIX
         * =====================================================
         *
         * Prima di fare qualsiasi altra cosa proviamo
         * direttamente a trovare:
         *
         * sources: [{ src: "..." }]
         */

        val firstSource =
            extractStreamflixSource(
                html
            )

        if (
            !firstSource.isNullOrBlank()
        ) {

            Log.e(
                "MAXSTREAM_DEBUG",
                ">>> STREAMFLIX SOURCE TROVATA NELLA PAGINA PRINCIPALE <<<"
            )

            val success =
                sendStream(
                    streamUrl = firstSource,
                    playerReferer = response.url,
                    baseHeaders = headers,
                    callback = callback
                )

            if (success) {
                return
            }
        }

        /*
         * =====================================================
         * CONTROLLO CLOUDFLARE
         * =====================================================
         */

        val initialDoc =
            Jsoup.parse(
                html
            )

        val cloudflareBlocked =
            response.code == 403 ||
                initialDoc
                    .title()
                    .contains(
                        "Just a moment",
                        ignoreCase = true
                    ) ||
                html.contains(
                    "/cdn-cgi/challenge-platform/",
                    ignoreCase = true
                )

        if (
            cloudflareBlocked
        ) {

            Log.e(
                "MAXSTREAM_DEBUG",
                "Browser challenge rilevata su ${response.url}"
            )

            val webViewResult =
                MaxStreamWebView.openForInspection(
                    url = response.url,
                    userAgent = sessionUserAgent,
                    referer = referer
                )

            Log.d(
                "MAXSTREAM_DEBUG",
                "WEBVIEW STATUS = ${webViewResult.status}"
            )

            Log.d(
                "MAXSTREAM_DEBUG",
                "WEBVIEW FINAL URL = ${webViewResult.finalUrl}"
            )

            Log.d(
                "MAXSTREAM_DEBUG",
                "PLAYER URL = ${webViewResult.playerUrl}"
            )

            Log.d(
                "MAXSTREAM_DEBUG",
                "PLAYER HOST = ${webViewResult.playerHost}"
            )

            Log.d(
                "MAXSTREAM_DEBUG",
                "PLAYER PAGE URL = ${webViewResult.playerPageUrl}"
            )

            Log.d(
                "MAXSTREAM_DEBUG",
                "PLAYER PAGE TITLE = ${webViewResult.playerPageTitle}"
            )

            Log.d(
                "MAXSTREAM_DEBUG",
                "DOM COUNTS iframe=${webViewResult.iframeCount} " +
                    "video=${webViewResult.videoCount} " +
                    "source=${webViewResult.sourceCount}"
            )

            /*
             * Se la WebView ci restituisce direttamente
             * l'URL del player, proviamo ad aprirlo
             * nuovamente usando i cookie aggiornati.
             */
            val possiblePlayerUrl =
                webViewResult.playerUrl
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: webViewResult.playerPageUrl
                        ?.takeIf {
                            it.isNotBlank()
                        }

            if (
                !possiblePlayerUrl.isNullOrBlank()
            ) {

                Log.e(
                    "MAXSTREAM_DEBUG",
                    "Provo player trovato dalla WebView: $possiblePlayerUrl"
                )

                val updatedCookies =
                    UprotSession.cookieHeader

                val webViewHeaders =
                    headers
                        .toMutableMap()
                        .apply {

                            this["Referer"] =
                                webViewResult.finalUrl
                                    ?.takeIf {
                                        it.isNotBlank()
                                    }
                                    ?: response.url

                            if (
                                updatedCookies.isNotBlank()
                            ) {
                                this["Cookie"] =
                                    updatedCookies
                            }
                        }

                try {

                    val playerResponse =
                        app.get(
                            possiblePlayerUrl,
                            headers = webViewHeaders
                        )

                    val playerHtml =
                        playerResponse.text

                    Log.e(
                        "MAXSTREAM_DEBUG",
                        "WEBVIEW PLAYER HTTP STATUS = ${playerResponse.code}"
                    )

                    Log.e(
                        "MAXSTREAM_DEBUG",
                        "WEBVIEW PLAYER HTML LENGTH = ${playerHtml.length}"
                    )

                    /*
                     * Qui applichiamo di nuovo
                     * il metodo Streamflix.
                     */
                    val webViewSource =
                        extractStreamflixSource(
                            playerHtml
                        )

                    if (
                        !webViewSource.isNullOrBlank()
                    ) {

                        Log.e(
                            "MAXSTREAM_DEBUG",
                            ">>> STREAMFLIX SOURCE TROVATA DOPO WEBVIEW <<<"
                        )

                        val success =
                            sendStream(
                                streamUrl = webViewSource,
                                playerReferer = playerResponse.url,
                                baseHeaders = webViewHeaders,
                                callback = callback
                            )

                        if (success) {
                            return
                        }
                    }

                    /*
                     * Se non troviamo sources,
                     * continuiamo usando questo HTML
                     * come pagina player.
                     */
                    if (
                        playerResponse.code in 200..399 &&
                        playerHtml.isNotBlank()
                    ) {

                        html =
                            playerHtml

                        playerReferer =
                            playerResponse.url

                        /*
                         * Aggiorniamo anche gli headers
                         * che verranno usati dopo.
                         */
                        headers["Referer"] =
                            playerReferer

                        if (
                            updatedCookies.isNotBlank()
                        ) {
                            headers["Cookie"] =
                                updatedCookies
                        }

                    } else {

                        Log.e(
                            "MAXSTREAM_DEBUG",
                            "Impossibile utilizzare HTML player WebView"
                        )
                    }

                } catch (
                    e: Exception
                ) {

                    Log.e(
                        "MAXSTREAM_DEBUG",
                        "Errore apertura player WebView: ${e.message}"
                    )
                }
            }

            /*
             * A differenza del vecchio extractor,
             * NON facciamo subito return su PLAYER_FOUND
             * o PLAYER_PAGE_READY.
             *
             * Continuiamo e proviamo ad estrarre il player.
             */

            when (
                webViewResult.status
            ) {

                MaxStreamWebViewStatus.CANCELLED -> {

                    Log.e(
                        "MAXSTREAM_DEBUG",
                        "WebView MaxStream chiusa dall'utente"
                    )

                    return
                }

                MaxStreamWebViewStatus.TIMEOUT -> {

                    Log.e(
                        "MAXSTREAM_DEBUG",
                        "Timeout WebView MaxStream"
                    )

                    /*
                     * Non ritorniamo immediatamente se abbiamo
                     * già ottenuto dell'HTML valido.
                     */
                    if (
                        html.isBlank()
                    ) {
                        return
                    }
                }

                MaxStreamWebViewStatus.PLAYER_FOUND -> {

                    Log.e(
                        "MAXSTREAM_DEBUG",
                        "Player MaxStream rilevato dalla WebView"
                    )
                }

                MaxStreamWebViewStatus.PLAYER_PAGE_READY -> {

                    Log.e(
                        "MAXSTREAM_DEBUG",
                        "Pagina player MaxStream caricata dalla WebView"
                    )
                }
            }
        }

        /*
         * =====================================================
         * CAPTCHA
         * =====================================================
         */

        val maxDoc =
            Jsoup.parse(
                html
            )

        val isCaptcha =
            maxDoc.selectFirst(
                "#upcaptcha-form"
            ) != null ||
                maxDoc.selectFirst(
                    ".upcaptcha-box"
                ) != null

        if (
            isCaptcha
        ) {

            Log.e(
                "MAXSTREAM_DEBUG",
                "MaxStream richiede ancora UPCaptcha"
            )

            return
        }

        /*
         * =====================================================
         * METODO CLASSICO MAXSTREAM
         * decodedBaseUrl + decodedFileCode
         * =====================================================
         */
        /*
 * =====================================================
 * NUOVO MAXSTREAM 2026
 * rawHex -> HEX decode -> reverse
 * =====================================================
 */

val hexIframeUrl =
    extractHexIframe(html)

if (!hexIframeUrl.isNullOrBlank()) {

    Log.e(
        "MAXSTREAM_DEBUG",
        ">>> NUOVO IFRAME HEX TROVATO <<<"
    )

    Log.e(
        "MAXSTREAM_DEBUG",
        "IFRAME HEX URL = $hexIframeUrl"
    )

    val iframeHeaders =
        headers
            .toMutableMap()
            .apply {
                this["Referer"] =
                    playerReferer
            }

    try {

        val iframeResponse =
            app.get(
                hexIframeUrl,
                headers = iframeHeaders
            )

        val iframeHtml =
            iframeResponse.text

        Log.e(
            "MAXSTREAM_DEBUG",
            "HEX IFRAME STATUS = ${iframeResponse.code}"
        )

        Log.e(
            "MAXSTREAM_DEBUG",
            "HEX IFRAME FINAL URL = ${iframeResponse.url}"
        )

        Log.e(
            "MAXSTREAM_DEBUG",
            "HEX IFRAME HTML LENGTH = ${iframeHtml.length}"
        )

        /*
         * Prova prima il metodo Streamflix.
         */
        val streamflixSource =
            extractStreamflixSource(
                iframeHtml
            )

        if (!streamflixSource.isNullOrBlank()) {

            Log.e(
                "MAXSTREAM_DEBUG",
                ">>> STREAMFLIX SOURCE TROVATA NEL NUOVO IFRAME <<<"
            )

            val success =
                sendStream(
                    streamUrl = streamflixSource,
                    playerReferer = iframeResponse.url,
                    baseHeaders = iframeHeaders,
                    callback = callback
                )

            if (success) {
                return
            }
        }

        /*
         * Se sources/src non è presente,
         * analizziamo comunque l'HTML dell'iframe
         * con i fallback successivi.
         */
        html =
            iframeHtml

        playerReferer =
            iframeResponse.url

    } catch (
        e: Exception
    ) {

        Log.e(
            "MAXSTREAM_DEBUG",
            "Errore apertura nuovo iframe HEX: ${e.message}"
        )
    }
 }
        /*
         * =====================================================
         * NUOVO MAXSTREAM 2026
         * rawHex -> HEX decode -> reverse
         * =====================================================
         */
        
        val hexIframeUrl =
            extractHexIframe(html)
        
        if (!hexIframeUrl.isNullOrBlank()) {
        
            Log.e(
                "MAXSTREAM_DEBUG",
                ">>> NUOVO IFRAME HEX TROVATO <<<"
            )
        
            Log.e(
                "MAXSTREAM_DEBUG",
                "IFRAME HEX URL = $hexIframeUrl"
            )
        
            val iframeHeaders =
                headers
                    .toMutableMap()
                    .apply {
                        this["Referer"] =
                            playerReferer
                    }
        
            try {
        
                val iframeResponse =
                    app.get(
                        hexIframeUrl,
                        headers = iframeHeaders
                    )
        
                val iframeHtml =
                    iframeResponse.text
        
                Log.e(
                    "MAXSTREAM_DEBUG",
                    "HEX IFRAME STATUS = ${iframeResponse.code}"
                )
        
                Log.e(
                    "MAXSTREAM_DEBUG",
                    "HEX IFRAME FINAL URL = ${iframeResponse.url}"
                )
        
                Log.e(
                    "MAXSTREAM_DEBUG",
                    "HEX IFRAME HTML LENGTH = ${iframeHtml.length}"
                )
        
                /*
                 * Prova prima il metodo Streamflix.
                 */
                val streamflixSource =
                    extractStreamflixSource(
                        iframeHtml
                    )
        
                if (!streamflixSource.isNullOrBlank()) {
        
                    Log.e(
                        "MAXSTREAM_DEBUG",
                        ">>> STREAMFLIX SOURCE TROVATA NEL NUOVO IFRAME <<<"
                    )
        
                    val success =
                        sendStream(
                            streamUrl = streamflixSource,
                            playerReferer = iframeResponse.url,
                            baseHeaders = iframeHeaders,
                            callback = callback
                        )
        
                    if (success) {
                        return
                    }
                }
        
                /*
                 * Se sources/src non è presente,
                 * analizziamo comunque l'HTML dell'iframe
                 * con i fallback successivi.
                 */
                html =
                    iframeHtml
        
                playerReferer =
                    iframeResponse.url
        
            } catch (
                e: Exception
            ) {
        
                Log.e(
                    "MAXSTREAM_DEBUG",
                    "Errore apertura nuovo iframe HEX: ${e.message}"
                )
            }
        }
        val iframeBase64 =
            Regex(
                """decodedBaseUrl\s*=\s*atob\(\s*["']([^"']+)["']\s*\)"""
            )
                .find(
                    html
                )
                ?.groupValues
                ?.getOrNull(
                    1
                )

        val iframeCodeBase64 =
            Regex(
                """decodedFileCode\s*=\s*atob\(\s*["']([^"']+)["']\s*\)"""
            )
                .find(
                    html
                )
                ?.groupValues
                ?.getOrNull(
                    1
                )

        if (
            !iframeBase64.isNullOrBlank() &&
            !iframeCodeBase64.isNullOrBlank()
        ) {

            try {

                val decodedBase =
                    String(
                        Base64.decode(
                            iframeBase64,
                            Base64.DEFAULT
                        ),
                        Charsets.UTF_8
                    )

                val decodedCode =
                    String(
                        Base64.decode(
                            iframeCodeBase64,
                            Base64.DEFAULT
                        ),
                        Charsets.UTF_8
                    )

                val iframeUrl =
                    decodedBase +
                        decodedCode

                Log.e(
                    "MAXSTREAM_DEBUG",
                    "IFRAME MAXSTREAM DECODED = $iframeUrl"
                )

                val iframeHeaders =
                    headers
                        .toMutableMap()
                        .apply {

                            this["Referer"] =
                                playerReferer
                        }

                val iframeResponse =
                    app.get(
                        iframeUrl,
                        headers = iframeHeaders
                    )

                val iframeHtml =
                    iframeResponse.text

                Log.e(
                    "MAXSTREAM_DEBUG",
                    "IFRAME STATUS = ${iframeResponse.code}"
                )

                Log.e(
                    "MAXSTREAM_DEBUG",
                    "IFRAME FINAL URL = ${iframeResponse.url}"
                )

                Log.e(
                    "MAXSTREAM_DEBUG",
                    "IFRAME HTML LENGTH = ${iframeHtml.length}"
                )

                /*
                 * =================================================
                 * STREAMFLIX METHOD SULL'IFRAME
                 * =================================================
                 */

                val iframeSource =
                    extractStreamflixSource(
                        iframeHtml
                    )

                if (
                    !iframeSource.isNullOrBlank()
                ) {

                    Log.e(
                        "MAXSTREAM_DEBUG",
                        ">>> STREAMFLIX SOURCE TROVATA NELL'IFRAME <<<"
                    )

                    val success =
                        sendStream(
                            streamUrl = iframeSource,
                            playerReferer = iframeResponse.url,
                            baseHeaders = iframeHeaders,
                            callback = callback
                        )

                    if (success) {
                        return
                    }
                }

                /*
                 * Se Streamflix regex non trova niente,
                 * continuiamo con il vecchio sistema.
                 */

                html =
                    iframeHtml

                playerReferer =
                    iframeResponse.url

            } catch (
                e: Exception
            ) {

                Log.e(
                    "MAXSTREAM_DEBUG",
                    "Errore caricamento iframe MaxStream: ${e.message}"
                )
            }

        } else {

            Log.e(
                "MAXSTREAM_DEBUG",
                "decodedBaseUrl/decodedFileCode non trovati"
            )
        }

        /*
         * =====================================================
         * ANALISI PLAYER
         * =====================================================
         */

        Log.e(
            "MAXSTREAM_DEBUG",
            "=============================="
        )

        Log.e(
            "MAXSTREAM_DEBUG",
            "PLAYER REFERER = $playerReferer"
        )

        /*
         * Ultimo tentativo Streamflix dopo tutte
         * le eventuali trasformazioni della pagina.
         */
        val finalStreamflixSource =
            extractStreamflixSource(
                html
            )

        if (
            !finalStreamflixSource.isNullOrBlank()
        ) {

            Log.e(
                "MAXSTREAM_DEBUG",
                ">>> STREAMFLIX SOURCE TROVATA NEL PLAYER FINALE <<<"
            )

            val success =
                sendStream(
                    streamUrl = finalStreamflixSource,
                    playerReferer = playerReferer,
                    baseHeaders = headers,
                    callback = callback
                )

            if (success) {
                return
            }
        }

        val document =
            Jsoup.parse(
                html
            )

        Log.e(
            "MAXSTREAM_DEBUG",
            "PLAYER TITLE = ${document.title()}"
        )

        /*
         * Log degli script, utile per capire
         * eventuali cambiamenti futuri di MaxStream.
         */
        document
            .select(
                "script"
            )
            .forEachIndexed { index, script ->

                val src =
                    script.attr(
                        "src"
                    )

                if (
                    src.isNotBlank()
                ) {

                    Log.e(
                        "MAXSTREAM_DEBUG",
                        "PLAYER SCRIPT SRC [$index] = $src"
                    )
                }

                val content =
                    script.data()
                        .ifBlank {
                            script.html()
                        }

                if (
                    content.isNotBlank()
                ) {

                    Log.e(
                        "MAXSTREAM_DEBUG",
                        "PLAYER SCRIPT [$index] = ${
                            content
                                .replace(
                                    "\n",
                                    " "
                                )
                                .take(
                                    5000
                                )
                        }"
                    )
                }
            }

        /*
         * =====================================================
         * FALLBACK VECCHIO
         * =====================================================
         *
         * Cerca qualsiasi URL .m3u8 oppure .mp4
         * nell'HTML.
         */

        val streamUrlRegex =
            """https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*"""
                .toRegex(
                    RegexOption.IGNORE_CASE
                )

        val matches =
            streamUrlRegex
                .findAll(
                    html
                )
                .map {
                    it.value
                        .replace(
                            "\\/",
                            "/"
                        )
                        .replace(
                            "\\u0026",
                            "&"
                        )
                        .replace(
                            "&amp;",
                            "&"
                        )
                }
                .distinct()
                .toList()

        Log.e(
            "MAXSTREAM_DEBUG",
            "Stream diretti trovati = ${matches.size}"
        )

        for (
            streamUrl in matches
        ) {

            Log.e(
                "MAXSTREAM_DEBUG",
                "FALLBACK STREAM = $streamUrl"
            )

            val success =
                sendStream(
                    streamUrl = streamUrl,
                    playerReferer = playerReferer,
                    baseHeaders = headers,
                    callback = callback
                )

            if (success) {

                /*
                 * Non facciamo necessariamente return,
                 * perché potrebbero esserci più qualità.
                 */
                Log.e(
                    "MAXSTREAM_DEBUG",
                    "Fallback stream aggiunto"
                )
            }
        }

        Log.e(
            "MAXSTREAM_DEBUG",
            "MAXSTREAM EXTRACTION FINISHED"
        )
    }
}
