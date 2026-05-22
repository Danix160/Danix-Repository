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
        
        // 1. Sanificazione
        if (targetLink.startsWith("//")) targetLink = "https:$targetLink"
        else if (!targetLink.startsWith("http")) targetLink = "https://uprot.net/" + targetLink.removePrefix("/")

        val baseHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Referer" to (referer ?: targetLink)
        )

        // 2. Gestione FILM (msf -> mse)
        if (!targetLink.contains("msfi")) {
            val filmUrl = if (targetLink.contains("msf")) targetLink.replace("msf", "mse") else targetLink
            val response = app.get(filmUrl, headers = baseHeaders)
            val link = findLinkInHtml(response.text)
            if (link != null) loadExtractor(link, url, subtitleCallback, callback)
        } 
        // 3. Gestione SERIE TV (msfi -> msei)
        else {
            Log.d("CB01_DEBUG", "Inizio Serie TV su: $targetLink")
            
            // GET iniziale per i Cookie
            val initResponse = app.get(targetLink, headers = baseHeaders)
            val doc = Jsoup.parse(initResponse.text)
            
            // Estrazione Captcha
            val imgCaptcha = doc.selectFirst("img[src*=captcha]")?.attr("src")
            val captchaNumber = imgCaptcha?.substringAfter("captcha=", "")?.substringBefore("&") ?: ""
            
            // LO SWITCH FONDAMENTALE: da msfi a msei
            val postUrl = targetLink.replace("/msfi/", "/msei/")
            Log.d("CB01_DEBUG", "Invio POST a: $postUrl con captcha: $captchaNumber")

            val postResponse = app.post(
                postUrl,
                cookies = initResponse.cookies,
                headers = baseHeaders,
                data = mapOf("captcha" to captchaNumber)
            )

            // Trova il link nel contenuto dopo la POST
            val finalLink = findLinkInHtml(postResponse.text)
            
            if (!finalLink.isNullOrEmpty()) {
                Log.d("CB01_DEBUG", "Link trovato, inoltro a estrattore: $finalLink")
                // Se è un link MaxStream, lo gestiamo tramite l'estrattore apposito
                if (finalLink.contains("maxstream") || finalLink.contains("watchfree") || finalLink.contains("maxf")) {
                    MaxStream().getUrl(finalLink, url, subtitleCallback, callback)
                } else {
                    loadExtractor(finalLink, url, subtitleCallback, callback)
                }
            } else {
                Log.e("CB01_DEBUG", "Errore: Bottone CONTINUE non trovato dopo la POST")
            }
        }
    }

    private fun findLinkInHtml(html: String): String? {
        val doc = Jsoup.parse(html)
        // Cerca il bottone, ma cerca anche link diretti se il bottone non c'è
        val element = doc.select("a").firstOrNull { 
            it.text().contains("CONTINUE", ignoreCase = true) || 
            it.attr("href").contains("maxf") || 
            it.attr("href").contains("watchfree") 
        }
        
        var href = element?.attr("abs:href")
        if (href.isNullOrEmpty()) return null
        
        // Risoluzione finale se necessario
        return href
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
