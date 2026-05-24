package com.altadefinizione

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class AltaDefinizioneProvider : MainAPI() {

    override var mainUrl = "https://altadefinizione.you"
    override var name = "AltaDefinizione"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ---------------------------------------------------------
    // HOME PAGE
    // ---------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val items = doc.select(".swiper-slide")

        val list = items.mapNotNull { item ->
            val link = item.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = item.selectFirst(".movie-title a")?.text()?.trim()
                ?: item.selectFirst("h2 a")?.text()?.trim()
                ?: return@mapNotNull null

            val poster = item.selectFirst("img")?.attr("src")?.let {
                if (it.startsWith("/")) mainUrl + it else it
            }

            val isSeries = link.contains("/serie-tv/")

            if (isSeries) {
                newTvSeriesSearchResponse(title, link) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, link) {
                    this.posterUrl = poster
                }
            }
        }

        return newHomePageResponse(
            listOf(HomePageList("In evidenza", list)),
            hasNext = false
        )
    }

    // ---------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?do=search&subaction=search&story=$query"
        val doc = app.get(url).document

        return doc.select(".movie-poster").mapNotNull { item ->
            val link = item.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = item.parent()?.selectFirst(".movie-title a")?.text()?.trim()
                ?: return@mapNotNull null

            val poster = item.selectFirst("img")?.attr("src")?.let {
                if (it.startsWith("/")) mainUrl + it else it
            }

            val isSeries = link.contains("/serie-tv/")

            if (isSeries) {
                newTvSeriesSearchResponse(title, link) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, link) {
                    this.posterUrl = poster
                }
            }
        }
    }

    // ---------------------------------------------------------
    // LOAD (FILM + SERIE)
    // ---------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val isSeries = url.contains("/serie-tv/")
        return if (isSeries) loadSeries(url, doc) else loadMovie(url, doc)
    }

    // ---------------------------------------------------------
    // FILM
    // ---------------------------------------------------------
    private suspend fun loadMovie(url: String, doc: org.jsoup.nodes.Document): LoadResponse {
        val poster = doc.selectFirst(".movie_entry-poster")?.attr("src")?.let {
            if (it.startsWith("/")) mainUrl + it else it
        }

        val title = doc.selectFirst(".movie_entry-title")?.text()?.trim() ?: "Senza titolo"

        val plot = doc.selectFirst(".movie_entry-plot #text-content")
            ?.text()?.trim()

        val year = doc.select("div.movie_entry-details .row")
            .firstOrNull { it.text().contains("Anno:") }
            ?.selectFirst("div:nth-child(2)")?.text()?.trim()?.toIntOrNull()

        val genres = doc.select("div.movie_entry-details .row")
            .firstOrNull { it.text().contains("Genere:") }
            ?.select("a")?.map { it.text().trim() }

        val castList = doc.select("div.movie_entry-details .row")
            .firstOrNull { it.text().contains("Cast:") }
            ?.selectFirst(".cast")?.text()
            ?.split(",")?.map { it.trim() } ?: emptyList()

        val actors = castList.map { name ->
            ActorData(Actor(name, null), null)
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            if (year != null) this.year = year
            if (!genres.isNullOrEmpty()) this.tags = genres
            if (actors.isNotEmpty()) this.actors = actors
        }
    }

    // ---------------------------------------------------------
    // SERIE TV
    // ---------------------------------------------------------
    private suspend fun loadSeries(url: String, doc: org.jsoup.nodes.Document): LoadResponse {
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst(".movie_entry-title")?.text()?.trim()
            ?: "Serie TV"

        val poster = doc.selectFirst(".movie_entry-poster")?.attr("src")?.let {
            if (it.startsWith("/")) mainUrl + it else it
        }

        // 1) Estrarre IMDB ID dall'iframe
        val iframe = doc.selectFirst("iframe")?.attr("src") ?: ""
        val imdb = iframe.substringAfterLast("/")

        // 2) Chiamare API episodi
        val apiUrl = "https://v.vidxgo.co/api/popups/list.php?imdb=$imdb"
        val json = app.get(apiUrl).parsedSafe<Map<String, Any>>()
        val episodesJson = json?.get("episodes") as? List<Map<String, Any>>

        val episodes = episodesJson?.map { ep ->
            val epNum = (ep["episode"] as? Number)?.toInt() ?: 1
            val epTitle = ep["title"]?.toString() ?: "Episodio $epNum"
            val plot = ep["plot"]?.toString()
            val thumb = ep["thumbnail"]?.toString()

            newEpisode("$url?ep=$epNum") {
                this.name = epTitle
                this.season = 1
                this.episode = epNum
                this.posterUrl = thumb
                this.description = plot
            }
        } ?: emptyList()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
        }
    }

    // ---------------------------------------------------------
    // PLAYER
    // ---------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document
        val iframe = doc.selectFirst("iframe")?.attr("src") ?: return false

        val finalUrl = iframe.replace("tt", "")
        loadExtractor(finalUrl, data, subtitleCallback, callback)

        return true
    }
}
