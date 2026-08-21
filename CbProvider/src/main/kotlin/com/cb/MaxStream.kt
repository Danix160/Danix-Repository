package com.cb

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

    override val name = "MaxStream"
    override val mainUrl = "https://maxstream.video"
    override val requiresReferer = true

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
                .takeIf { it.isNotBlank() }
                ?: USER_AGENT

        val sessionCookies =
            UprotSession.cookieHeader

        val headers =
            mutableMapOf(
                "User-Agent" to sessionUserAgent,
                "Referer" to (referer ?: mainUrl),
                "Accept" to
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to
                    "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7"
            )

        if (sessionCookies.isNotBlank()) {
            headers["Cookie"] = sessionCookies

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

        var html = response.text

        Log.e("MAXSTREAM_DEBUG", "STATUS = ${response.code}")
        Log.e("MAXSTREAM_DEBUG", "HTML LENGTH = ${html.length}")

        val maxDoc = Jsoup.parse(html)

        val isCaptcha =
            maxDoc.selectFirst("#upcaptcha-form") != null ||
            maxDoc.selectFirst(".upcaptcha-box") != null

        if (isCaptcha) {
            Log.e(
                "MAXSTREAM_DEBUG",
                "MaxStream richiede ancora UPCaptcha nonostante la sessione WebView"
            )
            return
        }

        // ...continua qui con il tuo extractor attuale

        android.util.Log.e("MAXSTREAM_DEBUG", "==============================")
android.util.Log.e("MAXSTREAM_DEBUG", "URL = $url")
android.util.Log.e("MAXSTREAM_DEBUG", "REFERER = $referer")
android.util.Log.e("MAXSTREAM_DEBUG", "STATUS = ${response.code}")
android.util.Log.e("MAXSTREAM_DEBUG", "FINAL URL = ${response.url}")
android.util.Log.e("MAXSTREAM_DEBUG", "HTML LENGTH = ${html.length}")

val document = org.jsoup.Jsoup.parse(html)

android.util.Log.e("MAXSTREAM_DEBUG", "TITLE = ${document.title()}")

document.select("script").forEachIndexed { index, script ->
    val src = script.attr("src")

    if (src.isNotBlank()) {
        android.util.Log.e(
            "MAXSTREAM_DEBUG",
            "SCRIPT SRC [$index] = $src"
        )
    }

    val content = script.data().ifBlank { script.html() }

    if (content.isNotBlank()) {
        android.util.Log.e(
            "MAXSTREAM_DEBUG",
            "SCRIPT [$index] = ${
                content.replace("\n", " ").take(5000)
            }"
        )
    }
}

document.select("iframe").forEachIndexed { index, iframe ->
    android.util.Log.e(
        "MAXSTREAM_DEBUG",
        "IFRAME [$index] = ${iframe.attr("src")}"
    )
}

document.select("a[href]").forEachIndexed { index, a ->
    val href = a.attr("href")

    if (
        href.contains("maxstream", true) ||
        href.contains("m3u8", true) ||
        href.contains("mp4", true)
    ) {
        android.util.Log.e(
            "MAXSTREAM_DEBUG",
            "LINK [$index] = $href"
        )
    }
}

android.util.Log.e(
    "MAXSTREAM_DEBUG",
    "HTML = ${html.take(15000)}"
)

        if (html.contains("eval(function(p,a,c,k,e,d)")) {
            html = getPackedJs(html) ?: html
        }

        val streamUrlRegex = """https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*""".toRegex()
        val matches = streamUrlRegex.findAll(html).map { it.value }.distinct().toList()

        for (streamUrl in matches) {
            val isM3u8 = streamUrl.contains(".m3u8")

            if (isM3u8) {
                // Utilizzo della funzione generateM3u8 nativa
                val m3u8Links = M3u8Helper.generateM3u8(
                    source = this.name,
                    streamUrl = streamUrl,
                    referer = url,
                    headers = headers
                )

                if (m3u8Links.isNotEmpty()) {
                    m3u8Links.forEach(callback)
                } else {
                    // Fallback nel caso la lista master M3U8 sia singola
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.headers = headers
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            } else {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.headers = headers
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
    }

    private fun getPackedJs(html: String): String? {
        val packedRegex = """eval\(function\(p,a,c,k,e,d\).*?\}\((.*?)\)\)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        return packedRegex.find(html)?.value
    }
}
