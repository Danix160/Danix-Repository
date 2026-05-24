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
            val poster = item.selectFirst("img")?.attr("src")

            if (link.contains("/serie-tv/")) {
                TvSeriesSearchResponse(title, link, this.name, TvType.TvSeries, poster)
            } else {
                MovieSearchResponse(title, link, this.name, TvType.Movie, poster)
            }
        }

        return HomePageResponse(listOf(HomePageList("In evidenza", list)), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?do=search&subaction=search&story=$query"
        val doc = app.get(url).document

        return doc.select(".movie-poster").mapNotNull { item ->
            val link = item.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = item.parent()?.selectFirst(".movie-title a")?.text()?.trim()
                ?: return@mapNotNull null
            val poster = item.selectFirst("img")?.attr("src")

            if (link.contains("/serie-tv/")) {
                TvSeriesSearchResponse(title, link, this.name, TvType.TvSeries, poster)
            } else {
                MovieSearchResponse(title, link, this.name, TvType.Movie, poster)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        return if (url.contains("/serie-tv/")) loadSeries(url, doc) else loadMovie(url, doc)
    }

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

        return MovieLoadResponse(
            title,
            url,
            this.name,
            TvType.Movie,
            link,
            poster,
            plot
        )
    }

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

            episodes += Episode(
                link,
                "Episodio $season x $episode",
                season,
                episode,
                thumb
            )
        }

        return TvSeriesLoadResponse(
            title,
            url,
            this.name,
            TvType.TvSeries,
            episodes,
            poster
        )
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
