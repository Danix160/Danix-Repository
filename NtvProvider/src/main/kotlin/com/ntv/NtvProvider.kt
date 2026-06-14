package com.ntv

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class NtvProvider : MainAPI() {

    override var name = "Ntv.cx"
    override var mainUrl = "https://ntv.st"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    // ---------------------------------------------------------
    // HOME PAGE → lista canali da /channels
    // ---------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/channels").document

        val channels = doc.select("div.channel-card").mapNotNull { card ->
            val title = card.selectFirst("h3.channel-name")?.text()?.trim() ?: return@mapNotNull null
            val href = card.selectFirst("a.watch-btn")?.attr("href") ?: return@mapNotNull null

            newLiveSearchResponse(title, fixUrl(href), TvType.Live) {
                this.posterUrl = null
            }
        }

        return newHomePageResponse(
            listOf(HomePageList("Live Channels", channels)),
            hasNext = false
        )
    }

    // ---------------------------------------------------------
    // LOAD → pagina del canale / evento
    // ---------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("h3.channel-name")?.text()?.trim()
            ?: "Live Stream"

        val description = doc.select("div.match-details span")
            .joinToString(" - ") { it.text() }

        return newLiveStreamLoadResponse(title, url, url) {
            this.plot = description
            this.posterUrl = null
        }
    }

    // ---------------------------------------------------------
    // LOAD LINKS → estrae iframe → embed → m3u8
    // ---------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // 1) Pagina WATCH
        val doc = app.get(data).document

        val embedUrl = doc.selectFirst("iframe#streamPlayer")
            ?.attr("src")
            ?.let { fixUrl(it) }
            ?: run {
                Log.e("NtvProvider", "Iframe non trovato")
                return false
            }

        // 2) Pagina EMBED
        val embedDoc = app.get(embedUrl, referer = data).document

        // 3) Cerca direttamente un m3u8
        val m3u8 = Regex("""https?://[^\s"'<>]+\.m3u8""")
            .find(embedDoc.toString())
            ?.value

        if (m3u8 == null) {
            Log.e("NtvProvider", "Nessun m3u8 trovato nell'embed")
            return false
        }

        // 4) Invia lo stream a Cloudstream
        callback(
            newExtractorLink(
                source = name,
                name = "Ntv.cx Live",
                url = m3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.headers = mapOf(
                    "Referer" to mainUrl,
                    "Origin" to mainUrl,
                    "User-Agent" to USER_AGENT
                )
                this.quality = 0
            }
        )

        return true
    }
}
