package com.onlineserietv

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URI

class MaxStream : ExtractorApi() {
    override val name = "MaxStream"
    override val mainUrl = "https://maxstream.video"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 1. Prima richiesta
        val initialResponse = app.get(url, referer = referer)
        val initialHtml: String = initialResponse.text
        val document = Jsoup.parse(initialHtml)

        // 2. Estrazione del link intermedio dal tag <a> con il path /uprots/
        val rawNextUrl = document.selectFirst("a[href*=/uprots/]")?.attr("href")
            ?: document.select("a[href*=/uprots/]").firstOrNull()?.attr("href")

        // Risoluzione manuale dell'URL per evitare errori di compilazione con fixUrl/fixUrlNull
        val nextUrl: String = if (!rawNextUrl.isNullOrEmpty()) {
            if (rawNextUrl.startsWith("http://") || rawNextUrl.startsWith("https://")) {
                rawNextUrl
            } else {
                URI(mainUrl).resolve(rawNextUrl).toString()
            }
        } else {
            url
        }

        // 3. Seconda richiesta se presente un reindirizzamento
        val finalHtml: String = if (nextUrl != url) {
            app.get(nextUrl, referer = url).text
        } else {
            initialHtml
        }

        // 4. Estrazione dell'URL video tramite Regex
        val pattern = """sources\W+src\W+([^"\s]+)""".toRegex()
        val match = pattern.find(finalHtml)

        if (match != null) {
            val videoUrl = match.groupValues[1].replace("\"", "").trim()
            val isM3u8 = videoUrl.contains(".m3u8")

            val link = newExtractorLink(
                source = this.name,
                name = this.name,
                url = videoUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = nextUrl
                this.quality = Qualities.Unknown.value
            }

            callback.invoke(link)
        }
    }
}
