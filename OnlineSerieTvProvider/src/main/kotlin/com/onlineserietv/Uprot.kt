package com.onlineserietv

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import java.net.URI

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
        // 1. Richiesta alla pagina di Uprot (es. /msf/m2conw034rnu)
        val response = app.get(url, referer = referer)
        val document = Jsoup.parse(response.text)

        // 2. Estrazione del link verso MaxStream o del path /uprots/
        val rawTargetUrl = document.selectFirst("a[href*=/uprots/]")?.attr("href")
            ?: document.selectFirst("a[href*=/msf/]")?.attr("href")
            ?: document.select("a[href]").map { it.attr("href") }.firstOrNull { 
                it.contains("maxstream") || it.contains("uprots") 
            }

        // 3. Risoluzione dell'URL completo
        val targetUrl: String? = if (!rawTargetUrl.isNullOrEmpty()) {
            if (rawTargetUrl.startsWith("http://") || rawTargetUrl.startsWith("https://")) {
                rawTargetUrl
            } else {
                URI(mainUrl).resolve(rawTargetUrl).toString()
            }
        } else null

        // 4. Se il link finale è stato trovato, lo passiamo al sistema di risoluzione di Cloudstream
        targetUrl?.let { finalUrl ->
            loadExtractor(finalUrl, url, subtitleCallback, callback)
        }
    }
}
