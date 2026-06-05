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

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "it-IT,it;q=0.9",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "https://uprot.net/"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var target = normalize(url)

        // 1️⃣ Primo GET — ottieni cookie di sessione
        val init = app.get(target, headers = headers, referer = mainUrl)
        val cookies = init.cookies

        if (init.code == 403) return

        // 2️⃣ Cerca il link CONTINUE
        val continueUrl = findContinue(init.text) ?: return

        // 3️⃣ Segui i redirect UPROTS
        val finalUrl = followRedirects(continueUrl, cookies) ?: return

        // 4️⃣ Se è Maxstream → estrai
        loadExtractor(finalUrl, url, subtitleCallback, callback)
    }

    private fun normalize(url: String): String {
        return when {
            url.contains("/msfi/") -> url.replace("/msfi/", "/msf/")
            url.contains("/msf/") -> url.replace("/msf/", "/mse/")
            else -> url
        }
    }

    private fun findContinue(html: String): String? {
        val doc = Jsoup.parse(html)
        return doc.select("a")
            .firstOrNull { it.text().contains("CONTINUE", ignoreCase = true) }
            ?.attr("href")
    }

    private suspend fun followRedirects(url: String, cookies: Map<String, String>): String? {
        var current = url
        repeat(10) {
            val res = app.get(current, headers = headers, cookies = cookies, allowRedirects = true)
            current = res.url

            // Se non è più un dominio UPROTS → è il link finale
            if (!current.contains("uprots")) return current
        }
        return null
    }
}
