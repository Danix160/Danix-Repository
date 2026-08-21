package com.cb

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class MaxStream : ExtractorApi() {
    override val name = "MaxStream"
    override val mainUrl = "https://maxstream.video"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Referer" to (referer ?: url)
        )

        val response = app.get(url, headers = headers)
        var html = response.text

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
