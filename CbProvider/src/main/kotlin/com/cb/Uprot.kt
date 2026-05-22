package com.cb

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.app
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

        var current = normalize(url)
        Log.d("UPROT", "Normalized URL: $current")

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0",
            "Accept" to "*/*",
            "Referer" to (referer ?: url)
        )

        // 1️⃣ GET iniziale
        val res = app.get(current, headers = headers)
        val html = res.text
        Log.d("UPROT", "Initial GET status: ${res.code}")

        // 2️⃣ CONTINUE diretto → Maxstream
        findContinue(html)?.let { continueUrl ->
            Log.d("UPROT", "Found CONTINUE → $continueUrl")
            loadExtractor(continueUrl, url, subtitleCallback, callback)
            return
        }

        // 3️⃣ Meta refresh
        extractMetaRefresh(html)?.let { metaUrl ->
            Log.d("UPROT", "Found META refresh → $metaUrl")
            loadExtractor(metaUrl, url, subtitleCallback, callback)
            return
        }

        // 4️⃣ Form hidden
        extractFormRedirect(html, headers)?.let { formUrl ->
            Log.d("UPROT", "Found FORM redirect → $formUrl")
            loadExtractor(formUrl, url, subtitleCallback, callback)
            return
        }

        // 5️⃣ JS redirect
        extractJsRedirect(html)?.let { jsUrl ->
            Log.d("UPROT", "Found JS redirect → $jsUrl")
            loadExtractor(jsUrl, url, subtitleCallback, callback)
            return
        }

        // 6️⃣ Redirect multipli uprots → maxstream
        followRedirects(current, headers)?.let { finalUrl ->
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
            url.contains("/msfi/") -> {
                Log.d("UPROT", "Normalizing /msfi/ → /mse/")
                url.replace("/msfi/", "/mse/")
            }
            url.contains("/msf/") -> {
                Log.d("UPROT", "Normalizing /msf/ → /mse/")
                url.replace("/msf/", "/mse/")
            }
            else -> url
        }
    }

    // ============================
    //   CONTINUE LINK
    // ============================
    private fun findContinue(html: String): String? {
        val doc = Jsoup.parse(html)

        // CONTINUE normale
        doc.select("a").forEach { a ->
            val text = a.text().trim().uppercase()
            if (text.contains("CONTINUE")) {
                return a.attr("href")
            }
        }

        // JS redirect
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

        Log.d("UPROT", "Submitting hidden form → $action")

        val res = app.post(action, data = data, headers = headers)
        return findContinue(res.text)
    }

    // ============================
    //   JS REDIRECT
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

            Log.d("UPROT", "Redirect step → $loc")

            if (loc == null) {
                return findContinue(res.text)
            }

            current = loc
        }

        return null
    }
}
