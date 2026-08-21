package com.onlineserietv

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

class Uprot : ExtractorApi() {
    override val name = "Uprot"
    override val mainUrl = "https://uprot.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/145.0.0.0 Safari/537.36",
            "Accept" to
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "it-IT,it;q=0.9,en;q=0.8",
            "Referer" to (referer ?: mainUrl)
        )

        val mseUrl = when {
            url.contains("/msf/") ->
                url.replace("/msf/", "/mse/")

            else -> url
        }

        var maxstreamUrl: String? = null

        try {
            val response = app.get(
                mseUrl,
                headers = headers
            )

            val html = response.text

            val base64Base = Regex(
                """decodedBaseUrl\s*=\s*atob\(\s*["']([^"']+)["']\s*\)"""
            ).find(html)?.groupValues?.getOrNull(1)

            val base64Value = Regex(
                """decodedEncryptedVal\s*=\s*atob\(\s*["']([^"']+)["']\s*\)"""
            ).find(html)?.groupValues?.getOrNull(1)

            if (!base64Base.isNullOrBlank() &&
                !base64Value.isNullOrBlank()
            ) {

                val decodedBase = String(
                    Base64.decode(base64Base, Base64.DEFAULT),
                    Charsets.UTF_8
                )

                val decodedValue = String(
                    Base64.decode(base64Value, Base64.DEFAULT),
                    Charsets.UTF_8
                )

                maxstreamUrl = decodedBase + decodedValue
            }

            if (maxstreamUrl == null) {

                maxstreamUrl =
                    Regex(
                        """https?://(?:www\.)?maxstream\.[^"'<>\\\s]+"""
                    )
                        .find(html)
                        ?.value
                        ?.replace("\\/", "/")
            }

            if (maxstreamUrl == null) {

                val doc = Jsoup.parse(html)

                maxstreamUrl =
                    doc.selectFirst(
                        "iframe[src*=maxstream]"
                    )?.attr("src")
                        ?: doc.selectFirst(
                            "a[href*=maxstream]"
                        )?.attr("href")
            }

        } catch (e: Exception) {
            println("UPROT ERROR: ${e.message}")
        }

        maxstreamUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { finalUrl ->

                val absoluteUrl =
                    if (finalUrl.startsWith("//"))
                        "https:$finalUrl"
                    else
                        finalUrl

                loadExtractor(
                    absoluteUrl,
                    mseUrl,
                    subtitleCallback,
                    callback
                )
            }
    }
}
