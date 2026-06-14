package com.ntv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log

class NtvProvider : MainAPI() {

    override var name = "Ntv.st"
    override var mainUrl = "https://ntv.st"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    // ---------------------------------------------------------
    // DATA CLASS per l’API JSON
    // ---------------------------------------------------------
    data class ChannelResponse(
        val success: Boolean,
        val channels: List<ChannelItem>
    )

    data class ChannelItem(
        val channel_id: String,
        val channel_name: String,
        val channel_image: String
    )

    // ---------------------------------------------------------
    // HOME PAGE → usa l’API JSON (perfetta per Cloudstream)
    // ---------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = app.get("$mainUrl/api/get-channels").parsedSafe<ChannelResponse>()
            ?: throw ErrorLoadingException("API error")

        val channels = json.channels.map { ch ->
            newLiveSearchResponse(
                ch.channel_name,
                "$mainUrl/channel/${ch.channel_id}",
                TvType.Live
            ) {
                this.posterUrl = ch.channel_image
            }
        }

        return newHomePageResponse(
            listOf(HomePageList("Live Channels", channels)),
            hasNext = false
        )
    }

    // ---------------------------------------------------------
    // LOAD → pagina del canale
    // ---------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: "Live Stream"

        return newLiveStreamLoadResponse(title, url, url) {
            this.posterUrl = null
            this.plot = null
        }
    }

    // ---------------------------------------------------------
    // LOAD LINKS → iframe → embed → m3u8
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
        val embedDoc = app.get(
            embedUrl,
            referer = data,
            headers = mapOf("User-Agent" to USER_AGENT),
            allowRedirects = true
        ).document

        // 3) Cerca m3u8
        val m3u8 = Regex("""https?://[^\s"'<>]+\.m3u8""")
            .find(embedDoc.toString())
            ?.value

        if (m3u8 == null) {
            Log.e("NtvProvider", "Nessun m3u8 trovato nell'embed")
            return false
        }

        // 4) Link finale
        callback(
            newExtractorLink(
                source = name,
                name = "Ntv.st Live",
                url = m3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedUrl
                this.headers = mapOf(
                    "Referer" to embedUrl,
                    "Origin" to mainUrl,
                    "User-Agent" to USER_AGENT
                )
                this.quality = 0
            }
        )

        return true
    }
}
