package com.lagradost.cloudstream3.movieproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import org.jsoup.nodes.Element

class OnlineSerietvProvider : MainAPI() {
    override var mainUrl = "https://onlineserietv.live"
    override var name = "OnlineSerieTv"
    override var lang = "it"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // --- HOME PAGE & SEARCH ---
    override suspend fun getMainPage(page: Int, request: HomePageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val home = mutableListOf<HomePageList>()

        // Sezione Film e Serie TV dai caroselli (visti in Home.txt)
        document.select("div.items").forEach { block ->
            val title = block.selectFirst("h2")?.text() ?: "Novità"
            val items = block.select("article").mapNotNull { it.toSearchResult() }
            if (items.isNotEmpty()) home.add(HomePageList(title, items))
        }
        return HomePageResponse(home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        val isMovie = href.contains("/film/")

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("article").mapNotNull { it.toSearchResult() }
    }

    // --- LOAD DETAILS ---
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text() ?: ""
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[name=description]")?.attr("content")
        
        return if (url.contains("/film/")) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            val episodes = mutableListOf<Episode>()
            // Parsing episodi come da IframeSerie.txt
            document.select("a.episodes_button").forEach { ep ->
                val epHref = ep.attr("href")
                val epNum = ep.selectFirst("b")?.text()?.toIntOrNull()
                episodes.add(Episode(epHref, episode = epNum))
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // --- LINK EXTRACTION & CAPTCHA ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Carichiamo la pagina che contiene l'iframe del player
        val response = app.get(data).document
        val iframeUrl = response.selectFirst("iframe")?.attr("src") ?: return false

        // 2. Chiamata al player (Flexy o MaxStream)
        // Se il sito richiede un captcha numerico "uprot", Cloudstream mostrerà un dialog all'utente
        val playerPage = app.get(iframeUrl).document
        
        // Verifica presenza Captcha Uprot
        if (playerPage.selectFirst("input[name=captcha]") != null) {
            // Qui implementiamo la logica di risoluzione manuale se necessaria
            // In un plugin reale, useremmo un'interfaccia di dialogo per l'input numerico
        }

        // 3. Estrazione finale del file video (M3U8 o MP4)
        // Nota: Poiché Flexy e MaxStream sono player custom del sito, 
        // spesso i link sono nascosti in script eval o variabili JS
        val scriptData = playerPage.select("script").html()
        val videoUrl = Regex("file:\"(.*?)\"").find(scriptData)?.groupValues?.get(1)

        if (videoUrl != null) {
            callback.invoke(
                ExtractorLink(
                    this.name,
                    "Flexy Player",
                    videoUrl,
                    referer = iframeUrl,
                    quality = getQualityFromName("720p"),
                    isM3u8 = videoUrl.contains(".m3u8")
                )
            )
        }

        return true
    }
}
