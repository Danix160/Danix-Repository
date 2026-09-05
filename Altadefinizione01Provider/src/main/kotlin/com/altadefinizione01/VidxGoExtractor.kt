package com.altadefinizione01

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
import java.net.URI
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

class VidxGoExtractor : ExtractorApi() {

    override val name = "VidxGo"
    override val mainUrl = "https://v.vidxgo.co"
    override val requiresReferer = true

    companion object {

        private const val TAG =
            "VIDXGO_DEBUG"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        Log.d(TAG, "==============================")
        Log.d(TAG, "URL = $url")
        Log.d(TAG, "REFERER RICEVUTO = $referer")

        try {

            val pageReferer =
                referer
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: "https://altadefinizione-01.fun/"

            /*
             * =========================================================
             * SERIE TV
             * =========================================================
             *
             * Il Provider passa:
             *
             * https://v.vidxgo.co/t/IMDB/SEASON/EPISODE
             *
             * NON apriamo direttamente /t/ perché il vero player
             * richiede X-Ck / X-Cs.
             *
             * Dal JavaScript VidxGo sappiamo che le puntate vere
             * sono navigabili come:
             *
             * /IMDB/SEASON/EPISODE
             *
             * Da quella pagina estraiamo currentSrc.
             */

            if (url.contains("/t/")) {

                return handleTvSeries(
                    url = url,
                    pageReferer = pageReferer,
                    callback = callback
                )
            }

            /*
             * =========================================================
             * FILM
             * =========================================================
             */

            handleMovie(
                url = url,
                pageReferer = pageReferer,
                callback = callback
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "ERRORE VIDXGO: ${e.message}",
                e
            )
        }
    }

    // ============================================================
    // TV SERIES
    // ============================================================

    private suspend fun handleTvSeries(
        url: String,
        pageReferer: String,
        callback: (ExtractorLink) -> Unit
    ) {

        /*
         * Esempio ricevuto:
         *
         * https://v.vidxgo.co/t/14688458/1/1
         */

        val match =
            Regex(
                """/t/(\d+)/(\d+)/(\d+)"""
            )
                .find(url)

        if (match == null) {

            Log.e(
                TAG,
                "SERIE: URL /t/ non riconosciuto: $url"
            )

            return
        }

        val imdb =
            match.groupValues[1]

        val season =
            match.groupValues[2]

        val episode =
            match.groupValues[3]

        Log.d(TAG, "SERIE IMDB = $imdb")
        Log.d(TAG, "SERIE STAGIONE = $season")
        Log.d(TAG, "SERIE EPISODIO = $episode")

        /*
         * Questa è la vera pagina usata dal player VidxGo.
         *
         * Esempio:
         *
         * https://v.vidxgo.co/14688458/1/1
         */

        val episodePageUrl =
            "$mainUrl/$imdb/$season/$episode"

        Log.d(
            TAG,
            "SERIE PAGE URL = $episodePageUrl"
        )

        val pageHeaders =
            mapOf(
                "User-Agent" to USER_AGENT,

                "Accept" to
                    "text/html,application/xhtml+xml,application/xml;q=0.9," +
                    "image/avif,image/webp,*/*;q=0.8",

                "Accept-Language" to
                    "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",

                "Referer" to
                    pageReferer,

                "Sec-Fetch-Site" to
                    "cross-site",

                "Sec-Fetch-Mode" to
                    "navigate",

                "Sec-Fetch-Dest" to
                    "iframe",

                "Upgrade-Insecure-Requests" to
                    "1"
            )

        val response =
            app.get(
                episodePageUrl,
                headers = pageHeaders
            )

        val body =
            response.text

        Log.d(
            TAG,
            "SERIE STATUS = ${response.code}"
        )

        Log.d(
            TAG,
            "SERIE FINAL URL = ${response.url}"
        )

        Log.d(
            TAG,
            "SERIE BODY LENGTH = ${body.length}"
        )

        if (response.code !in 200..299) {

            Log.e(
                TAG,
                "SERIE HTTP ERROR = ${response.code}"
            )

            Log.e(
                TAG,
                "SERIE ERROR BODY = ${
                    body
                        .replace("\n", " ")
                        .take(3000)
                }"
            )

            return
        }

        /*
         * Cerca currentSrc negli script cifrati.
         */

        var videoUrl =
            extractCurrentSrc(
                body
            )

        /*
         * Fallback:
         *
         * per 1x01 VidxGo permette anche /IMDB.
         */

        if (
            videoUrl.isNullOrBlank() &&
            season == "1" &&
            episode == "1"
        ) {

            Log.d(
                TAG,
                "SERIE: provo fallback pagina base"
            )

            val baseUrl =
                "$mainUrl/$imdb"

            val baseResponse =
                app.get(
                    baseUrl,
                    headers = pageHeaders
                )

            Log.d(
                TAG,
                "SERIE BASE STATUS = ${baseResponse.code}"
            )

            if (
                baseResponse.code in
                    200..299
            ) {

                videoUrl =
                    extractCurrentSrc(
                        baseResponse.text
                    )
            }
        }

        if (videoUrl.isNullOrBlank()) {

            Log.e(
                TAG,
                "SERIE: currentSrc non trovato"
            )

            Log.e(
                TAG,
                "SERIE BODY PREVIEW = ${
                    body
                        .replace("\n", " ")
                        .take(5000)
                }"
            )

            return
        }

        val cleanVideoUrl =
            cleanUrl(
                videoUrl
            )

        Log.d(
            TAG,
            "SERIE VIDEO URL = $cleanVideoUrl"
        )

        emitVideo(
            videoUrl = cleanVideoUrl,
            refreshProvider = {
                try {
                    Log.d(TAG, "REFRESH SERIE: richiedo nuovo currentSrc")

                    val freshResponse =
                        app.get(
                            episodePageUrl,
                            headers = pageHeaders
                        )

                    Log.d(
                        TAG,
                        "REFRESH SERIE STATUS = ${freshResponse.code}"
                    )

                    if (freshResponse.code !in 200..299) {
                        null
                    } else {
                        extractCurrentSrc(freshResponse.text)
                            ?.let(::cleanUrl)
                    }
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "REFRESH SERIE ERROR = ${e.message}",
                        e
                    )
                    null
                }
            },
            callback = callback
        )
    }

    // ============================================================
    // MOVIE
    // ============================================================

    private suspend fun handleMovie(
        url: String,
        pageReferer: String,
        callback: (ExtractorLink) -> Unit
    ) {

        val origin =
            Regex(
                """^(https?://[^/]+)"""
            )
                .find(pageReferer)
                ?.groupValues
                ?.getOrNull(1)
                ?: "https://altadefinizione-01.fun"

        val headers =
            mutableMapOf(
                "User-Agent" to USER_AGENT,

                "Accept" to
                    "text/html,application/xhtml+xml,application/xml;q=0.9," +
                    "image/avif,image/webp,*/*;q=0.8",

                "Accept-Language" to
                    "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",

                "Referer" to
                    pageReferer,

                "Origin" to
                    origin,

                "Sec-Fetch-Site" to
                    "cross-site",

                "Sec-Fetch-Mode" to
                    "navigate",

                "Sec-Fetch-Dest" to
                    "iframe",

                "Upgrade-Insecure-Requests" to
                    "1"
            )

        Log.d(
            TAG,
            "REQUEST REFERER = $pageReferer"
        )

        Log.d(
            TAG,
            "REQUEST ORIGIN = $origin"
        )

        val response =
            app.get(
                url,
                headers = headers
            )

        val body =
            response.text

        Log.d(
            TAG,
            "STATUS = ${response.code}"
        )

        Log.d(
            TAG,
            "FINAL URL = ${response.url}"
        )

        Log.d(
            TAG,
            "BODY LENGTH = ${body.length}"
        )

        if (
            response.code !in
                200..299
        ) {

            Log.e(
                TAG,
                "VIDXGO HTTP ERROR = ${response.code}"
            )

            Log.e(
                TAG,
                "BODY = ${
                    body
                        .replace("\n", " ")
                        .take(3000)
                }"
            )

            return
        }

        val finalVideoUrl =
            extractCurrentSrc(
                body
            )

        if (
            finalVideoUrl.isNullOrBlank()
        ) {

            Log.e(
                TAG,
                "FILM: currentSrc non trovato"
            )

            return
        }

        val cleanVideoUrl =
            cleanUrl(
                finalVideoUrl
            )

        Log.d(
            TAG,
            "FILM VIDEO URL = $cleanVideoUrl"
        )

        emitVideo(
            videoUrl = cleanVideoUrl,
            refreshProvider = {
                try {
                    Log.d(TAG, "REFRESH FILM: richiedo nuovo currentSrc")

                    val freshResponse =
                        app.get(
                            url,
                            headers = headers
                        )

                    Log.d(
                        TAG,
                        "REFRESH FILM STATUS = ${freshResponse.code}"
                    )

                    if (freshResponse.code !in 200..299) {
                        null
                    } else {
                        extractCurrentSrc(freshResponse.text)
                            ?.let(::cleanUrl)
                    }
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "REFRESH FILM ERROR = ${e.message}",
                        e
                    )
                    null
                }
            },
            callback = callback
        )
    }

    // ============================================================
    // EXTRACT CURRENT SRC
    // ============================================================

    private fun extractCurrentSrc(
        body: String
    ): String? {

        /*
         * Prima prova currentSrc direttamente
         * nel documento.
         */

        val direct =
            Regex(
                """currentSrc\s*=\s*['"]([^'"]+)['"]"""
            )
                .find(body)
                ?.groupValues
                ?.getOrNull(1)

        if (
            !direct.isNullOrBlank()
        ) {

            Log.d(
                TAG,
                "currentSrc diretto trovato"
            )

            return cleanUrl(
                direct
            )
        }

        val document =
            Jsoup.parse(
                body
            )

        /*
         * Cerchiamo tutti gli script che usano
         * var k + atob(), cioè il sistema VidxGo.
         */

        val scripts =
            document
                .select("script")
                .mapNotNull { element ->

                    val content =
                        element
                            .data()
                            .ifBlank {
                                element.html()
                            }

                    content.takeIf {
                        script ->

                        script.contains(
                            "var k",
                            ignoreCase = true
                        ) &&
                            script.contains(
                                "atob(",
                                ignoreCase = true
                            )
                    }
                }

        Log.d(
            TAG,
            "SCRIPT CIFRATI = ${scripts.size}"
        )

        scripts.forEachIndexed {
                index,
                script ->

            try {

                val decrypted =
                    decryptFullScript(
                        script
                    )
                        ?: return@forEachIndexed

                Log.d(
                    TAG,
                    "SCRIPT [$index] DEC LENGTH = ${decrypted.length}"
                )

                val currentSrc =
                    Regex(
                        """(?:let|var|const)?\s*currentSrc\s*=\s*['"]([^'"]+)['"]"""
                    )
                        .find(
                            decrypted
                        )
                        ?.groupValues
                        ?.getOrNull(1)

                if (
                    !currentSrc.isNullOrBlank()
                ) {

                    Log.d(
                        TAG,
                        "currentSrc trovato nello script [$index]"
                    )

                    return cleanUrl(
                        currentSrc
                    )
                }

            } catch (
                e: Exception
            ) {

                Log.e(
                    TAG,
                    "Errore script [$index]: ${e.message}"
                )
            }
        }

        return null
    }

    // ============================================================
    // DECRYPT FULL SCRIPT
    // ============================================================

    private fun decryptFullScript(
        script: String
    ): String? {

        return try {

            val key =
                Regex(
                    """var\s+k\s*=\s*['"]([^'"]+)['"]"""
                )
                    .find(
                        script
                    )
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return null

            val encodedData =
                Regex(
                    """atob\(\s*['"]([^'"]+)['"]\s*\)"""
                )
                    .find(
                        script
                    )
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return null

            val decoded =
                Base64.decode(
                    encodedData,
                    Base64.DEFAULT
                )

            val decrypted =
                ByteArray(
                    decoded.size
                )

            for (
                i in decoded.indices
            ) {

                val dataByte =
                    decoded[i]
                        .toInt() and
                        0xFF

                val keyByte =
                    key[
                        i %
                            key.length
                    ]
                        .code and
                        0xFF

                decrypted[i] =
                    (
                        dataByte xor
                            keyByte
                        )
                        .toByte()
            }

            String(
                decrypted,
                Charsets.UTF_8
            )

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "decryptFullScript error: ${e.message}"
            )

            null
        }
    }

    // ============================================================
    // CLEAN URL
    // ============================================================

    private fun cleanUrl(
        url: String
    ): String {

        return url
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
            .trim()
    }

    // ============================================================
    // OUTPUT
    // ============================================================

    private suspend fun emitVideo(
        videoUrl: String,
        refreshProvider: suspend () -> String?,
        callback: (ExtractorLink) -> Unit
    ) {

        /*
         * Dal browser il CDN riceve:
         *
         * Origin:  https://v.vidxgo.co
         * Referer: https://v.vidxgo.co/
         */

        val streamHeaders =
            mapOf(
                "User-Agent" to USER_AGENT,
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/",
                "sec-fetch-dest" to "empty",
                "sec-fetch-mode" to "cors",
                "sec-fetch-site" to "cross-site"
            )

        val isM3u8 =
            videoUrl.contains(
                ".m3u8",
                ignoreCase = true
            )

        if (isM3u8) {

            /*
             * =========================================================
             * DIAGNOSTICA HLS / SCADENZA TOKEN
             * =========================================================
             *
             * Nel logcat abbiamo visto un 403 dopo ~4 minuti.
             * L'URL VidxGo contiene parametri firmati t=, e=, b=.
             * Qui NON modifichiamo il token: misuriamo soltanto
             * la sua scadenza e controlliamo master/variant playlist.
             */

            logSignedUrlInfo(videoUrl)

            /*
             * Il link firmato VidxGo scade dopo pochi minuti.
             * Un ExtractorLink normale non ha un callback di refresh
             * durante la riproduzione, quindi esponiamo al player una
             * playlist locale che rinnova currentSrc tramite il normale
             * flusso VidxGo quando la scadenza è vicina.
             */
            try {

                val proxyUrl =
                    VidxGoHlsRefreshProxy.register(
                        initialUrl = videoUrl,
                        headers = streamHeaders,
                        refreshProvider = refreshProvider
                    )

                Log.d(
                    TAG,
                    "HLS REFRESH PROXY = $proxyUrl"
                )

                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name Refresh",
                        url = proxyUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )

                return

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "HLS REFRESH PROXY ERROR = ${e.message}",
                    e
                )

                Log.w(
                    TAG,
                    "Uso fallback HLS originale senza refresh"
                )
            }

            try {

                val masterResponse =
                    app.get(
                        videoUrl,
                        headers = streamHeaders
                    )

                val masterBody =
                    masterResponse.text

                Log.d(
                    TAG,
                    "HLS MASTER STATUS = ${masterResponse.code}"
                )

                Log.d(
                    TAG,
                    "HLS MASTER FINAL = ${redactSignedUrl(masterResponse.url)}"
                )

                Log.d(
                    TAG,
                    "HLS MASTER LENGTH = ${masterBody.length}"
                )

                Log.d(
                    TAG,
                    "HLS MASTER TYPE = ${
                        when {
                            masterBody.contains("#EXT-X-ENDLIST") -> "VOD/ENDLIST"
                            masterBody.contains("#EXT-X-PLAYLIST-TYPE:VOD") -> "VOD"
                            masterBody.contains("#EXT-X-PLAYLIST-TYPE:EVENT") -> "EVENT"
                            else -> "LIVE/UNKNOWN"
                        }
                    }"
                )

                val playlistLines =
                    masterBody
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .toList()

                val variantUrls =
                    playlistLines
                        .filter {
                            !it.startsWith("#") &&
                                it.contains(
                                    ".m3u8",
                                    ignoreCase = true
                                )
                        }
                        .mapNotNull {
                            resolveUrl(
                                base = masterResponse.url,
                                child = it
                            )
                        }
                        .distinct()

                Log.d(
                    TAG,
                    "HLS VARIANT COUNT = ${variantUrls.size}"
                )

                variantUrls
                    .take(5)
                    .forEachIndexed { index, variantUrl ->

                        Log.d(
                            TAG,
                            "HLS VARIANT [$index] = ${redactSignedUrl(variantUrl)}"
                        )

                        try {

                            val variantResponse =
                                app.get(
                                    variantUrl,
                                    headers = streamHeaders
                                )

                            val variantBody =
                                variantResponse.text

                            Log.d(
                                TAG,
                                "HLS VARIANT [$index] STATUS = ${variantResponse.code}"
                            )

                            Log.d(
                                TAG,
                                "HLS VARIANT [$index] LENGTH = ${variantBody.length}"
                            )

                            val mediaUrls =
                                variantBody
                                    .lineSequence()
                                    .map { it.trim() }
                                    .filter {
                                        it.isNotBlank() &&
                                            !it.startsWith("#")
                                    }
                                    .mapNotNull {
                                        resolveUrl(
                                            base = variantResponse.url,
                                            child = it
                                        )
                                    }
                                    .toList()

                            Log.d(
                                TAG,
                                "HLS VARIANT [$index] MEDIA COUNT = ${mediaUrls.size}"
                            )

                            mediaUrls
                                .firstOrNull()
                                ?.let { firstMedia ->

                                    Log.d(
                                        TAG,
                                        "HLS VARIANT [$index] FIRST MEDIA = ${
                                            redactSignedUrl(firstMedia)
                                        }"
                                    )
                                }

                            mediaUrls
                                .lastOrNull()
                                ?.let { lastMedia ->

                                    Log.d(
                                        TAG,
                                        "HLS VARIANT [$index] LAST MEDIA = ${
                                            redactSignedUrl(lastMedia)
                                        }"
                                    )
                                }

                        } catch (e: Exception) {

                            Log.e(
                                TAG,
                                "HLS VARIANT [$index] CHECK ERROR = ${e.message}"
                            )
                        }
                    }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "HLS MASTER CHECK ERROR = ${e.message}",
                    e
                )
            }

            /*
             * Manteniamo il comportamento originale di CloudStream:
             * la diagnostica sopra non sostituisce M3u8Helper.
             */

            try {

                val links =
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = videoUrl,
                        referer = "$mainUrl/",
                        headers = streamHeaders
                    )

                Log.d(
                    TAG,
                    "M3U8 HELPER LINKS = ${links.size}"
                )

                links.forEachIndexed { index, link ->

                    Log.d(
                        TAG,
                        "M3U8 HELPER [$index] URL = ${redactSignedUrl(link.url)}"
                    )
                }

                if (links.isNotEmpty()) {

                    links.forEach(
                        callback
                    )

                    return
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "M3U8 parsing error: ${e.message}",
                    e
                )
            }

            /*
             * Fallback M3U8 diretto.
             */

            Log.d(
                TAG,
                "HLS FALLBACK DIRETTO = ${redactSignedUrl(videoUrl)}"
            )

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

        } else {

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
    }

    // ============================================================
    // HLS DIAGNOSTIC HELPERS
    // ============================================================

    private fun logSignedUrlInfo(
        url: String
    ) {

        try {

            val expiryRaw =
                Regex("""[?&]e=(\d+)""")
                    .find(url)
                    ?.groupValues
                    ?.getOrNull(1)

            if (expiryRaw.isNullOrBlank()) {

                Log.d(
                    TAG,
                    "HLS TOKEN EXPIRY = parametro e assente"
                )

                return
            }

            val expiryNumber =
                expiryRaw.toLongOrNull()

            if (expiryNumber == null) {

                Log.d(
                    TAG,
                    "HLS TOKEN EXPIRY = valore non numerico"
                )

                return
            }

            /*
             * VidxGo nel log usa un valore e= compatibile
             * con Unix epoch in millisecondi. Manteniamo anche
             * il supporto ai secondi per sicurezza.
             */

            val expiryMs =
                if (expiryNumber < 10_000_000_000L) {
                    expiryNumber * 1000L
                } else {
                    expiryNumber
                }

            val nowMs =
                System.currentTimeMillis()

            val remainingMs =
                expiryMs - nowMs

            Log.d(
                TAG,
                "HLS TOKEN EXPIRY MS = $expiryMs"
            )

            Log.d(
                TAG,
                "HLS TOKEN NOW MS = $nowMs"
            )

            Log.d(
                TAG,
                "HLS TOKEN REMAINING = ${remainingMs}ms (${remainingMs / 1000L}s)"
            )

            if (remainingMs <= 0L) {

                Log.e(
                    TAG,
                    "HLS TOKEN RISULTA GIÀ SCADUTO"
                )

            } else if (remainingMs <= 300_000L) {

                Log.w(
                    TAG,
                    "HLS TOKEN SCADENZA BREVE: meno di 5 minuti"
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "HLS TOKEN CHECK ERROR = ${e.message}"
            )
        }
    }

    private fun resolveUrl(
        base: String,
        child: String
    ): String? {

        return try {

            if (
                child.startsWith(
                    "http://",
                    ignoreCase = true
                ) ||
                child.startsWith(
                    "https://",
                    ignoreCase = true
                )
            ) {
                child
            } else {
                URI(base)
                    .resolve(child)
                    .toString()
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "HLS RESOLVE ERROR = ${e.message}"
            )

            null
        }
    }

    /*
     * Evitiamo di stampare nel log i valori dei token firmati.
     * Host, path e nomi dei parametri restano visibili.
     */
    private fun redactSignedUrl(
        url: String
    ): String {

        return try {

            val uri =
                URI(url)

            val queryKeys =
                uri.rawQuery
                    ?.split("&")
                    ?.mapNotNull {
                        it.substringBefore("=")
                            .takeIf { key ->
                                key.isNotBlank()
                            }
                    }
                    ?.distinct()
                    ?.joinToString(",")

            buildString {

                append(
                    URI(
                        uri.scheme,
                        uri.authority,
                        uri.path,
                        null,
                        null
                    ).toString()
                )

                if (!queryKeys.isNullOrBlank()) {
                    append("?keys=")
                    append(queryKeys)
                }
            }

        } catch (_: Exception) {

            url.substringBefore("?") +
                if (url.contains("?")) {
                    "?keys=redacted"
                } else {
                    ""
                }
        }
    }

}

/**
 * Proxy HLS locale per i link VidxGo a breve scadenza.
 *
 * Non calcola/modifica token t/e/b. Quando il token sta per scadere
 * richiama refreshProvider, che deve ottenere un nuovo currentSrc
 * tramite il normale flusso VidxGo.
 */
private object VidxGoHlsRefreshProxy {
    private const val TAG = "VIDXGO_PROXY"
    private const val VERSION = "integrated-v1"
    private const val REFRESH_MARGIN_MS = 60_000L
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    private data class Session(
        @Volatile var currentUrl: String,
        val headers: Map<String, String>,
        val refreshProvider: suspend () -> String?
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    private val executor = Executors.newCachedThreadPool()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var port: Int = -1

    @Synchronized
    private fun ensureServer() {
        val current = serverSocket
        if (current != null && !current.isClosed) return

        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        Log.d(TAG, "Server locale avviato su 127.0.0.1:$port version=$VERSION")

        executor.execute {
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    executor.execute { handleClient(client) }
                } catch (e: Exception) {
                    if (!socket.isClosed) Log.e(TAG, "accept error=${e.message}")
                }
            }
        }
    }

    fun register(
        initialUrl: String,
        headers: Map<String, String>,
        refreshProvider: suspend () -> String?
    ): String {
        ensureServer()
        val id = UUID.randomUUID().toString().replace("-", "")
        sessions[id] = Session(initialUrl, headers, refreshProvider)
        Log.d(TAG, "Sessione creata id=$id remaining=${remainingMs(initialUrl)}ms")
        return "http://127.0.0.1:$port/v/$id/master.m3u8"
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = READ_TIMEOUT_MS
                val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return writeError(client, 400, "Bad Request")

                val method = parts[0]
                val target = parts[1]
                val requestHeaders = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) requestHeaders[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                }

                val path = target.substringBefore('?')
                val query = target.substringAfter('?', "")
                val match = Regex("""^/v/([A-Za-z0-9]+)/(.+)$""").find(path)
                    ?: return writeError(client, 404, "Not Found")

                val id = match.groupValues[1]
                val resource = match.groupValues[2]
                val session = sessions[id] ?: return writeError(client, 404, "Session expired")

                when {
                    resource == "master.m3u8" -> {
                        val masterName = URI(session.currentUrl).path.substringAfterLast('/')
                        servePlaylist(client, method, id, session, masterName)
                    }
                    resource == "file" -> {
                        val encoded = parseQuery(query)["p"]
                            ?: return writeError(client, 400, "Missing path")
                        val relative = URLDecoder.decode(encoded, "UTF-8")
                        if (relative.contains(".m3u8", ignoreCase = true)) {
                            servePlaylist(client, method, id, session, relative)
                        } else {
                            serveBinary(client, method, session, relative, requestHeaders)
                        }
                    }
                    else -> writeError(client, 404, "Not Found")
                }
            } catch (e: Exception) {
                if (isClientDisconnect(e)) {
                    Log.d(TAG, "Client locale ha chiuso la richiesta: ${e.message}")
                } else {
                    Log.e(TAG, "client error=${e.message}", e)
                    try { writeError(client, 500, "Proxy Error") } catch (_: Exception) {}
                }
            }
        }
    }

    private fun isClientDisconnect(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message?.lowercase().orEmpty()
            if (
                message.contains("broken pipe") ||
                message.contains("connection reset") ||
                message.contains("socket closed") ||
                message.contains("connection aborted")
            ) return true
            current = current.cause
        }
        return false
    }

    private fun parseQuery(query: String): Map<String, String> =
        if (query.isBlank()) emptyMap() else query.split('&').mapNotNull { item ->
            val k = item.substringBefore('=', "")
            val v = item.substringAfter('=', "")
            k.takeIf { it.isNotBlank() }?.let { it to v }
        }.toMap()

    private fun servePlaylist(
        client: Socket,
        method: String,
        id: String,
        session: Session,
        relativePath: String
    ) {
        val conn = fetchWithRefresh(session, relativePath, null)
        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        conn.disconnect()

        val rewritten = rewritePlaylist(id, body)
        val bytes = rewritten.toByteArray(Charsets.UTF_8)
        val out = BufferedOutputStream(client.getOutputStream())
        writeHeaders(out, 200, "application/vnd.apple.mpegurl", bytes.size.toLong())
        if (!method.equals("HEAD", true)) out.write(bytes)
        out.flush()
    }

    private fun rewritePlaylist(id: String, body: String): String =
        body.lineSequence().map { raw ->
            val line = raw.trim()
            when {
                line.isBlank() -> raw
                line.startsWith("#") -> Regex("""URI=\"([^\"]+)\"""").replace(raw) { m ->
                    "URI=\"${localFileUrl(id, extractRelativePath(m.groupValues[1]))}\""
                }
                else -> localFileUrl(id, extractRelativePath(line))
            }
        }.joinToString("\n")

    private fun localFileUrl(id: String, path: String): String =
        "http://127.0.0.1:$port/v/$id/file?p=${URLEncoder.encode(path, "UTF-8")}" 

    private fun extractRelativePath(urlOrPath: String): String = try {
        val clean = urlOrPath.substringBefore('?')
        if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) {
            URI(clean).path.substringAfterLast('/')
        } else clean.substringAfterLast('/')
    } catch (_: Exception) {
        urlOrPath.substringBefore('?').substringAfterLast('/')
    }

    private fun serveBinary(
        client: Socket,
        method: String,
        session: Session,
        relativePath: String,
        requestHeaders: Map<String, String>
    ) {
        val range = requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value
        val conn = fetchWithRefresh(session, relativePath, range)
        val status = conn.responseCode
        val contentType = conn.contentType ?: guessContentType(relativePath)
        val out = BufferedOutputStream(client.getOutputStream())
        writeHeaders(
            out = out,
            status = status,
            contentType = contentType,
            contentLength = conn.contentLengthLong,
            contentRange = conn.getHeaderField("Content-Range"),
            acceptRanges = conn.getHeaderField("Accept-Ranges")
        )

        if (!method.equals("HEAD", true)) {
            BufferedInputStream(conn.inputStream).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    try {
                        out.write(buffer, 0, n)
                    } catch (e: Exception) {
                        if (isClientDisconnect(e)) {
                            Log.d(TAG, "Segmento annullato dal player: $relativePath")
                            break
                        }
                        throw e
                    }
                }
            }
        }
        try {
            out.flush()
        } catch (e: Exception) {
            if (!isClientDisconnect(e)) throw e
            Log.d(TAG, "Flush annullato dal player: $relativePath")
        }
        conn.disconnect()
    }

    private fun guessContentType(path: String): String = when {
        path.endsWith(".ts", true) -> "video/mp2t"
        path.endsWith(".aac", true) -> "audio/aac"
        path.endsWith(".mp4", true) || path.endsWith(".m4s", true) -> "video/mp4"
        else -> "application/octet-stream"
    }

    private fun fetchWithRefresh(session: Session, relativePath: String, range: String?): HttpURLConnection {
        ensureFresh(session, force = false)
        var conn = openRemote(session, relativePath, range)
        if (conn.responseCode == 403) {
            Log.w(TAG, "CDN 403 su $relativePath: forzo refresh")
            conn.disconnect()
            ensureFresh(session, force = true)
            conn = openRemote(session, relativePath, range)
        }
        return conn
    }

    private fun openRemote(session: Session, relativePath: String, range: String?): HttpURLConnection {
        val remoteUrl = buildRemoteUrl(session.currentUrl, relativePath)
        val conn = URI(remoteUrl).toURL().openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.requestMethod = "GET"
        session.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (!range.isNullOrBlank()) conn.setRequestProperty("Range", range)
        conn.connect()
        return conn
    }

    private fun buildRemoteUrl(currentUrl: String, relativePath: String): String {
        val current = URI(currentUrl)
        val directory = current.path.substringBeforeLast('/', "")
        return URI(current.scheme, current.authority, "$directory/$relativePath", current.rawQuery, null).toString()
    }

    private fun ensureFresh(session: Session, force: Boolean) {
        val remaining = remainingMs(session.currentUrl)
        if (!force && remaining != null && remaining > REFRESH_MARGIN_MS) return

        synchronized(session) {
            val inside = remainingMs(session.currentUrl)
            if (!force && inside != null && inside > REFRESH_MARGIN_MS) return

            Log.d(TAG, "REFRESH necessario force=$force remaining=${inside}ms")
            val fresh = try {
                runBlocking { session.refreshProvider() }
            } catch (e: Exception) {
                Log.e(TAG, "refreshProvider error=${e.message}", e)
                null
            }

            if (fresh.isNullOrBlank()) {
                Log.e(TAG, "REFRESH fallito: currentSrc nullo")
                return
            }

            session.currentUrl = fresh
            Log.d(TAG, "REFRESH OK remaining=${remainingMs(fresh)}ms")
        }
    }

    private fun remainingMs(url: String): Long? {
        val raw = Regex("""[?&]e=(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return null
        val expiry = if (raw < 10_000_000_000L) raw * 1000L else raw
        return expiry - System.currentTimeMillis()
    }

    private fun writeHeaders(
        out: BufferedOutputStream,
        status: Int,
        contentType: String,
        contentLength: Long,
        contentRange: String? = null,
        acceptRanges: String? = null
    ) {
        val reason = when (status) {
            200 -> "OK"
            206 -> "Partial Content"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val h = StringBuilder()
        h.append("HTTP/1.1 $status $reason\r\n")
        h.append("Content-Type: $contentType\r\n")
        if (contentLength >= 0) h.append("Content-Length: $contentLength\r\n")
        if (!contentRange.isNullOrBlank()) h.append("Content-Range: $contentRange\r\n")
        if (!acceptRanges.isNullOrBlank()) h.append("Accept-Ranges: $acceptRanges\r\n")
        h.append("Cache-Control: no-store\r\n")
        h.append("Connection: close\r\n\r\n")
        out.write(h.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun writeError(client: Socket, status: Int, message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        val out = BufferedOutputStream(client.getOutputStream())
        writeHeaders(out, status, "text/plain; charset=utf-8", bytes.size.toLong())
        out.write(bytes)
        out.flush()
    }
}
