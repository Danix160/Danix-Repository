package com.vegetatv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class VegetaTVProvider : MainAPI() {

    override var name = "VegetaTV"
    override var mainUrl = "http://vegetatv.duckdns.org"
    override var lang = "it"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    // ---------------------------------------------------------
    // HOME PAGE → categorie + canali (NON load)
    // ---------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document

        // CANALI
        val channels = doc.select("#channels .chan").map { ch ->
            val title = ch.selectFirst(".nm")?.text()?.trim() ?: "Senza nome"
            val logo = ch.selectFirst(".logo")?.attr("src")
            val category = ch.selectFirst(".gp")?.text()?.trim() ?: "Altro"
            val stream = ch.attr("data-url")

            newLiveSearchResponse("$title [$category]", stream, TvType.Live) {
                this.posterUrl = logo
            }
        }

        // CATEGORIE
        val categories = channels.groupBy {
            it.name.substringAfterLast("[", "").substringBefore("]").trim()
        }

        val lists = categories.map { (cat, list) ->
            HomePageList(cat, list)
        }

        return newHomePageResponse(lists, hasNext = false)
    }

    // ---------------------------------------------------------
    // LOAD(server) → pagina fittizia (obbligatoria)
    // ---------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        return newLiveStreamLoadResponse("VegetaTV", url, url)
    }

    // ---------------------------------------------------------
    // LOAD LINKS → stream diretto m3u8
    // ---------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        callback(
            newExtractorLink(
                source = name,
                name = "VegetaTV",
                url = data,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.headers = mapOf("User-Agent" to USER_AGENT)
                this.quality = Qualities.Unknown.value
            }
        )

        return true
    }
}
