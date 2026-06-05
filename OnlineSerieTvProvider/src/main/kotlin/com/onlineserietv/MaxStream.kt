package com.onlineserietv

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app

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
        try {
            val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            var currentUrl = url

            // FORZATURA DA msf A mse: 
            // Se l'URL contiene /msf/, lo trasformiamo subito in /mse/ per atterrare direttamente sulla pagina con il bottone "Continue"
            if (currentUrl.contains("/msf/")) {
                currentUrl = currentUrl.replace("/msf/", "/mse/")
                println("DEBUG MAXSTREAM — Forzato cambio url da msf a mse: $currentUrl")
            }

            // FASE 1: Gestione della pagina Uprot (/mse/)
            if (currentUrl.contains("uprot") || currentUrl.contains("/mse/")) {
                println("DEBUG MAXSTREAM — Richiesta alla pagina intermedia Uprot: $currentUrl")
                
                val res = app.get(
                    currentUrl,
                    headers = mapOf(
                        "User-Agent" to userAgent, 
                        "Referer" to (referer ?: mainUrl)
                    )
                )
                val htmlText = res.text

                // Cerchiamo l'URL del pulsante "Continue" che punta a maxstream.video/uprotem/...
                val matchTarget = """href=["'](https?://maxstream\.video/uprotem/[^"']+)["']""".toRegex().find(htmlText)
                
                if (matchTarget != null) {
                    currentUrl = matchTarget.groupValues[1]
                    println("DEBUG MAXSTREAM — Trovato link maxstream reale: $currentUrl")
                } else {
                    println("DEBUG MAXSTREAM — Pulsante Continue non trovato. Controlla se c'è blocco Cloudflare nell'HTML.")
                    return
                }
            }

            // FASE 2: Richiesta finale alla pagina di MaxStream per estrarre il video definitivo
            println("DEBUG MAXSTREAM — Richiesta finale al player: $currentUrl")
            val response = app.get(
                currentUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to url // Manteniamo l'URL di partenza originale come Referer richiesto dal server
                )
            )
            val html = response.text

            if (html.isEmpty()) return

            // Estrazione pulita del file video
            var videoUrl = ""
            if (html.contains("sources:")) {
                val segment = html.substringAfter("sources:")
                if (segment.contains("src")) {
                    videoUrl = segment.substringAfter("src").substringAfter("\"").substringBefore("\"")
                }
            } else if (html.contains("file:")) {
                videoUrl = html.substringAfter("file:").substringAfter("\"").substringBefore("\"")
            }

            videoUrl = videoUrl.trim().replace("\\", "")

            if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                println("DEBUG MAXSTREAM — Streaming estratto con successo: $videoUrl")
                
                val isM3u8 = videoUrl.contains(".m3u8") || videoUrl.contains("master.m3u8")

                val link = newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = currentUrl
                    this.quality = Qualities.Unknown.value
                }
                callback.invoke(link)
            } else {
                println("DEBUG MAXSTREAM — Nessun URL video trovato nella pagina finale del player.")
            }

        } catch (e: Exception) {
            println("DEBUG MAXSTREAM — Errore durante il processo: ${e.message}")
        }
    }
}
