package com.cb

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
    ) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0",
            "Accept" to "*/*",
            "Referer" to (referer ?: url)
        )

        // Normalizzazione link
        var current = normalize(url)

        // 1️⃣ GET iniziale
        val res = app.get(current, headers = headers)
        var html = res.text

        // 2️⃣ Prova estrazione diretta
        var final = extractMaxstream(html)

        // 3️⃣ Meta refresh
        if (final == null) {
            final = extractMetaRefresh(html)
        }

        // 4️⃣ Form hidden
        if (final == null) {
            final = extractFormRedirect(html, headers)
        }

        // 5️⃣ Redirect JS
        if (final == null) {
            final = extractJsRedirect(html)
        }

        // 6️⃣ Segui redirect multipli uprots → maxstream
        if (final == null) {
            final = followRedirects(current, headers)
        }

        // 7️⃣ Conversione Maxstream
        final = convertMaxstream(final)

        if (!final.isNullOrEmpty()) {
            loadExtractor(final, url, subtitleCallback, callback)
        }
    }

    // ============================
    //   NORMALIZZAZIONE LINK
    // ============================
    private fun normalize(url: String): String {
        return url
            .replace("/msf/", "/mse/")
            .replace("/msfi/", "/msf/")
            .replace("msef", "mse")
    }

    // ============================
    //   ESTRAZIONE MAXSTREAM
    // ============================
    private fun extractMaxstream(html: String): String? {
        val doc = Jsoup.parse(html)

        // Cerca link Maxstream diretto
        doc.select("a[href]").forEach { a ->
            val href = a.attr("href")
            if (href.contains("maxstream", ignoreCase = true)) return href
        }

        // Cerca link watchfree
        doc.select("a[href]").forEach { a ->
            val href = a.attr("href")
            if (href.contains("watchfree")) return href
        }

        return null
    }

    // ============================
    //   META REFRESH
    // ============================
    private fun extractMetaRefresh(html: String): String? {
        val doc = Jsoup.parse(html)
        val meta = doc.selectFirst("meta[http-equiv=refresh]")?.attr("content") ?: return null
        return meta.substringAfter("url=", "")
    }

    // ============================
    //   FORM HIDDEN
    // ============================
    private suspend fun extractFormRedirect(html: String, headers: Map<String, String>): String? {
        val doc = Jsoup.parse(html)
        val form = doc.selectFirst("form[action]") ?: return null

        val action = form.attr("action")
        val data = form.select("input[name]").associate {
            it.attr("name") to it.attr("value")
        }

        val res = app.post(action, data = data, headers = headers)
        return extractMaxstream(res.text)
    }

    // ============================
    //   REDIRECT JS
    // ============================
    private fun extractJsRedirect(html: String): String? {
        val regex = Regex("window\\.location\\.href\\s*=\\s*['\"](.*?)['\"]")
        return regex.find(html)?.groupValues?.get(1)
    }

    // ============================
    //   REDIRECT MULTIPLI
    // ============================
    private suspend fun followRedirects(url: String, headers: Map<String, String>): String? {
        var current = url
        val visited = mutableSetOf<String>()

        repeat(10) {
            if (current in visited) return null
            visited.add(current)

            val res = app.get(current, headers = headers, allowRedirects = false)
            val loc = res.headers["location"]

            if (loc == null) return extractMaxstream(res.text)

            current = loc
        }

        return null
    }

    // ============================
    //   CONVERSIONE MAXSTREAM
    // ============================
    private fun convertMaxstream(url: String?): String? {
        if (url == null) return null

        if (url.contains("watchfree/")) {
            val id = url.substringAfter("watchfree/").substringBefore("/")
            return "https://maxstream.video/emvvv/$id"
        }

        return url
    }
}
