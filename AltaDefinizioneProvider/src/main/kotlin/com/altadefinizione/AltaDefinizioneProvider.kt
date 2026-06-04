package com.altadefinizione

import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.app

class AltaDefinizioneProvider : MainAPI() {
    override var mainUrl = "https://altadefinizione-01.forum"
    override var name = "AltaDefinizione"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
   override val hasMainPage = true

    // Nella vecchia firma l'override corretto restituisce un HomePageResponse (o List<HomePageResponse> a seconda dell'SDK)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageResponse>()

        // 1. Carosello "Al Cinema Ora"
        val sliderItems = document.select("div#slider div.boxgrid")
        if (sliderItems.isNotEmpty()) {
            val sliderResults = sliderItems.mapNotNull { card ->
                parseCard(card)
            }
            if (sliderResults.isNotEmpty()) {
                homePageList.add(HomePageResponse("Al Cinema Ora", sliderResults))
            }
        }

        // 2. Griglia "Ultimi Arrivi"
        val gridItems = document.select("div.son_eklenen div.boxgrid:not(.slidercaprion)")
        if (gridItems.isNotEmpty()) {
            val gridResults = gridItems.mapNotNull { card ->
                parseCard(card)
            }
            if (gridResults.isNotEmpty()) {
                homePageList.add(HomePageResponse("Ultimi Arrivi", gridResults))
            }
        }

        // Restituisce direttamente la lista se l'SDK prevede List<HomePageResponse>, 
        // oppure passalo all'istanza corretta se l'SDK richiede l'incapsulamento.
        return HomePageResponse(homePageList)
    }

    private fun parseCard(card: org.jsoup.nodes.Element): SearchResponse? {
        val titleElement = card.selectFirst(".ml-mask h3 a, .ml-mask h2 a, h2 a, h3 a")
        val title = titleElement?.text()?.trim() ?: return null
        val url = titleElement.attr("href") ?: return null

        val imgElement = card.selectFirst("img")
        val posterRaw = imgElement?.attr("data-src")?.ifBlank { imgElement.attr("src") } ?: ""
        val posterUrl = fixUrl(posterRaw)

        val genres = card.select(".ml-cat a").map { it.text().lowercase() }
        val isSerie = genres.contains("serie tv") || card.selectFirst(".se_num") != null
        val type = if (isSerie) TvType.TvSeries else TvType.Movie

        // FIX RATING DEPRECATO: Calcoliamo il punteggio su base 1000 usando l'API corretta (score)
        val ratingRaw = card.selectFirst(".ml-imdb b")?.text()?.trim()
        val floatRating = ratingRaw?.toFloatOrNull()
        val calculatedScore = if (floatRating != null) (floatRating * 100).toInt() else null

        val qualityRaw = card.selectFirst(".trdublaj")?.text()?.trim() ?: "HD"
        val quality = getQualityFromString(qualityRaw)

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.score = calculatedScore // Sostituito .rating con .score richiesto dal nuovo compiler
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = posterUrl
                this.score = calculatedScore // Sostituito .rating con .score richiesto dal nuovo compiler
                this.quality = quality
            }
        }
    }

    // 2. RICERCA
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select(".box-film, .movie-item, .post").mapNotNull {
            it.toSearchResult()
        }
    }

    // 3. DETTAGLI DELLA PAGINA (Risolto l'errore newEpisode)
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
                
                // Utilizzo corretto di newEpisode tramite configuratore lambda
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = if (epName.isNotEmpty()) epName else "Episodio ${index + 1}"
                    }
                )
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // 4. ESTRAZIONE LINK
    override suspend fun loadLinks(
        data: String,
        isCouchtuner: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        document.select("iframe[src*=\"vidxgo\"], a[href*=\"vidxgo\"], iframe[data-src*=\"vidxgo\"]").forEach { element ->
            val iframeUrl = element.attr("src").ifEmpty { element.attr("data-src") }.ifEmpty { element.attr("href") }
            
            if (iframeUrl.isNotEmpty()) {
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

        val isTv = href.contains("/serie/") || href.contains("/serietv/") || this.select(".badge-tv, .season-tag").isNotEmpty()

        return if (isTv) {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = fixUrlNull(posterUrl)
            }
        } else {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = fixUrlNull(posterUrl)
            }
        }
    }
}
