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
            "Upgrade-Insecure-Requests" to "1",
            "Sec-GPC" to "1"
        )

        // =========================================================================
        // 2. Gestione Condizionale: FILM (mse) vs SERIE TV (msfi) con Auto-Captcha
        // =========================================================================
        if (!targetLink.contains("msfi")) {
            // Gestione FILM: Sostituzione lineare classica
            if (targetLink.contains("msf")) {
                targetLink = targetLink.replace("msf", "mse")
            }

            Log.d("CB01_DEBUG", "Uprot Film - Richiesta GET standard su: $targetLink")
            val response = app.get(targetLink, headers = baseHeaders, referer = referer)
            if (response.code != 403) {
                maxStreamUrl = findLinkInHtml(response.text)
            }
        } else {
            // Gestione SERIE TV: Exploit del Captcha numerico in chiaro
            if (targetLink.contains("msei")) {
                targetLink = targetLink.replace("msei", "msfi") // Forza l'endpoint corretto per la POST
            }

            Log.d("CB01_DEBUG", "Uprot Serie TV - Avvio bypass del Captcha su: $targetLink")
            
            // Inizializza la sessione per catturare i cookie e l'HTML del modulo
            val initResponse = app.post(targetLink, headers = baseHeaders, referer = targetLink)
            val cookies = initResponse.cookies

            val doc = Jsoup.parse(initResponse.text)
            val imgCaptcha = doc.selectFirst("img")?.attr("src")
            
            // Estrae i numeri del captcha direttamente dall'URL dell'immagine di verifica
            val captchaNumber = imgCaptcha?.substringAfter("captcha=", "")?.substringBefore("&") ?: ""
            Log.d("CB01_DEBUG", "Captcha Uprot intercettato automaticamente: $captchaNumber")

            // Invia il modulo risolto tramite POST
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

        // Failsafe: Se l'estrazione fallisce o restituisce un link vuoto, interrompiamo 
        // l'esecuzione senza passare l'URL madre a loadExtractor, evitando il caricamento infinito.
        if (maxStreamUrl.isNullOrEmpty() || maxStreamUrl == targetLink) {
            Log.e("CB01_DEBUG", "Estrazione interrotta: Nessun link valido trovato su Uprot")
            return
        }

        // =========================================================================
        // 3. Smistamento Finale del Link Estratto
        // =========================================================================
        Log.d("CB01_DEBUG", "Link sbloccato con successo: $maxStreamUrl")
        
        if (maxStreamUrl!!.contains("watchfree") || maxStreamUrl!!.contains("maxstream") || maxStreamUrl!!.contains("maxf")) {
            MaxStream().getUrl(maxStreamUrl!!, url, subtitleCallback, callback)
        } else {
            loadExtractor(maxStreamUrl!!, url, subtitleCallback, callback)
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

        // Insegue la catena finché siamo nei server di transito di uprot
        while (redirectUrl.contains("uprots") || redirectUrl.contains("uprot.net")) {
            Log.d("CB01_DEBUG", "Inseguendo redirect intermedio (${time + 1}): $redirectUrl")
            
            val headResponse = app.get(redirectUrl, headers = headers, allowRedirects = true)
            redirectUrl = headResponse.url
            
            time++
            if (time == 5) break // Protezione anti-loop ridotta a 5 passaggi massimi
        }

        // Formatta correttamente la stringa finale se passa da watchfree
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
