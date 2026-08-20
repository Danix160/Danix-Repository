package com.onlineserietv

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

class MaxStream : ExtractorApi() {
    override val name = "MaxStream"
    override val mainUrl = "https://maxstream.video"
    override val requiresReferer = true

    // Intercetta maxstream.video e tutti i domini mirror tipo maxthu741.site o similari
    override var mainUrlRegex = """https?://(www\.)?(maxstream\.[a-z]+|maxthu\d+\.[a-z]+)""".toRegex().pattern

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Prepariamo gli header necessari per evitare il blocco 403 HTTP
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Referer" to (referer ?: url)
        )

        val response = app.get(url, headers = headers)
        var html = response.text

        // Se lo script è impacchettato con Dean Edwards Packer (eval(function(p,a,c,k,e,d)...))
        if (html.contains("eval(function(p,a,c,k,e,d)")) {
            html = getPackedJs(html) ?: html
        }

        // Regex flessibile per catturare flussi M3U8 o MP4 isolati
        val streamUrlRegex = """https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*""".toRegex()
        val matches = streamUrlRegex.findAll(html).map { it.value }.distinct().toList()

        matches.forEach { streamUrl ->
            val isM3u8 = streamUrl.contains(".m3u8")

            if (isM3u8) {
                // Estrattore master M3U8 nativo di Cloudstream per la risoluzione delle qualità (1080p, 720p, etc.)
                M3u8Helper().m3u8Generation(streamUrl, streamUrl).forEach { link ->
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} ${link.quality}p",
                            url = link.url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.headers = headers
                            this.quality = link.quality
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

    // Helper per estrarre il payload scompattato dallo script eval
    private fun getPackedJs(html: String): String? {
        val packedRegex = """eval\(function\(p,a,c,k,e,d\).*?\}\((.*?)\)\)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        return packedRegex.find(html)?.value
    }
}
