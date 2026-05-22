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
        var maxStreamUrl: String? = null

        val baseHeaders = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-GPC" to "1"
        )

        if (!targetLink.contains("msfi")) {
            if (targetLink.contains("msi")) {
                targetLink = targetLink.replace("msi", "msei")
            }

            val response = app.get(targetLink, headers = baseHeaders, referer = referer)
            if (response.code != 403) {
                maxStreamUrl = findLinkInHtml(response.text)
            }
        } else {
            if (targetLink.contains("mse")) {
                targetLink = targetLink.replace("mse", "msf")
            }

            val initResponse = app.post(targetLink, headers = baseHeaders, referer = targetLink)
            val cookies = initResponse.cookies

            val doc = Jsoup.parse(initResponse.text)
            val imgCaptcha = doc.selectFirst("img")?.attr("src")
            val captchaNumber = imgCaptcha?.substringAfter("captcha=", "")?.substringBefore("&") ?: ""

            val postResponse = app.post(
                targetLink,
                cookies = cookies,
                headers = baseHeaders.plus("Content-Type" to "application/x-www-form-urlencoded"),
                data = mapOf("captcha" to captchaNumber),
                referer = targetLink
            )

            if (postResponse.code != 403) {
                maxStreamUrl = getFinalMaxstreamLink(postResponse.text, baseHeaders)
            }
        }

        if (!maxStreamUrl.isNullOrEmpty()) {
            loadExtractor(maxStreamUrl, url, subtitleCallback, callback)
        }
    }

    private fun findLinkInHtml(html: String): String? {
        val doc = Jsoup.parse(html)
        doc.select("a").forEach { tag ->
            val text = tag.text().uppercase()
            if (text.contains("C O N T I N U E") || text.contains("CONTINUE")) {
                return tag.attr("href")
            }
        }
        return null
    }

    private suspend fun getFinalMaxstreamLink(html: String, headers: Map<String, String>): String? {
        var redirectUrl = findLinkInHtml(html) ?: return null
        var time = 0

        while (redirectUrl.contains("uprots")) {
            val headResponse = app.get(redirectUrl, headers = headers, allowRedirects = true)
            redirectUrl = headResponse.url
            time++
            if (time == 10) return null
        }

        return if (redirectUrl.contains("watchfree/")) {
            val parts = redirectUrl.split("watchfree/")[1].split("/")
            if (parts.size > 1) {
                "https://maxstream.video/emvvv/${parts[1]}"
            } else {
                redirectUrl
            }
        } else {
            redirectUrl
        }
    }
}
