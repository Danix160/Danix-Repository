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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val items = doc.select(".movie-poster")

        val list = items.mapNotNull { item ->
            val link = item.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = item.parent()?.selectFirst(".movie-title a")?.text()?.trim()
                ?: return@mapNotNull null

            if (link.contains("/serie-tv/")) {
                newTvSeriesSearchResponse(title, link, TvType.TvSeries)
            } else {
                newMovieSearchResponse(title, link, TvType.Movie)
            }
        }

        return newHomePageResponse(
            listOf(HomePageList("In evidenza", list)),
            false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?do=search&subaction=search&story=$query"
        val doc = app.get(url).document

        return doc.select(".movie-poster").mapNotNull { item ->
            val link = item.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = item.parent()?.selectFirst(".movie-title a")?.text()?.trim()
                ?: return@mapNotNull null

            if (link.contains("/serie-tv/")) {
                newTvSeriesSearchResponse(title, link, TvType.TvSeries)
            } else {
                newMovieSearchResponse(title, link, TvType.Movie)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        return if (url.contains("/serie-tv/")) loadSeries(url, doc) else loadMovie(url, doc)
    }

    private suspend fun loadMovie(url: String, doc: Document): LoadResponse {
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Film"

        val superVideo = doc.select("a[href*=\"supervideo.cc\"]").attr("href")
        val dropLoad = doc.select("a[href*=\"dropload.co\"]").attr("href")

        val link = when {
            superVideo.isNotBlank() -> superVideo
            dropLoad.isNotBlank() -> dropLoad
            else -> url
        }

        return newMovieLoadResponse(title, url, TvType.Movie, link)
    }

    private suspend fun loadSeries(url: String, doc: Document): LoadResponse {
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Serie TV"

        val episodes = mutableListOf<Episode>()

        doc.select(".down-episode").forEach { ep ->
            val superVideo = ep.select("a[href*=\"supervideo.cc\"]").attr("href")
            val dropLoad = ep.select("a[href*=\"dropload.co\"]").attr("href")

            val link = when {
                superVideo.isNotBlank() -> superVideo
                dropLoad.isNotBlank() -> dropLoad
                else -> return@forEach
            }

            episodes += newEpisode(link)
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes)
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
