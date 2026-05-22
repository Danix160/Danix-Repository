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
        var targetLink = url
        var finalUrl: String? = null

        val baseHeaders = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-GPC" to "1"
        )

        // --- NORMAL FLOW (msf → mse) ---
        if (!targetLink.contains("msfi")) {

            if (targetLink.contains("msf"))
                targetLink = targetLink.replace("msf", "mse")

            val response = app.get(targetLink, headers = baseHeaders, referer = referer)

            if (response.code != 403) {
                finalUrl = findMaxstreamLink(response.text)
            }

        } else {
            // --- CAPTCHA FLOW (msfi → msf) ---
            if (targetLink.contains("mse"))
                targetLink = targetLink.replace("mse", "msf")

            val initResponse = app.post(targetLink, headers = baseHeaders, referer = targetLink)
            val cookies = initResponse.cookies

            val doc = Jsoup.parse(initResponse.text)
            val imgCaptcha = doc.selectFirst("img")?.attr("src")
            val captchaNumber = imgCaptcha?.substringAfter("captcha=", "")?.substringBefore("&") ?: ""

            val postResponse = app.post(
                targetLink,
                cookies = cookies,
                headers = baseHeaders + ("Content-Type" to "application/x-www-form-urlencoded"),
                data = mapOf("captcha" to captchaNumber),
                referer = targetLink
            )

            if (postResponse.code != 403) {
                finalUrl = getFinalMaxstreamLink(postResponse.text, baseHeaders)
            }
        }

        // --- FINAL EXTRACTION ---
        if (!finalUrl.isNullOrEmpty()) {
            loadExtractor(finalUrl, url, subtitleCallback, callback)
        }
    }

    // ============================
    //   PRIORITÀ ASSOLUTA MAXSTREAM
    // ============================
    private fun findMaxstreamLink(html: String): String? {
        val doc = Jsoup.parse(html)

        val continues = doc.select("a")
            .filter { it.text().contains("CONTINUE", ignoreCase = true) }
            .map { it.attr("href") }

        if (continues.isEmpty()) return null

        // 1️⃣ PRIORITÀ: MAXSTREAM
        continues.forEach { link ->
            if (link.contains("maxstream", ignoreCase = true)) return link
        }

        // 2️⃣ ESCLUDI MIXDROP
        continues.forEach { link ->
            if (!link.contains("mixdrop", ignoreCase = true)) return link
        }

        // 3️⃣ Se proprio non c'è altro, ritorna null (NO MIXDROP)
        return null
    }

    private suspend fun getFinalMaxstreamLink(html: String, headers: Map<String, String>): String? {
        var redirectUrl = findMaxstreamLink(html) ?: return null
        var time = 0

        while (redirectUrl.contains("uprots")) {
            val headResponse = app.get(redirectUrl, headers = headers, allowRedirects = true)
            redirectUrl = headResponse.url
            time++
            if (time == 10) return null
        }

        // Conversione link Maxstream
        return if (redirectUrl.contains("watchfree/")) {
            val parts = redirectUrl.split("watchfree/")[1].split("/")
            if (parts.size > 1) {
                "https://maxstream.video/emvvv/${parts[1]}"
            } else redirectUrl
        } else redirectUrl
    }
}
