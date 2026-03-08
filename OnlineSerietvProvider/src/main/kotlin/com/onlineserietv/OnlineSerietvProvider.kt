package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class OnlineSerietvProvider : MainAPI() {
    override var mainUrl = "https://onlineserietv.live"
    override var name = "OnlineSerieTv"
    override var lang = "it"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Analisi della Home per le sezioni "Film" e "Serie TV" [cite: 138, 245]
    override suspend fun getMainPage(page: Int, request: HomePageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val home = mutableListOf<HomePageList>()

        // Selettore basato sulla struttura dei caroselli OWL trovata nei file
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
        val isMovie = href.contains("/film/") // Identificazione tipo dal percorso URL [cite: 71, 444]

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    // Ricerca globale
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("article").mapNotNull { it.toSearchResult() }
    }

    // Caricamento dei dettagli (Film o Serie TV) [cite: 71, 444]
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text() ?: ""
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[name=description]")?.attr("content")
        val isMovie = url.contains("/film/")

        return if (isMovie) {
            // Per i film, l'iframe è diretto 
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            // Per le serie, estraiamo stagioni ed episodi [cite: 350, 444]
            val episodes = mutableListOf<Episode>()
            // Il file IframeSerie.txt mostra pulsanti per gli episodi con link diretti
            document.select("a.episodes_button").forEach { ep ->
                val epHref = ep.attr("href")
                val epNum = ep.text().trim().toIntOrNull()
                episodes.add(Episode(epHref, episode = epNum))
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // Estrazione dei link video dai player (Flexy, MaxStream, ecc.) 
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        // Cerca l'iframe del player 
        val iframeSrc = document.selectFirst("iframe")?.attr("src") ?: return false
        
        // Carica i link usando gli estrattori comuni di Cloudstream
        loadExtractor(iframeSrc, data, subtitleCallback, callback)
        return true
    }
}
