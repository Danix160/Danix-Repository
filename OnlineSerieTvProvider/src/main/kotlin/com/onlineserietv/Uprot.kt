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
    val form = doc.select("form").firstOrNull { form ->
        form.text().uppercase().contains("CONTINUE")
    }

    if (form != null) {
        // Ritorna l'action del form (dove inviare i dati)
        return form.attr("action")
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

        // Eseguiamo la richiesta per superare lo step. 
        // NOTA: Se era un form, idealmente andrebbe fatta una richiesta POST con i relativi input.
        // Se basta una GET, carichiamo la nuova pagina:
        val response = app.get(redirectUrl, headers = headers, allowRedirects = true)
        
        // AGGIORNAMENTO CRUCIALE: prendiamo il NUOVO URL e il NUOVO HTML della pagina
        val nextUrl = response.url
        currentHtml = response.text // Prende il codice della nuova pagina appena caricata

        // Se l'URL è cambiato e siamo usciti da uprot, interrompiamo il ciclo
        if (!nextUrl.contains("uprot")) {
            redirectUrl = nextUrl
            break
        }

        // Altrimenti, analizziamo il nuovo HTML per cercare il PROSSIMO tasto continue
        redirectUrl = findLinkInHtml(currentHtml) ?: nextUrl
    }

    // parsing finale per estrarre l'ID di maxstream
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
