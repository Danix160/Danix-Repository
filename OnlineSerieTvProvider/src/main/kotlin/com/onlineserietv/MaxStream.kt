package com.onlineserietv

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
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
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("Referer" to (referer ?: url))
        
        // 1. Chiamata iniziale
        var response = app.get(url, headers = headers)
        var html = response.text

        // 2. Fallback con WebView se Cloudflare blocca la richiesta
        if (response.code == 403 || response.code == 503 || html.contains("challenge-platform")) {
            response = app.get(
                url,
                headers = headers,
                interceptor = WebViewResolver(Regex("""https?://(?:www\.)?maxstream\.video/.*"""))
            )
            html = response.text
        }

        // 3. Regex più flessibile per catturare sia 'file' che 'src' con apici doppi o singoli
        val pattern = """(?:sources|file|src)\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""".toRegex(RegexOption.IGNORE_CASE)
        val match = pattern.find(html)

        // Fallback: cerca qualsiasi stringa che termini in .m3u8 nel JS
        val videoUrl = match?.groupValues?.get(1) 
            ?: """["'](https?://[^"']+\.m3u8[^"']*)["']""".toRegex(RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)

        if (videoUrl != null) {
            if (videoUrl.contains(".m3u8")) {
                // Scompatta automaticamente le risoluzioni (1080p, 720p, ecc.)
                M3u8Helper.generateM3u8(
                    name = this.name,
                    m3u8Url = videoUrl,
                    referer = url,
                    headers = mapOf("Referer" to mainUrl)
                ).forEach(callback)
            } else {
                // Link video diretto (es. MP4)
                val link = newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoUrl,
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
                callback.invoke(link)
            }
        }
    }
}
