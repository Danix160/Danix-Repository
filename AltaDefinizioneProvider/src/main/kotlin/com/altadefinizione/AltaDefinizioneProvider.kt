package com.altadefinizione

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class AltaDefinizioneProvider : MainAPI() {

    override var mainUrl = "https://altadefinizionex.co"
    override var name = "AltaDefinizione"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ---------------------------------------------------------
    // HOME PAGE
    // ---------------------------------------------------------
    override suspend fun getMainPage(): HomePageResponse {
        val doc = app.get(mainUrl).document
        val lists = ArrayList<HomePageList>()

        // SLIDER
        val slider = doc.select("#slider .swiper-slide").mapNotNull {
            val link = it.selectFirst(".slide-title a")?.absUrl("href") ?: return@mapNotNull null
            val title = it.selectFirst(".slide-title a")?.text() ?: "Senza titolo"
            val poster = it.selectFirst("img.layer-image")?.absUrl("src")

            MovieSearchResponse(
                title,
                link,
                this.name,
                TvType.Movie,
                poster
            )
        }
        lists.add(HomePageList("Slider", slider))

        // TRENDING
        val trending = doc.select("#trending .swiper-slide").mapNotNull {
            val link = it.selectFirst(".movie-poster a")?.absUrl("href") ?: return@mapNotNull null
            val poster = it.selectFirst(".movie-poster img")?.absUrl("src")

            MovieSearchResponse(
                "",
                link,
                this.name,
                TvType.Movie,
                poster
            )
        }
        lists.add(HomePageList("Titoli del momento", trending))

        // ULTIMI INSERITI
        val latest = doc.select(".movie[data-link]").mapNotNull {
            val link = it.attr("data-link")
            val title = it.attr("data-title").ifBlank { "Senza titolo" }
            val poster = it.selectFirst(".movie-poster img")?.absUrl("src")

            MovieSearchResponse(
                title,
                link,
                this.name,
                TvType.Movie,
                poster
            )
        }
        if (latest.isNotEmpty()) {
            lists.add(HomePageList("Ultimi inseriti", latest))
        }

        return HomePageResponse(lists)
    }

    // ---------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?do=search&subaction=search&story=$query"
        val doc = app.get(url).document

        return doc.select(".movie").mapNotNull {
            val link = it.attr("data-link")
            val title = it.attr("data-title").ifBlank { "Senza titolo" }
            val poster = it.selectFirst(".movie-poster img")?.absUrl("src")

            MovieSearchResponse(
                title,
                link,
                this.name,
                TvType.Movie,
                poster
            )
        }
    }

    // ---------------------------------------------------------
    // LOAD (FILM + SERIE)
    // ---------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.movie_entry-title")?.text() ?: return null
        val poster = doc.selectFirst(".movie_entry-poster")?.attr("data-src")
        val backdrop = doc.selectFirst(".player img.layer-image")?.absUrl("src")

        // IMDB → VidxGo ID
        val imdb = backdrop?.substringAfter("tt")?.substringBefore(".")
        val streamUrl = imdb?.let { "https://v.vidxgo.co/$it" }

        // Durata
        val duration = doc.select(".movie_entry-info .meta-list span")
            .firstOrNull { it.text().contains("min") }
            ?.text()
            ?.replace(" min", "")
            ?.toIntOrNull()

        // Trama
        val plot = doc.selectFirst(".movie_entry-description")?.text()

        // Se è un film
        if (doc.select(".player").isNotEmpty()) {
            return MovieLoadResponse(
                title = title,
                url = url,
                apiName = this.name,
                dataUrl = streamUrl,
                posterUrl = poster,
                plot = plot,
                duration = duration
            )
        }

        // Se è una serie
        val episodes = doc.select(".episode-item").mapNotNull {
            val epLink = it.selectFirst("a")?.absUrl("href") ?: return@mapNotNull null
            val epTitle = it.selectFirst(".episode-title")?.text() ?: "Episodio"
            val season = it.attr("data-season").toIntOrNull() ?: 1
            val episode = it.attr("data-episode").toIntOrNull() ?: 1

            Episode(
                epLink,
                epTitle,
                season,
                episode
            )
        }

        return TvSeriesLoadResponse(
            title = title,
            url = url,
            apiName = this.name,
            posterUrl = poster,
            episodes = episodes
        )
    }

    // ---------------------------------------------------------
    // LOAD LINKS (usa il tuo extractor)
    // ---------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        VidxGoExtractor().getUrl(
              data,
              mainUrl,
              subtitleCallback,
              callback
          )
    }
}
