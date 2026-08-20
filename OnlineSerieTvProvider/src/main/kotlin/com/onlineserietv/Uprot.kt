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
        // 1. Chiamata alla pagina iniziale di Uprot
        val response = app.get(url, referer = referer)
        val document = Jsoup.parse(response.text)

        // 2. Cerca il link intermedio (es. /uprots/...)
        val rawTargetUrl = document.selectFirst("a[href*=/uprots/]")?.attr("href")
            ?: document.selectFirst("a[href*=/msf/]")?.attr("href")
            ?: document.select("a[href]").map { it.attr("href") }.firstOrNull { 
                it.contains("maxstream") || it.contains("uprots") 
            }

        val stepUrl = if (!rawTargetUrl.isNullOrEmpty()) {
            if (rawTargetUrl.startsWith("http://") || rawTargetUrl.startsWith("https://")) {
                rawTargetUrl
            } else {
                URI(mainUrl).resolve(rawTargetUrl).toString()
            }
        } else null

        if (stepUrl != null) {
            // 3. Eseguiamo la richiesta (app.get segue i redirect di default)
            val finalResponse = app.get(stepUrl, referer = url)
            val destinationUrl = finalResponse.url

            // Se il reindirizzamento ha portato a MaxStream o a un dominio differente
            if (destinationUrl.contains("maxstream") || destinationUrl != stepUrl) {
                loadExtractor(destinationUrl, stepUrl, subtitleCallback, callback)
            } else {
                // Se rimane sulla stessa pagina, cerchiamo un eventuale iframe o link all'interno del DOM
                val doc2 = Jsoup.parse(finalResponse.text)
                val realUrl = doc2.selectFirst("iframe[src*=maxstream]")?.attr("src")
                    ?: doc2.selectFirst("a[href*=maxstream]")?.attr("href")
                    ?: destinationUrl

                loadExtractor(realUrl, stepUrl, subtitleCallback, callback)
            }
        }
    }
}
