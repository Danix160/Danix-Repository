package com.onlineserietv

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

    private val videoRegex = Regex(
        """(file|source|src)\s*[:=]\s*["'](https?://[^"']+\.m3u8[^"']*)["']""",
        RegexOption.IGNORE_CASE
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer)
        val html = response.text

        val match = videoRegex.find(html)
        val videoUrl = match?.groupValues?.get(2)

        if (videoUrl != null) {
            val link = newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }

            callback.invoke(link)
        }
    }
}
