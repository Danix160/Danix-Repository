package com.altadefinizione

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

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
        val items = doc.select(".movie-poster")

        val list = items.mapNotNull { item ->
            val link = item.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = item.parent()?.selectFirst(".movie-title a")?.text()?.trim()
                ?: return@mapNotNull null
            val poster = item.selectFirst("img")?.attr("src")

            if (link.contains("/serie-tv/")) {
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
            val poster = item.selectFirst("img")?.attr("src")

            if (link.contains("/serie-tv/")) {
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
        return if (url.contains("/serie-tv/")) loadSeries(url, doc) else loadMovie(url, doc)
    }

    // ---------------------------------------------------------
    // FILM
    // ---------------------------------------------------------
    private suspend fun loadMovie(url: String, doc: Document): LoadResponse {
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Film"
        val poster = doc.selectFirst(".movie-poster img")?.attr("src")
        val plot = doc.selectFirst(".movie_entry-plot")?.text()?.trim()

        val superVideo = doc.select("a[href*=\"supervideo.cc\"]").attr("href")
        val dropLoad = doc.select("a[href*=\"dropload.co\"]").attr("href")

        val link = when {
            superVideo.isNotBlank() -> superVideo
            dropLoad.isNotBlank() -> dropLoad
            else -> url
        }

        return newMovieLoadResponse(
            name = title,
            url = url,
            dataUrl = link,
            type = TvType.Movie
        ) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ---------------------------------------------------------
    // SERIE TV
    // ---------------------------------------------------------
    private suspend fun loadSeries(url: String, doc: Document): LoadResponse {
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Serie TV"
        val poster = doc.selectFirst(".movie-poster img")?.attr("src")

        val episodes = mutableListOf<Episode>()

        doc.select(".down-episode").forEach { ep ->
            val epText = ep.selectFirst("span b")?.text()?.trim() ?: return@forEach
            val season = epText.substringBefore("x").toIntOrNull() ?: 1
            val episode = epText.substringAfter("x").toIntOrNull() ?: 1

            val thumb = ep.selectFirst("img")?.attr("data-src")

            val superVideo = ep.select("a[href*=\"supervideo.cc\"]").attr("href")
            val dropLoad = ep.select("a[href*=\"dropload.co\"]").attr("href")

            val link = when {
                superVideo.isNotBlank() -> superVideo
                dropLoad.isNotBlank() -> dropLoad
                else -> return@forEach
            }

            episodes += newEpisode(
                data = link,
                name = "Episodio $season x $episode",
                season = season,
                episode = episode,
                posterUrl = thumb
            )
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            posterUrl = poster,
            episodes = episodes
        )
    }

    // ---------------------------------------------------------
    // PLAYER (SuperVideo / DropLoad)
    // ---------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return loadExtractor(data, subtitleCallback, callback)
    }
}
