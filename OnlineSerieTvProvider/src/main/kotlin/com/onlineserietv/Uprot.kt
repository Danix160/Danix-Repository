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
        // 1. Trasformiamo l'URL da /msf/ o /msfi/ a /mse/ come fa l'algoritmo
        val uprotUrl = url.replace("/msf/", "/mse/").replace("/msfi/", "/mse/")
        
        var maxstreamUrl: String? = null

        // 2. Troviamo il link MaxStream decodificando il Base64 contenuto nel JS
        try {
            val response = app.get(uprotUrl, referer = referer ?: mainUrl)
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

        // 3. Fallback: Se la decodifica Base64 fallisce, cerchiamo un reindirizzamento diretto o tag iframe
        if (maxstreamUrl == null) {
            val fallbackResponse = app.get(url, referer = referer)
            val doc = Jsoup.parse(fallbackResponse.text)
            
            maxstreamUrl = doc.selectFirst("iframe[src*=maxstream]")?.attr("src")
                ?: doc.selectFirst("a[href*=maxstream]")?.attr("href")
                ?: fallbackResponse.url.takeIf { it.contains("maxstream") }
        }

        // 4. Passiamo l'URL estratto di MaxStream all'estrattore MaxStream.kt
        maxstreamUrl?.let { finalUrl ->
            loadExtractor(finalUrl, uprotUrl, subtitleCallback, callback)
        }
    }
}
