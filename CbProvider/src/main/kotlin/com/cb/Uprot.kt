package com.cb

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.app
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
        var targetLink = url.trim()
        var maxStreamUrl: String? = null

        // Sanificazione immediata dello schema dell'URL in ingresso
        if (targetLink.startsWith("//")) {
            targetLink = "https:$targetLink"
        } else if (!targetLink.startsWith("http")) {
            targetLink = "https://uprot.net/" + targetLink.removePrefix("/")
        }

        val baseHeaders = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-GPC" to "1"
        )

        // Se è un link Serie TV (msfi), verifichiamo se serve normalizzarlo o se possiamo leggerlo direttamente
        if (targetLink.contains("msfi")) {
            val response = app.get(targetLink, headers = baseHeaders, referer = referer)
            if (response.code != 403) {
                maxStreamUrl = getFinalMaxstreamLink(response.text, baseHeaders)
            }
        } else {
            // Caso Film standard
            if (targetLink.contains("msf")) {
                targetLink = targetLink.replace("msf", "mse")
            }
            val response = app.get(targetLink, headers = baseHeaders, referer = referer)
            if (response.code != 403) {
                maxStreamUrl = getFinalMaxstreamLink(response.text, baseHeaders)
            }
        }

        // Se l'estrazione standard fallisce ma abbiamo comunque un redirect parziale o un watchfree
        if (maxStreamUrl.isNullOrEmpty()) {
            maxStreamUrl = targetLink
        }

        // Se abbiamo trovato o ripulito il link finale di MaxStream, lo carichiamo nell'estrattore
        if (!maxStreamUrl.isNullOrEmpty() && (maxStreamUrl.contains("maxstream") || maxStreamUrl.contains("watchfree") || maxStreamUrl.contains("uprots"))) {
            if (!maxStreamUrl.startsWith("http")) {
                maxStreamUrl = "https://" + maxStreamUrl.removePrefix("//")
            }
            // Forza la conversione nel formato nativo incorporato se rimangono residui di watchfree
            if (maxStreamUrl.contains("watchfree/")) {
                val parts = maxStreamUrl.split("watchfree/")[1].split("/")
                if (parts.size > 1) {
                    maxStreamUrl = "https://maxstream.video/emvvv/${parts[1]}"
                }
            }
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

        // Insegue la catena di redirect dei domini uprots/uprot
        while (redirectUrl.contains("uprots") || redirectUrl.contains("uprot.net")) {
            if (!redirectUrl.startsWith("http")) {
                redirectUrl = "https://" + redirectUrl.removePrefix("//")
            }
            
            val headResponse = app.get(redirectUrl, headers = headers, allowRedirects = true)
            redirectUrl = headResponse.url
            
            // Se uscendo dal redirect abbiamo già beccato watchfree o maxstream, ci fermiamo subito
            if (redirectUrl.contains("watchfree") || redirectUrl.contains("maxstream")) {
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
