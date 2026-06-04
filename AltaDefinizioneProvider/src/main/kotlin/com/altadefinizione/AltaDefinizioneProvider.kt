package com.altadefinizione

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class AltaDefinizioneProvider : MainAPI() {
    override var mainUrl = "https://altadefinizione.casino"
    override var name = "AltaDefinizione"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val homePages = mutableListOf<HomePageList>()

        val items = document.select(".box-film, .movie-item, .post").mapNotNull {
            it.toSearchResult()
        }

        if (items.isNotEmpty()) {
            homePages.add(HomePageList("Ultime Uscite", items))
        }

        return HomePageResponse(homePages, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select(".box-film, .movie-item, .post").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.select("h1").text().trim()
        val poster = document.select(".poster img, .movie-poster img").attr("src")
        val plot = document.select(".plot, .story, #description").text().trim()
        
        val isTvSeries = document.select(".episodes, .season-list, #links-series").isNotEmpty()

        return if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            
            document.select(".episode-element, .links-episodes a").forEachIndexed { index, element ->
                val epUrl = element.attr("href")
                val epName = element.text().trim()
                episodes.add(
                    Episode(
                        data = epUrl,
                        name = if (epName.isNotEmpty()) epName else "Episodio ${index + 1}"
                    )
                )
            }
            
            TvSeriesLoadResponse(
                name = title,
                url = url,
                apiName = this.name,
                type = TvType.TvSeries,
                posterUrl = poster,
                plot = plot,
                episodes = episodes
            )
        } else {
            MovieLoadResponse(
                name = title,
                url = url,
                apiName = this.name,
                type = TvType.Movie,
                dataUrl = url,
                posterUrl = poster,
                plot = plot
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCouchtuner: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Individua i nodi iframe o link che contengono url riferibili a vidxgo
        document.select("iframe[src*=\"vidxgo\"], a[href*=\"vidxgo\"], iframe[data-src*=\"vidxgo\"]").forEach { element ->
            val iframeUrl = element.attr("src").ifEmpty { element.attr("data-src") }.ifEmpty { element.attr("href") }
            
            if (iframeUrl.isNotEmpty()) {
                // Esegue l'estrazione delegando all'istanza di VidxGoExtractor registrata
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.select(".title, h2, h3").text().trim()
        val href = this.select("a").attr("href")
        val posterUrl = this.select("img").attr("src")

        if (title.isEmpty() || href.isEmpty()) return null

        return MovieSearchResponse(
            name = title,
            url = fixUrl(href),
            apiName = this@AltaDefinizioneProvider.name,
            type = TvType.Movie,
            posterUrl = fixUrlNull(posterUrl)
        )
    }
}
