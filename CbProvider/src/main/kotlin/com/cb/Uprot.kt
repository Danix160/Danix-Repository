package com.cb

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.api.Log
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
        Log.d("UPROT", "=== UPROT 2026 START ===")
        Log.d("UPROT", "Input URL: $url")

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0",
            "Accept" to "*/*",
            "Referer" to (referer ?: url)
        )

        val target = normalize(url)
        Log.d("UPROT", "Normalized URL: $target")

        val res = app.get(target, headers = headers)
        val html = res.text
        Log.d("UPROT", "Initial GET status: ${res.code}")

        // 1) Maxstream diretto nell'HTML (caso /msfi/ CB01)
        extractMaxstream(html)?.let { maxUrl ->
            Log.d("UPROT", "Found Maxstream in HTML → $maxUrl")
            loadExtractor(maxUrl, url, subtitleCallback, callback)
            return
        }

        // 2) CONTINUE classico
        findContinue(html)?.let { contUrl ->
            Log.d("UPROT", "Found CONTINUE → $contUrl")
            loadExtractor(contUrl, url, subtitleCallback, callback)
            return
        }

        // 3) Meta refresh
        extractMetaRefresh(html)?.let { metaUrl ->
            Log.d("UPROT", "Found META refresh → $metaUrl")
            loadExtractor(metaUrl, url, subtitleCallback, callback)
            return
        }

        // 4) Form hidden
        extractFormRedirect(html, headers)?.let { formUrl ->
            Log.d("UPROT", "Found FORM redirect → $formUrl")
            loadExtractor(formUrl, url, subtitleCallback, callback)
            return
        }

        // 5) JS redirect
        extractJsRedirect(html)?.let { jsUrl ->
            Log.d("UPROT", "Found JS redirect → $jsUrl")
            loadExtractor(jsUrl, url, subtitleCallback, callback)
            return
        }

        // 6) Redirect HTTP multipli (caso /mse/ / uprots)
        followRedirects(target, headers)?.let { finalUrl ->
            Log.d("UPROT", "Final redirect chain → $finalUrl")
            loadExtractor(finalUrl, url, subtitleCallback, callback)
            return
        }

        Log.e("UPROT", "❌ No valid link found")
    }

    // ============================
    //   NORMALIZZAZIONE UPROT
    // ============================
    private fun normalize(url: String): String {
        return when {
            // /msfi/ CB01 → NON /msf/, NON /mse/, la apriamo così com'è
            url.contains("/msfi/") -> {
                Log.d("UPROT", "Keep /msfi/ as is")
                url
            }
            // /msf/ OnlineSerieTV → /mse/ per avere redirect pulito
            url.contains("/msf/") -> {
                Log.d("UPROT", "Normalizing /msf/ → /mse/")
                url.replace("/msf/", "/mse/")
            }
            else -> url
        }
    }

    // ============================
    //   MAXSTREAM DALL'HTML
    // ============================
    private fun extractMaxstream(html: String): String? {
        val doc = Jsoup.parse(html)
        doc.select("a[href]").forEach { a ->
            val href = a.attr("href")
            if (href.contains("maxstream.video/uprots")) {
                return href
            }
        }
        return null
    }

    // ============================
    //   CONTINUE LINK
    // ============================
    private fun findContinue(html: String): String? {
        val doc = Jsoup.parse(html)

        // bottone "C o n t i n u e" o simili
        doc.select("a").forEach { a ->
            val text = a.text().trim().uppercase()
            if (text.contains("CONTINUE")) {
                val href = a.attr("href")
                if (href.isNotBlank()) return href
            }
        }

        // fallback: JS redirect
        Regex("""window\.location\.href\s*=\s*['"](.*?)['"]""")
            .find(html)?.groupValues?.get(1)?.let { return it }

        return null
    }

    // ============================
    //   META REFRESH
    // ============================
    private fun extractMetaRefresh(html: String): String? {
        val doc = Jsoup.parse(html)
        val meta = doc.selectFirst("meta[http-equiv=refresh]")?.attr("content") ?: return null
        return meta.substringAfter("url=", "").takeIf { it.isNotBlank() }
    }

    // ============================
    //   FORM HIDDEN
    // ============================
    private suspend fun extractFormRedirect(
        html: String,
        headers: Map<String, String>
    ): String? {
        val doc = Jsoup.parse(html)
        val form = doc.selectFirst("form[action]") ?: return null

        val action = form.attr("action")
        val data = form.select("input[name]").associate {
            it.attr("name") to it.attr("value")
        }

        Log.d("UPROT", "Submitting hidden form → $action")

        val res = app.post(action, data = data, headers = headers)
        val body = res.text

        extractMaxstream(body)?.let { return it }
        findContinue(body)?.let { return it }
        extractMetaRefresh(body)?.let { return it }

        return null
    }

    // ============================
    //   JS REDIRECT
    // ============================
    private fun extractJsRedirect(html: String): String? {
        val regex = Regex("""window\.location\.href\s*=\s*['"](.*?)['"]""")
        return regex.find(html)?.groupValues?.get(1)
    }

    // ============================
    //   REDIRECT MULTIPLI HTTP
    // ============================
    private suspend fun followRedirects(
        startUrl: String,
        headers: Map<String, String>
    ): String? {
        var current = startUrl
        val visited = mutableSetOf<String>()

        repeat(10) {
            if (!visited.add(current)) return null

            val res = app.get(current, headers = headers, allowRedirects = false)
            val loc = res.headers["location"]

            if (loc == null) {
                val html = res.text
                extractMaxstream(html)?.let { return it }
                findContinue(html)?.let { return it }
                extractMetaRefresh(html)?.let { return it }
                return null
            }

            current = if (loc.startsWith("http")) loc else mainUrl + loc
        }

        return null
    }
}
