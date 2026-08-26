package com.cb

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

class MaxStream : ExtractorApi() {

    override val name =
        "MaxStream"

    override val mainUrl =
        "https://maxstream.video"

    override val requiresReferer =
        true

    companion object {

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/139.0.0.0 Mobile Safari/537.36"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val sessionUserAgent =
            UprotSession.userAgent
                .takeIf {
                    it.isNotBlank()
                }
                ?: USER_AGENT

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

        val response =
            app.get(
                url,
                headers = headers
            )

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

        val initialDoc =
            Jsoup.parse(
                html
            )

        val cloudflareBlocked =
            response.code == 403 ||
                initialDoc.title()
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

            when (
                webViewResult.status
            ) {

                MaxStreamWebViewStatus.PLAYER_PAGE_READY -> {

                    Log.d(
                        "MAXSTREAM_DEBUG",
                        "Pagina interna del player caricata correttamente"
                    )

                    /*
                     * Diagnostica completata.
                     * Non intercettiamo né estraiamo URL multimediali
                     * dalla pagina protetta.
                     */
                    return
                }

                MaxStreamWebViewStatus.PLAYER_FOUND -> {

                    Log.d(
                        "MAXSTREAM_DEBUG",
                        "Player MaxStream reale rilevato nella WebView"
                    )

                    Log.d(
                        "MAXSTREAM_DEBUG",
                        "PLAYER IFRAME DIAGNOSTICO = ${webViewResult.playerUrl}"
                    )

                    /*
                     * Risultato diagnostico:
                     * restituiamo al chiamante l'URL dell'iframe reale
                     * individuato nel DOM, ma non estraiamo automaticamente
                     * lo stream dalla pagina protetta.
                     */
                    return
                }

                MaxStreamWebViewStatus.CANCELLED -> {

                    Log.e(
                        "MAXSTREAM_DEBUG",
                        "WebView MaxStream chiusa prima del completamento"
                    )

                    return
                }

                MaxStreamWebViewStatus.TIMEOUT -> {

                    Log.e(
                        "MAXSTREAM_DEBUG",
                        "Timeout WebView MaxStream"
                    )

                    return
                }
            }
        }

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
                "MaxStream richiede ancora UPCaptcha nonostante la sessione WebView"
            )

            return
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
                                response.url
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

                html =
                    iframeHtml

                playerReferer =
                    iframeResponse.url

            } catch (
                e: Exception
            ) {

                Log.e(
                    "MAXSTREAM_DEBUG",
                    "Errore caricamento iframe /emiuhi/: ${e.message}"
                )
            }

        } else {

            Log.e(
                "MAXSTREAM_DEBUG",
                "decodedBaseUrl/decodedFileCode non trovati"
            )
        }

        Log.e(
            "MAXSTREAM_DEBUG",
            "=============================="
        )

        Log.e(
            "MAXSTREAM_DEBUG",
            "PLAYER REFERER = $playerReferer"
        )

        val document =
            Jsoup.parse(
                html
            )

        Log.e(
            "MAXSTREAM_DEBUG",
            "PLAYER TITLE = ${document.title()}"
        )

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
                "STREAM = $streamUrl"
            )

            val isM3u8 =
                streamUrl.contains(
                    ".m3u8",
                    ignoreCase = true
                )

            val streamHeaders =
                headers
                    .toMutableMap()
                    .apply {

                        this["Referer"] =
                            playerReferer
                    }

            if (
                isM3u8
            ) {

                val m3u8Links =
                    M3u8Helper.generateM3u8(
                        source = this.name,
                        streamUrl = streamUrl,
                        referer = playerReferer,
                        headers = streamHeaders
                    )

                if (
                    m3u8Links.isNotEmpty()
                ) {

                    m3u8Links
                        .forEach(
                            callback
                        )

                } else {

                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = streamUrl,
                            type =
                                ExtractorLinkType.M3U8
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

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = streamUrl,
                        type =
                            ExtractorLinkType.VIDEO
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
        }
    } 
}
