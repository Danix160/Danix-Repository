package com.altadefinizione.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.ExtractorApi
import com.lagradost.cloudstream3.extractors.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.USER_AGENT

class VidxGoExtractor : ExtractorApi() {
    override val name = "VidxGo"
    override val mainUrl = "https://v.vidxgo.co"

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val headers = mapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to USER_AGENT
        )

        val html = app.get(url, headers = headers).text

        // SERIE TV → /t/... → JSON con "url"
        if (url.contains("/t/")) {
            val videoUrlRaw = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
                .find(html)?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?: return emptyList()

            return listOf(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrlRaw,
                    referer = "$mainUrl/",
                    quality = Qualities.Unknown.value
                )
            )
        }

        // FILM → iframe
        val iframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)

        if (iframe != null) {
            return listOf(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = iframe,
                    referer = "$mainUrl/",
                    quality = Qualities.Unknown.value
                )
            )
        }

        // FILM fallback → currentSrc
        val videoUrlRaw = Regex("currentSrc\\s*=\\s*['\"]([^'\"]+)['\"]")
            .find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?: return emptyList()

        return listOf(
            ExtractorLink(
                source = name,
                name = name,
                url = videoUrlRaw,
                referer = "$mainUrl/",
                quality = Qualities.Unknown.value
            )
        )
    }
}
