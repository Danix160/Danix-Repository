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

    private val headers = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl, headers = headers).document
        val items = doc.select(".movie-poster")

        val list = items.mapNotNull { item ->
            val link = item.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = item.parent()?.selectFirst(".movie-title a")?.text()?.trim()
                ?: return@mapNotNull null
            val poster = item.selectFirst("img")?.attr("src")

            if (link.contains("/serie-tv/")) {
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }

        return newHomePageResponse(
            listOf(HomePageList("In evidenza", list)),
            false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?do=search&subaction=search&story=$query"
        val doc = app.get(url, headers = headers).document

        return doc.select(".movie-poster").mapNotNull { item ->
            val link = item.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = item.parent()?.selectFirst(".movie-title a")?.text()?.trim()
                ?: return@mapNotNull null
            val poster = item.selectFirst("img")?.attr("src")

            if (link.contains("/serie-tv/")) {
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document
        return if (url.contains("/serie-tv/")) loadSeries(url, doc) else loadMovie(url, doc)
    }

    private suspend fun loadMovie(url: String, doc: Document): LoadResponse {
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Film"
        val poster = doc.selectFirst(".movie-poster img")?.attr("src")

        val superVideo = doc.select("a[href*=\"supervideo.cc\"]").attr("href")
        val dropLoad = doc.select("a[href*=\"dropload.co\"]").attr("href")

        val link = when {
            superVideo.isNotBlank() -> superVideo
            dropLoad.isNotBlank() -> dropLoad
            else -> url
        }

        return newMovieLoadResponse(title, url, TvType.Movie, link) {
            this.posterUrl = poster
        }
    }

    private suspend fun loadSeries(url: String, doc: Document): LoadResponse {
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Serie TV"
        val poster = doc.selectFirst(".movie-poster img")?.attr("src")

        val episodes = mutableListOf<Episode>()

        doc.select(".down-episode").forEach { ep ->
            val superVideo = ep.select("a[href*=\"supervideo.cc\"]").attr("href")
            val dropLoad = ep.select("a[href*=\"dropload.co\"]").attr("href")

            val link = when {
                superVideo.isNotBlank() -> superVideo
                dropLoad.isNotBlank() -> dropLoad
                else -> return@forEach
            }

            episodes += newEpisode(link) {
                this.name = "Episodio"
                this.posterUrl = poster
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return loadExtractor(data, subtitleCallback, callback)
    }
}
