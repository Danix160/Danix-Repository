package com.onlineserietv

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink // Utilizziamo l'helper consigliato dall'errore
import com.lagradost.cloudstream3.app

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
        val response = app.get(url, referer = referer)
        val html = response.text

        val pattern = """sources\W+src\W+([^"\s]+)""".toRegex()
        val match = pattern.find(html)

        if (match != null) {
            val videoUrl = match.groupValues[1].replace("\"", "").trim()

            // Utilizziamo newExtractorLink per mappare correttamente la sorgente video
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoUrl,
                    referer = url,
                    quality = Qualities.Unknown.value,
                    isM3u8 = videoUrl.contains(".m3u8")
                )
            )
        }
    }
}
