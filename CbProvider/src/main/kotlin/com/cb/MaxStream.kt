package com.cb

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
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
        var cleanUrl = url.trim()
        
        // Gestione avanzata del link dell'episodio (3 parametri dopo watchfree)
        if (cleanUrl.contains("watchfree/")) {
            val parts = cleanUrl.split("watchfree/")[1].removeSuffix("/").split("/")
            if (parts.size >= 2) {
                // Nei link serie TV con 3 parametri (es. /watchfree/id1/id2/token), il vero ID del video è il SECONDO (parts[1])
                val videoId = if (parts.size == 3) parts[1] else parts[0]
                cleanUrl = "https://maxstream.video/emvvv/$videoId"
            }
        } else if (cleanUrl.contains("uprots.com/v/")) {
            cleanUrl = cleanUrl.replace("uprots.com/v/", "maxstream.video/emvvv/")
        }

        // Ora facciamo la chiamata all'embed reale e pulito di MaxStream
        val response = app.get(cleanUrl, referer = referer)
        val html = response.text

        val pattern = """sources\W+src\W+([^"\s]+)""".toRegex()
        val match = pattern.find(html)

        if (match != null) {
            val videoUrl = match.groupValues[1].replace("\"", "").trim()
            val isM3u8 = videoUrl.contains(".m3u8")

            val link = newExtractorLink(
                source = this.name,
                name = this.name,
                url = videoUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = cleanUrl
                this.quality = Qualities.Unknown.value
            }
            
            callback.invoke(link)
        }
    }
}
