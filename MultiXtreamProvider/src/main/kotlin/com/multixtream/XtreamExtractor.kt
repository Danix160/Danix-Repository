package com.multixtream

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

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

        // ⚠️ SOLO PARAMETRI POSIZIONALI — NIENTE NOMI
        val link = ExtractorLink(
            name,               // source
            "Xtream Live",      // name
            cleanUrl,           // url
            "",                 // referer (NON può essere null)
            Qualities.Unknown.value, // quality
            isM3u8              // isM3u8
        )

        callback(link)
    }
}
