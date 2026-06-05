package com.onlineserietv

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.app
import org.jsoup.Jsoup // 1️⃣ IMPORTATO JSOUP PER IL PARSING HTML

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

        // Carichiamo la prima pagina (allowRedirects = true per seguire eventuali passaggi iniziali)
        val res = app.get(target, headers = headers, referer = referer ?: mainUrl, allowRedirects = true)
        
        // 2️⃣ SE C'È IL TASTO CONTINUE, RISOLVIAMO I PASSAGGI INTERMEDI
        // Passiamo l'HTML iniziale alla nostra funzione di sblocco
        val finalUrl = getFinalMaxstreamLink(res.text, headers) ?: res.url

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
        
        // 1. Cerca prima se c'è un vero tag 'a' con il testo CONTINUE
        doc.select("a").forEach { tag ->
            val text = tag.text().uppercase()
            if (text.contains("C O N T I N U E") || text.contains("CONTINUE")) {
                val href = tag.attr("href")
                if (href.isNotEmpty() && !href.startsWith("#")) return href
            }
        }

        // 2. Se non c'è il tag 'a', quasi sicuramente è un FORM. Cerchiamo il form di sblocco.
        val form = doc.select("form").firstOrNull { formElement ->
            formElement.text().uppercase().contains("CONTINUE")
        }

        if (form != null) {
            val action = form.attr("action")
            // Se l'action è vuota, spesso significa che il form reinvia alla stessa pagina corrente
            return if (action.isNotEmpty()) action else null
        }

        return null
    }

    private suspend fun getFinalMaxstreamLink(html: String, headers: Map<String, String>): String? {
        var currentHtml = html
        var redirectUrl = findLinkInHtml(currentHtml) ?: return null
        var time = 0

        // Continuiamo finché l'URL contiene la protezione di uprot
        while (redirectUrl.contains("uprot")) {
            time++
            if (time == 10) return null

            val response = app.get(redirectUrl, headers = headers, allowRedirects = true)
            
            val nextUrl = response.url
            currentHtml = response.text 

            if (!nextUrl.contains("uprot")) {
                redirectUrl = nextUrl
                break
            }

            redirectUrl = findLinkInHtml(currentHtml) ?: nextUrl
        }

        // Parsing finale per estrarre l'ID di maxstream
        return if (redirectUrl.contains("watchfree/")) {
            val parts = redirectUrl.split("watchfree/")[1].split("/")
            if (parts.size > 1) {
                "https://maxstream.video/emvvv/${parts[1]}"
            } else {
                redirectUrl
            }
        } else {
            redirectUrl
        }
    }
}
