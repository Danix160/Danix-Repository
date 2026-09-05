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
