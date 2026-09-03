package com.onlineserietv

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import android.webkit.CookieManager
import android.util.Log

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
       val userAgent =
            "Mozilla/5.0 (Linux; Android 10; K) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/139.0.0.0 Mobile Safari/537.36"
        
        val cookieManager = CookieManager.getInstance()
        
        val maxStreamCookies =
            cookieManager.getCookie(url)
                ?: cookieManager.getCookie("https://maxstream.video")
                ?: ""
        
        Log.d("MAXSTREAM_DEBUG", "URL = $url")
        Log.d("MAXSTREAM_DEBUG", "REFERER = $referer")
        Log.d(
            "MAXSTREAM_DEBUG",
            "COOKIE MAXSTREAM PRESENTI = ${maxStreamCookies.isNotBlank()}"
        )
        
        val headers = mutableMapOf(
            "User-Agent" to userAgent,
            "Referer" to (referer ?: url)
        )
        
        if (maxStreamCookies.isNotBlank()) {
            headers["Cookie"] = maxStreamCookies
}

        val response = app.get(url, headers = headers)
        
        Log.d("MAXSTREAM_DEBUG", "STATUS = ${response.code}")
        Log.d("MAXSTREAM_DEBUG", "FINAL URL = ${response.url}")
        Log.d("MAXSTREAM_DEBUG", "HTML LENGTH = ${response.text.length}")
        
        var html = response.text

        if (html.contains("eval(function(p,a,c,k,e,d)")) {
            html = getPackedJs(html) ?: html
        }

        val streamUrlRegex = """https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*""".toRegex()
        val matches = streamUrlRegex.findAll(html).map { it.value }.distinct().toList()

        for (streamUrl in matches) {
            val isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true)
            Log.d(
            "MAXSTREAM_DEBUG",
            "STREAM = $streamUrl | M3U8=$isM3u8"
        )

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
