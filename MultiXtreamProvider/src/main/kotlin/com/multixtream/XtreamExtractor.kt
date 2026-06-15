package com.multixtream

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class XtreamExtractor : ExtractorApi() {

    override val name = "Xtream"
    override val mainUrl = "http://dummy.xtream.local"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val cleanUrl = url.trim()
        val isM3u8 = cleanUrl.contains(".m3u8")

        // Headers intelligenti per Xtream
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0",
            "Accept" to "*/*",
            "Connection" to "keep-alive"
        )

        val link = newExtractorLink(
            source = name,
            name = "Xtream Live",
            url = cleanUrl,
            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        ) {
            this.headers = headers
            this.referer = cleanUrl.substringBefore("/live/")
            this.isM3u8 = isM3u8
            this.quality = Qualities.Unknown.value
        }

        callback.invoke(link)
    }
}
