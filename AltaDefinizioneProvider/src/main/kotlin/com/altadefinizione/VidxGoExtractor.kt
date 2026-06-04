package com.altadefinizione

import android.util.Base64
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.app
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

class VidxGoExtractor : ExtractorApi() {
    override val name = "VidxGo"
    override val mainUrl = "https://v.vidxgo.co"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        
        val refererUrl = "${url.toHttpUrl().scheme}://${url.toHttpUrl().host}/"
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Referer", refererUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        
        if (!url.contains("/t/")) {
            requestBuilder.header("sec-fetch-dest", "iframe")
        }

        val response = app.get(
            url = url,
            headers = requestBuilder.build().headers.toMap()
        )
        val html = response.text

        val videoUrl: String

        if (url.contains("/t/")) {
            // Path A: Endpoint Serie TV
            val videoUrlRaw = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                ?: return emptyList()
            videoUrl = videoUrlRaw.replace("\\/", "/")
        } else {
            // Path B: Parsing dello Script Cifrato per i Film
            val scriptRegex = Regex("<script[\\s\\S]*?>[\\s\\S]*?\\(function\\(\\)\\s*\\{[\\s\\S]*?\\}\\s*\\)\\(\\);[\\s\\S]*?</script>", RegexOption.IGNORE_CASE)
            val scriptMatches = scriptRegex.findAll(html).toList()
            
            if (scriptMatches.size < 3) return emptyList()

            val targetScript = scriptMatches[2].value
            val k = Regex("var\\s+k\\s*=\\s*['\"]([^'\"]+)['\"]").find(targetScript)?.groupValues?.get(1) ?: return emptyList()
            val d = Regex("atob\\(['\"]([^'\"]+)['\"]\\)").find(targetScript)?.groupValues?.get(1) ?: return emptyList()

            val decodedD = Base64.decode(d, Base64.DEFAULT)
            val decrypted = ByteArray(decodedD.size) { i ->
                ((decodedD[i].toInt() and 0xFF) xor (k[i % k.length].code and 0xFF)).toByte()
            }
            val decryptedText = String(decrypted)
            
            val videoUrlRaw = Regex("currentSrc\\s*=\\s*['\"]([^'\"]+)['\"]").find(decryptedText)?.groupValues?.get(1) ?: return emptyList()
            videoUrl = videoUrlRaw.replace("\\/", "/")
        }

        val playbackHeaders = mapOf(
            "origin" to "https://v.vidxgo.co",
            "referer" to "https://v.vidxgo.co/",
            "sec-fetch-dest" to "empty",
            "sec-fetch-site" to "cross-site"
        )

        if (videoUrl.contains(".m3u8")) {
            M3u8Helper.generateM3u8(
                name,
                videoUrl,
                refererUrl,
                headers = playbackHeaders
            ).forEach { links.add(it) }
        } else {
            links.add(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    referer = refererUrl,
                    quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                    isM3u8 = false,
                    headers = playbackHeaders
                )
            )
        }

        return links
    }
}
