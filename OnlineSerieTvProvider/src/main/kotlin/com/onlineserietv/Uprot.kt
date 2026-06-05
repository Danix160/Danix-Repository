package com.onlineserietv

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.app
import org.jsoup.Jsoup

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
        val target = normalize(url)

        // Carichiamo la prima pagina di Uprot (/mse/)
        val res = app.get(target, headers = headers, referer = referer ?: mainUrl, allowRedirects = true)
        
        // Risolviamo i passaggi intermedi per ottenere il link del player finale
        val finalUrl = getFinalMaxstreamLink(res.text, headers) ?: res.url

        println("DEBUG_UPROT: URL finale passato a loadExtractor -> $finalUrl")

        if (finalUrl.contains("maxstream")) {
            loadExtractor(finalUrl, target, subtitleCallback, callback)
        } else {
            loadExtractor(finalUrl, url, subtitleCallback, callback)
        }
    }

    private fun normalize(url: String): String {
        return when {
            url.contains("/msf/") -> url.replace("/msf/", "/mse/")
            url.contains("/msfi/") -> url.replace("/msfi/", "/mse/")
            else -> url
        }
    }

    private fun findLinkInHtml(html: String): String? {
        val doc = Jsoup.parse(html)
        
        // 1. Cerca il tag 'a' con il testo CONTINUE
        doc.select("a").forEach { tag ->
            val text = tag.text().uppercase()
            if (text.contains("C O N T I N U E") || text.contains("CONTINUE")) {
                val href = tag.attr("href")
                if (href.isNotEmpty() && !href.startsWith("#")) {
                    // Gestione link relativi: se inizia con /, aggiungiamo il dominio di MaxStream
                    return if (href.startsWith("/")) "https://maxstream.video$href" else href
                }
            }
        }

        // 2. Cerca il form di sblocco
        val form = doc.select("form").firstOrNull { formElement ->
            formElement.text().uppercase().contains("CONTINUE")
        }

        if (form != null) {
            val action = form.attr("action")
            if (action.isNotEmpty()) {
                return if (action.startsWith("/")) "https://maxstream.video$action" else action
            }
        }

        return null
    }

    private suspend fun getFinalMaxstreamLink(html: String, headers: Map<String, String>): String? {
        var currentHtml = html
        var redirectUrl = findLinkInHtml(currentHtml) ?: return null
        var time = 0

        // MODIFICA CRUCIALE: Il ciclo deve continuare finché l'URL NON contiene l'endpoint finale dello streaming video (es. /emvvv/ o /video/)
        // Se contiene ancora uprot o l'endpoint intermedio /uprotem/, dobbiamo continuare a navigare
        while (redirectUrl.contains("uprot") || redirectUrl.contains("/uprotem/")) {
            time++
            if (time == 5) return null 

            println("DEBUG_UPROT: Tentativo intermedio $time su URL: $redirectUrl")

            val response = app.get(redirectUrl, headers = headers, allowRedirects = true)
            val nextUrl = response.url
            currentHtml = response.text 

            // Se siamo usciti da uprot e siamo su maxstream pronti per l'estrazione, verifichiamo la struttura
            if (!nextUrl.contains("uprot") && !nextUrl.contains("/uprotem/")) {
                redirectUrl = nextUrl
                break
            }

            val nextStep = findLinkInHtml(currentHtml)
            if (nextStep == null) {
                println("DEBUG_UPROT: Tasto CONTINUE non trovato nell'HTML al tentativo $time. Bot-check o pagina finale raggiunta.")
                redirectUrl = nextUrl
                break 
            } else {
                redirectUrl = nextStep
            }
        }

        // Parsing e normalizzazione finale dell'URL per MaxStream
        return when {
            redirectUrl.contains("watchfree/") -> {
                val parts = redirectUrl.split("watchfree/")[1].split("/")
                if (parts.size > 1) "https://maxstream.video/emvvv/${parts[1]}" else redirectUrl
            }
            redirectUrl.contains("uprotem/") -> {
                // Se il bottone finale era un /uprotem/, lo convertiamo nel formato compatibile con l'estrattore MaxStream
                val part = redirectUrl.substringAfter("uprotem/").substringBefore("/")
                "https://maxstream.video/emvvv/$part"
            }
            else -> redirectUrl
        }
    }
}
