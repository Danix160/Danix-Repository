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

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): List<ExtractorLink> {

        var targetLink = url
        var maxStreamUrl: String? = null

        // Mappiamo gli header base definiti nel tuo script Python
        val baseHeaders = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-GPC" to "1"
        )

        if (!targetLink.contains("msfi")) {
            // CASO 1: Non contiene msfi
            if (targetLink.contains("msf")) {
                targetLink = targetLink.replace("msf", "mse")
            }

            val response = app.get(targetLink, headers = baseHeaders, referer = referer)
            if (response.code != 403) {
                maxStreamUrl = findLinkInHtml(response.text)
            }
        } else {
            // CASO 2: Contiene msfi (Gestione Captcha / Sessione in tempo reale)
            if (targetLink.contains("mse")) {
                targetLink = targetLink.replace("mse", "msf")
            }

            // 1. Inizializziamo la sessione per ottenere i primi Cookie e l'immagine captcha
            val initResponse = app.post(targetLink, headers = baseHeaders, referer = targetLink)
            val cookies = initResponse.cookies

            val doc = Jsoup.parse(initResponse.text)
            val imgCaptcha = doc.selectFirst("img")?.attr("src")

            // Estraiamo il valore numerico dell'immagine se presente nell'URL (es: ?id=1234) 
            // o usiamo una stringa vuota se l'eval non è agganciato
            val captchaNumber = imgCaptcha?.substringAfter("captcha=", "")?.substringBefore("&") ?: ""

            // 2. Inviamo il token captcha con i cookie di sessione ottenuti
            val postResponse = app.post(
                targetLink,
                cookies = cookies,
                headers = baseHeaders.plus("Content-Type" to "application/x-www-form-urlencoded"),
                data = mapOf("captcha" to captchaNumber),
                referer = targetLink
            )

            if (postResponse.code != 403) {
                maxStreamUrl = getFinalMaxstreamLink(postResponse.text, baseHeaders)
            }
        }

        // Se abbiamo trovato l'URL valido di MaxStream, lo passiamo al motore di Cloudstream
        if (!maxStreamUrl.isNullOrEmpty()) {
            loadExtractor(maxStreamUrl, url, subtitleCallback, callback)
        }

        return emptyList()
    }

    // Cerca il tag <a> con il testo CONTINUE (Case Insensitive)
    private fun findLinkInHtml(html: String): String? {
        val doc = Jsoup.parse(html)
        doc.select("a").forEach { tag ->
            val text = tag.text().uppercase()
            if (text.contains("C O N T I N U E") || text.contains("CONTINUE")) {
                return tag.attr("href")
            }
        }
        return null
    }

    // Risolve i redirect continui (Fino a un massimo di 10 passaggi come da script Python)
    private suspend fun getFinalMaxstreamLink(html: String, headers: Map<String, String>): String? {
        var redirectUrl = findLinkInHtml(html) ?: return null
        var time = 0

        while (redirectUrl.contains("uprots")) {
            // Eseguiamo una chiamata per seguire i redirect automatici di uprots
            val headResponse = app.get(redirectUrl, headers = headers, allowRedirects = true)
            redirectUrl = headResponse.url
            time++
            if (time == 10) return null
        }

        // Se l'URL finale contiene 'watchfree/', effettuiamo lo split per ricostruire il link nativo di MaxStream
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
