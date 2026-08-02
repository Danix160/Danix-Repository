package com.altadefinizione

import com.altadefinizione.extractor.VidxGoExtractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AltaDefinizioneProvider : MainAPI() {

    override var mainUrl = "https://altadefinizionex.co"
    override var name = "AltaDefinizione"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ---------------------------------------------------------
    // MAIN PAGE (Cloudstream 4.x)
    // ---------------------------------------------------------
    override suspend fun getMainPage(): HomePageResponse {
        val doc = app.get(mainUrl).document
        val lists = ArrayList<HomePageList>()

        val slider = doc.select("#slider .swiper-slide").mapNotNull {
            val link = it.selectFirst(".slide-title a")?.absUrl("href") ?: return@mapNotNull null
            val title = it.selectFirst(".slide-title a")?.text() ?: "Senza titolo"
            val poster = it.selectFirst("img.layer-image")?.absUrl("src")

            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster
            }
        }

        lists.add(HomePageList("Slider", slider))

        return newHomePageResponse(lists)
    }

    // ---------------------------------------------------------
    // SEARCH (Cloudstream 4.x)
    // ---------------------------------------------------------
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

    // ---------------------------------------------------------
    // LOAD (Cloudstream 4.x)
    // ---------------------------------------------------------
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
            return newMovieLoadResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.plot = plot
                this.dataUrl = streamUrl
            }
        }

        // Serie
        val episodes = doc.select(".episode-item").mapNotNull {
            val epLink = it.selectFirst("a")?.absUrl("href") ?: return@mapNotNull null
            val epTitle = it.selectFirst(".episode-title")?.text() ?: "Episodio"
            val season = it.attr("data-season").toIntOrNull() ?: 1
            val episode = it.attr("data-episode").toIntOrNull() ?: 1

            newEpisode(epLink) {
                this.name = epTitle
                this.season = season
                this.episode = episode
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes)
    }

    // ---------------------------------------------------------
    // LOAD LINKS (Cloudstream 4.x)
    // ---------------------------------------------------------
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
