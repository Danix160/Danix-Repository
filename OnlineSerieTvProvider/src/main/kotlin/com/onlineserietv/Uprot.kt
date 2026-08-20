package com.onlineserietv

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

class Uprot : ExtractorApi() {
    override val name = "Uprot"
    override val mainUrl = "https://uprot.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val uprotUrl = url.replace("/msf/", "/mse/").replace("/msfi/", "/mse/")
        var maxstreamUrl: String? = null

        val customHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Referer" to (referer ?: mainUrl)
        )

        try {
            val response = app.get(uprotUrl, headers = customHeaders)
            val html = response.text

            val b64Base = Regex("""decodedBaseUrl\s*=\s*atob\(["']([^"']+)["']\)""").find(html)?.groupValues?.getOrNull(1)
            val b64Val = Regex("""decodedEncryptedVal\s*=\s*atob\(["']([^"']+)["']\)""").find(html)?.groupValues?.getOrNull(1)

            if (!b64Base.isNullOrBlank() && !b64Val.isNullOrBlank()) {
                val decodedBase = String(Base64.decode(b64Base, Base64.DEFAULT), Charsets.UTF_8)
                val decodedVal = String(Base64.decode(b64Val, Base64.DEFAULT), Charsets.UTF_8)
                val candidate = decodedBase + decodedVal
                if (candidate.isNotBlank()) {
                    maxstreamUrl = candidate
                }
            }
        } catch (_: Exception) { }

        // Fallback tramite DOM parsing
        if (maxstreamUrl == null) {
            val fallbackResponse = app.get(url, headers = customHeaders)
            val doc = Jsoup.parse(fallbackResponse.text)
            
            maxstreamUrl = doc.selectFirst("iframe[src*=/ms/]")?.attr("src")
                ?: doc.selectFirst("iframe[src*=max]")?.attr("src")
                ?: doc.selectFirst("a[href*=max]")?.attr("href")
                ?: fallbackResponse.url.takeIf { it.contains("max") }
        }

        maxstreamUrl?.let { finalUrl ->
            // Invia l'URL a Cloudstream affinché lo passi a MaxStream.kt
            loadExtractor(finalUrl, uprotUrl, subtitleCallback, callback)
        }
    }
}
