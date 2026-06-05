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

    // Header accurati emulati dal codice Python (fondamentali per evitare il 403)
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "it-IT,it;q=0.9",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Conversione iniziale da msf/msfi a mse (come fa Python)
        val target = normalize(url)
        println("DEBUG_UPROT: URL normalizzato di partenza -> $target")

        val dynamicHeaders = headers.toMutableMap()
        dynamicHeaders["Referer"] = url
        dynamicHeaders["Origin"] = url.split("/msf/")[0].split("/mse/")[0]

        // Eseguiamo la GET sulla pagina intermedia mse
        val res = app.get(target, headers = dynamicHeaders, allowRedirects = true)
        
        if (res.code == 403) {
            println("DEBUG_UPROT: Errore 403! Il server ha bloccato la richiesta (Cloudflare o Bot-check).")
            return
        }

        // Estraiamo il link finale di MaxStream seguendo i passaggi
        val finalUrl = getFinalMaxstreamLink(res.text, res.url, dynamicHeaders) ?: res.url
        println("DEBUG_UPROT: URL finale ottenuto -> $finalUrl")

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
        
        // 1. Cerca il tag 'a' con il testo CONTINUE (Case Insensitive)
        doc.select("a").forEach { tag ->
            val text = tag.text().uppercase().replace(" ", "")
            if (text.contains("CONTINUE")) {
                val href = tag.attr("href")
                if (href.isNotEmpty() && !href.startsWith("#")) {
                    return if (href.startsWith("/")) "https://maxstream.video$href" else href
                }
            }
        }

        // 2. Cerca l'eventuale FORM di sblocco (se presente)
        val form = doc.select("form").firstOrNull { formElement ->
            formElement.text().uppercase().replace(" ", "").contains("CONTINUE")
        }

        if (form != null) {
            val action = form.attr("action")
            if (action.isNotEmpty()) {
                return if (action.startsWith("/")) "https://maxstream.video$action" else action
            }
        }

        return null
    }

    private suspend fun getFinalMaxstreamLink(html: String, currentUrl: String, currentHeaders: Map<String, String>): String? {
        var currentHtml = html
        var redirectUrl = findLinkInHtml(currentHtml) ?: return null
        var time = 0

        // Ciclo analogo al "while 'uprots' in redirect" del codice Python, 
        // esteso per coprire i domini uprot e i link intermedi /uprotem/
        while (redirectUrl.contains("uprot") || redirectUrl.contains("uprots") || redirectUrl.contains("/uprotem/")) {
            time++
            if (time == 8) return null // Timeout per evitare loop infiniti

            println("DEBUG_UPROT: Salto intermedio $time -> $redirectUrl")

            // Eseguiamo una chiamata per seguire il redirect (Python usa .head, noi usiamo una GET veloce)
            val response = app.get(redirectUrl, headers = currentHeaders, allowRedirects = true)
            val nextUrl = response.url
            currentHtml = response.text 

            if (!nextUrl.contains("uprot") && !nextUrl.contains("uprots") && !nextUrl.contains("/uprotem/")) {
                redirectUrl = nextUrl
                break
            }

            val nextStep = findLinkInHtml(currentHtml)
            if (nextStep == null) {
                redirectUrl = nextUrl
                break 
            } else {
                redirectUrl = nextStep
            }
        }

        // Formattazione finale dell'URL (Traduzione della riga Python: 'https://maxstream.video/emvvv/' + ...split...)
        return when {
            redirectUrl.contains("watchfree/") -> {
                val parts = redirectUrl.split("watchfree/")[1].split("/")
                if (parts.size > 1) "https://maxstream.video/emvvv/${parts[1]}" else redirectUrl
            }
            redirectUrl.contains("uprotem/") -> {
                val part = redirectUrl.substringAfter("uprotem/").substringBefore("/")
                "https://maxstream.video/emvvv/$part"
            }
            else -> redirectUrl
        }
    }
}
