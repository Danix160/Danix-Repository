package com.onlineserietv

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

class Uprot : ExtractorApi() {
    override val name = "Uprot"
    override val mainUrl = "https://uprot.net"
    override val requiresReferer = true

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "it-IT,it;q=0.9",
        "Connection" to "keep-alive"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val target = normalizeUrl(url)
        println("DEBUG_UPROT: Target Normalizzato -> $target")

        val dynamicHeaders = baseHeaders.toMutableMap()
        dynamicHeaders["Referer"] = referer ?: url

        var htmlText: String
        var currentUrl = target

        // 1. Primo tentativo HTTP silente
        try {
            val response = app.get(target, headers = dynamicHeaders, allowRedirects = true)
            htmlText = response.text
            currentUrl = response.url
        } catch (e: Exception) {
            println("DEBUG_UPROT: Errore GET iniziale: ${e.message}")
            return
        }

        // 2. Controllo se è presente un CAPTCHA visuale o protezione Cloudflare
        val hasProtection = htmlText.contains("challenge-platform") || 
                            htmlText.contains("cf-turnstile") || 
                            htmlText.contains("h-captcha") || 
                            htmlText.contains("g-recaptcha") || 
                            htmlText.contains("captcha", ignoreCase = true)

        if (hasProtection) {
            println("DEBUG_UPROT: Rilevato CAPTCHA/Cloudflare. Avvio WebViewResolver...")
            try {
                // Apre la WebView per permettere all'utente di selezionare le immagini/risolvere il CAPTCHA
                val webViewResponse = app.get(
                    target,
                    headers = dynamicHeaders,
                    interceptor = WebViewResolver(
                        // Intercetta quando si approda sulla pagina sbloccata o su maxstream
                        interceptUrl = Regex("""https?://(?:www\.)?(?:maxstream\.video|uprot\.net/(?:uprotem|mse|msf)).*"""),
                        additionalUrls = listOf(Regex(""".*maxstream\.video.*"""))
                    )
                )

                htmlText = webViewResponse.text
                currentUrl = webViewResponse.url
                println("DEBUG_UPROT: WebView completata. URL finale -> $currentUrl")
            } catch (e: Exception) {
                println("DEBUG_UPROT: Errore o annullamento WebView: ${e.message}")
                return
            }
        }

        // 3. Estrazione e risoluzione del link finale
        val finalUrl = resolveRedirects(htmlText, currentUrl, dynamicHeaders)
        println("DEBUG_UPROT: URL Finale per Extractor -> $finalUrl")

        // 4. Caricamento dell'extractor evitando loop ricorsivi
        if (finalUrl != null && !finalUrl.literaryEquals(target) && !finalUrl.literaryEquals(url)) {
            val refererToUse = if (finalUrl.contains("maxstream")) target else url
            loadExtractor(finalUrl, refererToUse, subtitleCallback, callback)
        } else {
            println("DEBUG_UPROT: Impossibile trovare un link Maxstream valido.")
        }
    }

    private fun normalizeUrl(url: String): String {
        return when {
            url.contains("/msf/") -> url.replace("/msf/", "/mse/")
            url.contains("/msfi/") -> url.replace("/msfi/", "/mse/")
            else -> url
        }
    }

    private fun fixUrl(url: String, domain: String = "https://uprot.net"): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$domain$url"
            !url.startsWith("http") -> "$domain/$url"
            else -> url
        }
    }

    private fun String?.literaryEquals(other: String?): Boolean {
        if (this == null || other == null) return this == other
        return this.trimEnd('/') == other.trimEnd('/')
    }

    private fun extractUrlFromHtml(html: String): String? {
        val doc = Jsoup.parse(html)

        // A. Cerca bottone "#buttok" o link con testo "CONTINUE" / "AVANTI"
        val buttok = doc.selectFirst("#buttok")?.parent()?.attr("href")
        if (!buttok.isNullOrEmpty()) return fixUrl(buttok)

        doc.select("a[href]").forEach { tag ->
            val href = tag.attr("href")
            val text = tag.text().uppercase().replace(" ", "")
            if (text.contains("CONTINUE") || text.contains("PROCEED") || text.contains("AVANTI") || href.contains("maxstream")) {
                if (href.isNotEmpty() && !href.startsWith("#")) {
                    return fixUrl(href)
                }
            }
        }

        // B. Cerca Form Action
        val formAction = doc.selectFirst("form")?.attr("action")
        if (!formAction.isNullOrEmpty()) {
            return fixUrl(formAction)
        }

        // C. Cerca redirect JavaScript
        val jsRegex = Regex("""(?:window\.location\.href|location\.assign)\s*=\s*["']([^"']+)["']""")
        val match = jsRegex.find(html)
        if (match != null) {
            return fixUrl(match.groupValues[1])
        }

        return null
    }

    private suspend fun resolveRedirects(
        initialHtml: String,
        initialUrl: String,
        headers: Map<String, String>
    ): String? {
        var currentHtml = initialHtml
        var currentUrl = initialUrl
        var redirectUrl = extractUrlFromHtml(currentHtml) ?: currentUrl
        var depth = 0

        while ((redirectUrl.contains("uprot") || redirectUrl.contains("uprots") || redirectUrl.contains("/uprotem/")) && depth < 5) {
            depth++
            println("DEBUG_UPROT: Salto $depth -> $redirectUrl")

            try {
                val res = app.get(redirectUrl, headers = headers, allowRedirects = true)
                currentUrl = res.url
                currentHtml = res.text

                if (!currentUrl.contains("uprot") && !currentUrl.contains("uprots")) {
                    redirectUrl = currentUrl
                    break
                }

                val nextStep = extractUrlFromHtml(currentHtml)
                if (nextStep == null || nextStep.literaryEquals(redirectUrl)) {
                    redirectUrl = currentUrl
                    break
                } else {
                    redirectUrl = nextStep
                }
            } catch (e: Exception) {
                println("DEBUG_UPROT: Errore durante il salto $depth: ${e.message}")
                break
            }
        }

        // Mappatura finale per gli embed di Maxstream
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
