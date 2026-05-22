package com.cb

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.app
import org.jsoup.Jsoup
import android.util.Log

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
        var targetLink = url.trim()
        var maxStreamUrl: String? = null

        // 1. Sanificazione iniziale dell'URL ricevuto da StayOnline
        if (targetLink.startsWith("//")) {
            targetLink = "https:$targetLink"
        } else if (!targetLink.startsWith("http")) {
            targetLink = "https://uprot.net/" + targetLink.removePrefix("/")
        }

        val baseHeaders = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1"
        )

        // =========================================================================
        // 2. Chiamata a Uprot con Sanificazione Universale Antiblocco
        // =========================================================================
        // Riscrive gli endpoint per bypassare Cloudflare e rispondere all'istante
        if (targetLink.contains("msfi")) {
            targetLink = targetLink.replace("msfi", "msei") // Sostituisce msfi con msei per le serie TV
        } else if (targetLink.contains("msf")) {
            targetLink = targetLink.replace("msf", "mse")   // Sostituisce msf con mse per i film
        }

        // Esegue la richiesta sull'endpoint libero che non blocca la connessione
        val response = app.get(targetLink, headers = baseHeaders, referer = referer)
        if (response.code != 403) {
            maxStreamUrl = getFinalMaxstreamLink(response.text, baseHeaders)
        }

        // Fallback: se il parsing del pulsante fallisce, usiamo l'URL di transito
        if (maxStreamUrl.isNullOrEmpty()) {
            maxStreamUrl = targetLink
        }

        // =========================================================================
        // Gestione e Smistamento del link finale ottenuto
        // =========================================================================
        if (maxStreamUrl.contains("watchfree") || maxStreamUrl.contains("maxstream") || maxStreamUrl.contains("maxf")) {
            // Forza il passaggio diretto all'estrattore MaxStream che gestisce i domini specchio
            MaxStream().getUrl(maxStreamUrl, url, subtitleCallback, callback)
        } else {
            // Altrimenti (es. se la catena ha restituito Mixdrop), si affida al core di Cloudstream
            loadExtractor(maxStreamUrl, url, subtitleCallback, callback)
        }
    }

    private fun findLinkInHtml(html: String): String? {
        val doc = Jsoup.parse(html)
        doc.select("a").forEach { tag ->
            val text = tag.text().uppercase()
            if (text.contains("C O N T I N U E") || text.contains("CONTINUE")) {
                var href = tag.attr("href").trim()
                if (href.isBlank()) return@forEach
                
                if (href.startsWith("//")) {
                    href = "https:$href"
                } else if (!href.startsWith("http")) {
                    href = "https://uprot.net/" + href.removePrefix("/")
                }
                return href
            }
        }
        return null
    }

    private suspend fun getFinalMaxstreamLink(html: String, headers: Map<String, String>): String? {
        var redirectUrl = findLinkInHtml(html) ?: return null
        var time = 0

        // SE IL LINK CONTIENE GIÀ MAXSTREAM/WATCHFREE, ABBIAMO FINITO! Restituiscilo subito ed esci.
        if (redirectUrl.contains("maxstream.video") || redirectUrl.contains("watchfree") || redirectUrl.contains("maxf")) {
            return redirectUrl
        }

        // Insegue la catena di redirect SOLO se siamo ancora sui domini di transito uprots/uprot.net originali
        while (redirectUrl.contains("uprots") || redirectUrl.contains("uprot.net")) {
            Log.d("CB01_DEBUG", "Inseguendo redirect (Tentativo ${time + 1}): $redirectUrl")

            if (!redirectUrl.startsWith("http")) {
                redirectUrl = "https://" + redirectUrl.removePrefix("//")
            }
            
            val headResponse = app.get(redirectUrl, headers = headers, allowRedirects = true)
            redirectUrl = headResponse.url
            
            // Interrompiamo se intercettiamo qualsiasi variante finale (incluso maxf e watchfree)
            if (redirectUrl.contains("watchfree") || redirectUrl.contains("maxstream") || redirectUrl.contains("maxf")) {
                break
            }
            
            // Se rimaniamo bloccati sulla stessa pagina, proviamo a cercare un nuovo pulsante CONTINUE nell'HTML intermedio
            val nextLink = findLinkInHtml(headResponse.text)
            if (nextLink != null && nextLink != redirectUrl) {
                redirectUrl = nextLink
            }

            time++
            if (time == 10) return null
        }

        return redirectUrl
    }
}
