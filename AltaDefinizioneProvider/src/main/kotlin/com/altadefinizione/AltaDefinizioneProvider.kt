package com.altadefinizione

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.altadefinizione.extractor.VidxGoExtractor
import org.jsoup.nodes.Element

class AltaDefinizioneProvider : MainAPI() {

    override var mainUrl = "https://altadefinizionex.co"
    override var name = "AltaDefinizione"
    override val hasMainPage = true
    override var lang = "it"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

   override val mainPage = mainPageOf(
    "$mainUrl/film/" to "Film: Ultimi aggiunti",
        "$mainUrl/serie-tv/" to "Serie TV: Ultime aggiunte"
)
    
    private fun parseMovieElement(element: Element): SearchResponse? {
    val url = element.attr("data-link")
        ?: element.selectFirst(".movie-title a")?.absUrl("href")
        ?: return null

    val title = element.selectFirst(".movie-title a")?.text()
        ?: element.attr("data-title")
        ?: "Senza titolo"

    val poster = element.selectFirst(".movie-poster img")?.absUrl("src")

    val isSeries = url.contains("/serie-tv/")

    return if (isSeries) {
        newTvSeriesSearchResponse(title, url) {
            this.posterUrl = poster
        }
    } else {
        newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = poster
        }
    }
}

    // MAIN PAGE (Cloudstream 4.x)
 override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
    val doc = app.get(request.data).document
    val results = mutableListOf<SearchResponse>()

    // --- Pannello FILM: Inseriti di recente ---
    doc.select("#ultimi .swiper-slide .movie").forEach { element ->
        parseMovieElement(element)?.let { results.add(it) }
    }

    // --- Pannello SERIE TV: Ultime inserite ---
    doc.select("#se_top .swiper-slide .movie").forEach { element ->
        parseMovieElement(element)?.let { results.add(it) }
    }

    // --- Pannello SERIE TV: Di tendenza ---
    doc.select("#se_ultimi .swiper-slide .movie").forEach { element ->
        parseMovieElement(element)?.let { results.add(it) }
    }

    return newHomePageResponse(request.name, results)
}



    // SEARCH (Cloudstream 4.x)
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?do=search&subaction=search&story=$query"
        val doc = app.get(url).document

        return doc.select(".movie").mapNotNull {
            val link = it.attr("data-link")
            val title = it.attr("data-title")
            val poster = it.selectFirst(".movie-poster img")?.absUrl("src")

            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    // LOAD (Cloudstream 4.x)
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.movie_entry-title")?.text() ?: return null
        val poster = doc.selectFirst(".movie_entry-poster")?.attr("data-src")
        val plot = doc.selectFirst(".movie_entry-description")?.text()

        val imdb = doc.selectFirst(".player img.layer-image")
            ?.absUrl("src")
            ?.substringAfter("tt")
            ?.substringBefore(".")

        val streamUrl = imdb?.replace("tt", "")

        // Film
        if (doc.select(".player").isNotEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.dataUrl = streamUrl ?: ""
            }
        }

        // Serie
        val episodes = doc.select(".episode-item").mapNotNull {
            val epUrl = it.selectFirst("a")?.absUrl("href") ?: return@mapNotNull null
            val epTitle = it.selectFirst(".episode-title")?.text() ?: "Episodio"
            val season = it.attr("data-season").toIntOrNull() ?: 1
            val episode = it.attr("data-episode").toIntOrNull() ?: 1

            newEpisode(epUrl) {
                this.name = epTitle
                this.season = season
                this.episode = episode
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // LOAD LINKS (Cloudstream 4.x)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        VidxGoExtractor().getUrl(
            data,
            mainUrl,
            subtitleCallback,
            callback
        )

        return true
    }
}
