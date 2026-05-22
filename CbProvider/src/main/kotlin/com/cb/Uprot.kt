package com.cb

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
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

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1"
        )

        try {

            // STEP 1: Apriamo la pagina Uprot
            val response = app.get(
                url,
                headers = headers,
                referer = referer
            )

            Log.d(TAG, "Pagina Uprot caricata: ${response.url}")

            // STEP 2: Cerchiamo il bottone CONTINUE
            val continueLink = findContinueLink(response.text)

            if (continueLink.isNullOrBlank()) {
                Log.e(TAG, "CONTINUE link non trovato")
                Log.d(TAG, response.text.take(1000))
                return
            }

            val absoluteContinue = if (continueLink.startsWith("http")) {
                continueLink
            } else {
                "https://uprot.net$continueLink"
            }

            Log.d(TAG, "Continue link trovato: $absoluteContinue")

            // STEP 3: Risoluzione redirect finali
            val finalUrl = resolveFinalLink(
                absoluteContinue,
                headers
            )

            if (finalUrl.isNullOrBlank()) {
                Log.e(TAG, "Impossibile ottenere URL finale")
                return
            }

            Log.d(TAG, "URL finale ottenuto: $finalUrl")

            // STEP 4: Passiamo a MaxStream / altri extractor
            loadExtractor(
                finalUrl,
                url,
                subtitleCallback,
                callback
            )

        } catch (e: Exception) {

            Log.e(TAG, "Errore estrazione: ${e.message}", e)

        }
    }

    private fun findContinueLink(html: String): String? {

        val doc = Jsoup.parse(html)

        // Cerchiamo tutti gli anchor
        val anchors = doc.select("a[href]")

        // Priorità al bottone CONTINUE
        val continueAnchor = anchors.firstOrNull {
            it.text().contains("CONTINUE", ignoreCase = true)
        }

        if (continueAnchor != null) {
            return continueAnchor.attr("href")
        }

        // Fallback: cerchiamo direttamente watchfree
        val watchfreeAnchor = anchors.firstOrNull {
            val href = it.attr("href")
            href.contains("/watchfree/")
        }

        return watchfreeAnchor?.attr("href")
    }

    private suspend fun resolveFinalLink(
        startUrl: String,
        headers: Map<String, String>
    ): String? {

        var currentUrl = startUrl
        var attempts = 0

        while (attempts < 10) {

            Log.d(TAG, "Redirect step [$attempts]: $currentUrl")

            // Se siamo già su MaxStream/watchfree
            if (
                currentUrl.contains("/watchfree/") ||
                currentUrl.contains("/emvvv/")
            ) {
                return currentUrl
            }

            try {

                val response = app.get(
                    currentUrl,
                    headers = headers,
                    allowRedirects = true
                )

                val finalResponseUrl = response.url

                Log.d(TAG, "Response URL: $finalResponseUrl")

                // Caso già risolto
                if (
                    finalResponseUrl.contains("/watchfree/") ||
                    finalResponseUrl.contains("/emvvv/")
                ) {
                    return finalResponseUrl
                }

                // Cerchiamo eventuali link HTML
                val htmlLink = findContinueLink(response.text)

                if (!htmlLink.isNullOrBlank()) {

                    currentUrl = if (htmlLink.startsWith("http")) {
                        htmlLink
                    } else {
                        "https://uprot.net$htmlLink"
                    }

                } else {

                    currentUrl = finalResponseUrl

                }

            } catch (e: Exception) {

                Log.e(TAG, "Errore redirect: ${e.message}")

                return null
            }

            attempts++
        }

        return currentUrl
    }
}
