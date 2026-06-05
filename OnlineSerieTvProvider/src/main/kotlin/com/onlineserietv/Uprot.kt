package com.onlineserietv

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.app

class Uprot : ExtractorApi() {
    override val name = "Uprot"
    override val mainUrl = "https://uprot.net"
    override val requiresReferer = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "it-IT,it;q=0.9"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 1️⃣ Trasformiamo l'URL in /mse/ per attivare lo shortcut
        val target = normalize(url)

        // 2️⃣ Eseguiamo la richiesta disattivando il redirect automatico.
        // Vogliamo intercettare subito l'header "Location" che contiene il link di MaxStream.
        val res = app.get(target, headers = headers, referer = referer ?: mainUrl, allowRedirects = false)
        
        val redirectUrl = res.headers["Location"] ?: res.url

        // 3️⃣ Se l'URL ottenuto (da Location o finale) è di MaxStream, lo passiamo al core di CloudStream
        if (redirectUrl.contains("maxstream")) {
            loadExtractor(redirectUrl, target, subtitleCallback, callback)
        } else {
            // Fallback: se per qualche motivo il trucco di /mse/ non reindirizza subito,
            // usiamo il vecchio rimpiazzo e carichiamo l'URL (es. se gestito da altri estrattori interni)
            loadExtractor(redirectUrl, url, subtitleCallback, callback)
        }
    }

    private fun normalize(url: String): String {
        return when {
            url.contains("/msf/") -> url.replace("/msf/", "/mse/")
            url.contains("/msfi/") -> url.replace("/msfi/", "/mse/")
            else -> url
        }
    }
}
