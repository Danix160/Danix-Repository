package com.onlineserietv

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
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
        dynamicHeaders["Referer"] = referer ?: url
        dynamicHeaders["Origin"] = target.substringBefore("/mse/").substringBefore("/msf/")

        // 1. Primo tentativo HTTP standard
        var res = app.get(target, headers = dynamicHeaders, allowRedirects = true)
        var htmlText = res.text

        // 2. Se c'è il blocco Cloudflare o la challenge Turnstile
        if (res.code == 403 || res.code == 503 || htmlText.contains("cloudflare") || htmlText.contains("challenge-platform")) {
            println("DEBUG_UPROT: Rilevato blocco Cloudflare (${res.code}). Avvio WebViewResolver...")

            try {
                val webViewResponse = app.get(
                    target,
                    headers = dynamicHeaders,
                    interceptor = WebViewResolver(
                        interceptUrl = Regex("""https?://(?:www\.)?(?:maxstream\.video|uprot\.net/(?:uprotem|mse|msf)).*"""),
                        additionalUrls = listOf(Regex(""".*maxstream\.video.*"""))
                    )
                )

                htmlText = webViewResponse.text
                res = webViewResponse
                println("DEBUG_UPROT: WebViewResolver completato. Status Code: ${webViewResponse.code}")

                if (webViewResponse.code in listOf(403, 503) || htmlText.contains("challenge-platform")) {
                    println("DEBUG_UPROT: Cloudflare non superato.")
                    return
                }
            } catch (e: Exception) {
                println("DEBUG_UPROT: Errore WebViewResolver: ${e.message}")
                return
            }
        }

        // 3. Estrazione del link finale
        val finalUrl = getFinalMaxstreamLink(htmlText, dynamicHeaders)
        println("DEBUG_UPROT: URL finale ottenuto -> $finalUrl")

        // 4. Carica l'extractor evitando ricorsioni
        if (finalUrl != null && !finalUrl.literaryEquals(target) && !finalUrl.literaryEquals(url)) {
            val refererToUse = if (finalUrl.contains("maxstream")) target else url
            loadExtractor(finalUrl, refererToUse, subtitleCallback, callback)
        } else {
            println("DEBUG_UPROT: Impossibile estrarre un link Maxstream valido.")
        }
    }

    private fun String?.literaryEquals(other: String?): Boolean {
        if (this == null || other == null) return this == other
        return this.trimEnd('/') == other.trimEnd('/')
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

        // A. Cerca link diretti in <a>
        doc.select("a[href]").forEach { tag ->
            val href = tag.attr("href")
            val text = tag.text().uppercase().replace(" ", "")
            if (text.contains("CONTINUE") || text.contains("PROCEED") || text.contains("AVANTI") || href.contains("maxstream")) {
                if (href.isNotEmpty() && !href.startsWith("#")) {
                    return formatUrl(href)
                }
            }
        }

        // B. Cerca in Form POST
        val form = doc.select("form").firstOrNull()
        if (form != null) {
            val action = form.attr("action")
            if (action.isNotEmpty()) {
                return formatUrl(action)
            }
        }

        // C. Extraction da Script Regex
        val jsRegex = Regex("""(?:window\.location\.href|location\.assign)\s*=\s*["']([^"']+)["']""")
        val match = jsRegex.find(html)
        if (match != null) {
            return formatUrl(match.groupValues[1])
        }

        return null
    }

    private fun formatUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "https://maxstream.video$url"
            else -> url
        }
    }

    private suspend fun getFinalMaxstreamLink(html: String, currentHeaders: Map<String, String>): String? {
        var currentHtml = html
        var redirectUrl = findLinkInHtml(currentHtml) ?: return null
        var time = 0

        while (redirectUrl.contains("uprot") || redirectUrl.contains("uprots") || redirectUrl.contains("/uprotem/")) {
            time++
            if (time >= 5) break

            println("DEBUG_UPROT: Salto intermedio $time -> $redirectUrl")

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
