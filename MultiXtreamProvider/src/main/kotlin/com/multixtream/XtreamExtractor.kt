package com.multixtream

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*

class XtreamExtractor : ExtractorApi() {

    override val name = "Xtream"
    override val mainUrl = "http://dummy.xtream.local"
    override val requiresReferer = false


    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val cleanUrl = url.trim()

        val isM3u8 =
            cleanUrl.contains(".m3u8", ignoreCase = true)


        val link = newExtractorLink(
            source = name,
            name = "Xtream Live",
            url = cleanUrl,
            type = if (isM3u8)
                ExtractorLinkType.M3U8
            else
                ExtractorLinkType.VIDEO
        ) {

            this.referer = referer ?: ""

            this.quality = Qualities.Unknown.value

        }


        callback(link)
    }
}
