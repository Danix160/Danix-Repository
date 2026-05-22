package com.cb

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.app
import org.jsoup.Jsoup

class Uprot : ExtractorApi() {
    override val name = "Uprot"
    override val mainUrl = "https://uprot.net"
    override val requiresReferer = true

    private val TAG = "UprotExtractor"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "Avvio estrazione per URL: $url")

        val baseHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1"
        )

        // Eseguiamo una GET diretta, senza POST complicati
        val response = app.get(url, headers = baseHeaders, referer = referer)
        Log.d(TAG, "Risposta ricevuta, codice: ${response.code}")

        val continueLink = findLinkInHtml(response.text)
        
        if (!continueLink.isNullOrEmpty()) {
            val absoluteLink = if (continueLink.startsWith("http")) continueLink else "https://uprot.net$continueLink"
            Log.d(TAG, "Bottone CONTINUE trovato: $absoluteLink")
            
            val finalUrl = getFinalMaxstreamLink(absoluteLink, baseHeaders)
            
            if (!finalUrl.isNullOrEmpty()) {
                Log.d(TAG, "Link finale trovato: $finalUrl")
                loadExtractor(finalUrl, url, subtitleCallback, callback)
            } else {
                Log.e(TAG, "Errore: getFinalMaxstreamLink non ha restituito nulla")
            }
        } else {
            Log.e(TAG, "Errore: Nessun bottone CONTINUE trovato nell'HTML")
            Log.d(TAG, "HTML ricevuto: ${response.text.take(500)}...") // Debug HTML
        }
    }

    private fun findLinkInHtml(html: String): String? {
        val doc = Jsoup.parse(html)
        // Cerchiamo link che contengono "CONTINUE" nel testo
        val element = doc.select("a").firstOrNull { 
            it.text().contains("CONTINUE", ignoreCase = true) 
        }
        return element?.attr("href")
    }

    private suspend fun getFinalMaxstreamLink(html: String, headers: Map<String, String>): String? {
        // Se il link passato è già un video, ritornalo
        if (html.contains("maxstream.video")) return html

        val response = app.get(html, headers = headers)
        var redirectUrl = findLinkInHtml(response.text) ?: response.url
        
        var time = 0
        // Gestione loop di redirect
        while (redirectUrl.contains("uprots") && time < 10) {
            val headResponse = app.get(redirectUrl, headers = headers, allowRedirects = true)
            redirectUrl = headResponse.url
            time++
        }

        return if (redirectUrl.contains("watchfree/")) {
            val parts = redirectUrl.split("watchfree/").last().split("/")
            if (parts.isNotEmpty()) "https://maxstream.video/emvvv/${parts[0]}" else redirectUrl
        } else {
            redirectUrl
        }
    }
}
