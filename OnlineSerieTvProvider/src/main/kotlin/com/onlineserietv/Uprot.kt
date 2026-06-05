package com.onlineserietv

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver // 1️⃣ IMPORTATO WEBVIEW RESOLVER
import okhttp3.Request
import org.jsoup.Jsoup

class Uprot : ExtractorApi() {
    override val name = "Uprot"
    override val mainUrl = "https://uprot.net"
    override val requiresReferer = true

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
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
        val target = normalize(url)
        println("DEBUG_UPROT: URL normalizzato -> $target")

        val dynamicHeaders = baseHeaders.toMutableMap()
        dynamicHeaders["Referer"] = url
        dynamicHeaders["Origin"] = url.split("/msf/")[0].split("/mse/")[0]

        // Primo tentativo standard
        var res = app.get(target, headers = dynamicHeaders, allowRedirects = true)
        var htmlText = res.text

        // 2️⃣ SE C'È BLOCCO 403, SBLOCCHIAMO CON LA WEBVIEW DI CLOUDSTREAM
        if (res.code == 403 || htmlText.contains("cloudflare") || htmlText.contains("challenge-platform")) {
            println("DEBUG_UPROT: Rilevato blocco 403 o Cloudflare. Avvio WebViewResolver...")
            
            try {
                // Intercettiamo la richiesta tramite WebView simulando il browser di sistema
                val webViewResponse = app.get(
                    target,
                    headers = dynamicHeaders,
                    interceptor = WebViewResolver(dynamicHeaders)
                )
                htmlText = webViewResponse.text
                println("DEBUG_UPROT: WebViewResolver completato. Status Code: ${webViewResponse.code}")
            } catch (e: Exception) {
                println("DEBUG_UPROT: Errore durante l'uso di WebViewResolver: ${e.message}")
                return
            }
        }

        // Procediamo con l'estrazione del link dal DOM sbloccato
        val finalUrl = getFinalMaxstreamLink(htmlText, dynamicHeaders) ?: res.url
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
        
        doc.select("a").forEach { tag ->
            val text = tag.text().uppercase().replace(" ", "")
            if (text.contains("CONTINUE")) {
                val href = tag.attr("href")
                if (href.isNotEmpty() && !href.startsWith("#")) {
                    return if (href.startsWith("/")) "https://maxstream.video$href" else href
                }
            }
        }

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

    private suspend fun getFinalMaxstreamLink(html: String, currentHeaders: Map<String, String>): String? {
        var currentHtml = html
        var redirectUrl = findLinkInHtml(currentHtml) ?: return null
        var time = 0

        while (redirectUrl.contains("uprot") || redirectUrl.contains("uprots") || redirectUrl.contains("/uprotem/")) {
            time++
            if (time == 8) return null

            println("DEBUG_UPROT: Salto intermedio $time -> $redirectUrl")

            // Usiamo l'intercettore anche nei salti intermedi se necessario
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
