package com.altadefinizione

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class AltaDefinizioneProvider : MainAPI() {
    override var mainUrl = "https://altadefinizione-01.forum"
    override var name = "AltaDefinizione"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true
    
    // 1. HOME PAGE
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()

        // Carosello "Al Cinema Ora"
        val sliderItems = document.select("div#slider div.boxgrid")
        if (sliderItems.isNotEmpty()) {
            val sliderResults = sliderItems.mapNotNull { card -> parseCard(card) }
            if (sliderResults.isNotEmpty()) {
                homePageList.add(HomePageList("Al Cinema Ora", sliderResults))
            }
        }

        // Griglia "Ultimi Arrivi"
        val gridItems = document.select("div.son_eklenen div.boxgrid:not(.slidercaprion)")
        if (gridItems.isNotEmpty()) {
            val gridResults = gridItems.mapNotNull { card -> parseCard(card) }
            if (gridResults.isNotEmpty()) {
                homePageList.add(HomePageList("Ultimi Arrivi", gridResults))
            }
        }

        return newHomePageResponse(homePageList)
    }

    private fun parseCard(card: Element): SearchResponse? {
        val titleElement = card.selectFirst(".ml-mask h3 a, .ml-mask h2 a, h2 a, h3 a")
        val title = titleElement?.text()?.trim() ?: return null
        val url = titleElement.attr("href") ?: return null

        val imgElement = card.selectFirst("img")
        val posterRaw = imgElement?.attr("data-src")?.ifBlank { imgElement.attr("src") } ?: ""
        val posterUrl = fixUrl(posterRaw)

        val genres = card.select(".ml-cat a").map { it.text().lowercase() }
        val isSerie = genres.contains("serie tv") || card.selectFirst(".se_num") != null || url.contains("-streaming-community")
        val type = if (isSerie) TvType.TvSeries else TvType.Movie

        val ratingRaw = card.selectFirst(".ml-imdb b")?.text()?.trim()
        val calculatedScore = ratingRaw?.toFloatOrNull()?.let { (it * 10).toInt() } 

        val qualityRaw = card.selectFirst(".trdublaj")?.text()?.trim() ?: "HD"
        val quality = getQualityFromString(qualityRaw)

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }
    }
    
    // 2. RICERCA
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select("div.boxgrid").mapNotNull {
            parseCard(it)
        }
    }

    // 3. DETTAGLI DELLA PAGINA (Adattato sull'HTML reale fornito per le Serie)
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Estrae il titolo pulito isolandolo da eventuali diciture tra parentesi come (2019 - In Lavorazione)
        val rawTitle = document.selectFirst("div.single_head h1[itemprop=name], div.single_head h1")?.text()?.trim() ?: return null
        val title = rawTitle.substringBefore("(").trim()
        
        // Recupera il poster dai tag Open Graph della pagina
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.ifBlank { document.selectFirst(".poster img, .movie-poster img")?.attr("src") }?.let { fixUrl(it) }
        
        // Estrae la sinossi direttamente dal meta description dell'HTML analizzato
        val plot = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: document.selectFirst("#main-player p")?.text()?.trim()
        
        // Controllo se l'URL o la struttura delle stagioni indicano una Serie TV
        val episodesContainer = document.select("#episodesList a.ep-item")
        val isTvSeries = url.contains("-streaming-community") || episodesContainer.isNotEmpty()

        return if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            
            episodesContainer.forEach { element ->
                val epUrl = element.attr("href")
                if (!epUrl.isNullOrBlank()) {
                    // Estrarre i dati dall'URL. Es: /8772296/1/2 -> ["", "8772296", "1", "2"]
                    val segments = epUrl.split("/").filter { it.isNotEmpty() }
                    
                    // Verifichiamo che l'URL abbia la struttura attesa (almeno id, stagione ed episodio)
                    if (segments.size >= 3) {
                        val seasonNumber = segments[1].toIntOrNull() ?: 1
                        val episodeNumber = segments[2].toIntOrNull()
                        
                        val epName = element.selectFirst(".ep-name")?.text()?.trim() 
                            ?: "Episodio ${episodeNumber ?: ""}"
                        
                        // Opzionale: puoi estrarre anche il plot e il thumb dell'episodio se vuoi arricchire la UI
                        val epPlot = element.selectFirst(".ep-plot")?.text()?.trim()
                        val epThumb = element.selectFirst("img.ep-thumb")?.attr("src")

                        episodes.add(
                            newEpisode(epUrl) {
                                this.name = epName
                                this.season = seasonNumber
                                this.episode = episodeNumber
                                this.description = epPlot
                                this.posterUrl = epThumb
                            }
                        )
                    }
                }
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

    // 4. ESTRAZIONE LINK VIDEO
    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Intercetta sia iframe che player adibiti allo streaming (incluso l'anchor alternativo)
        document.select("iframe[src*=\"vidxgo\"], a[href*=\"vidxgo\"], iframe[data-src*=\"vidxgo\"], iframe[src*=\"embed\"]").forEach { element ->
            val iframeUrl = element.attr("data-src").ifEmpty { element.attr("src") }.ifEmpty { element.attr("href") }
            
            if (iframeUrl.isNotEmpty()) {
                loadExtractor(fixUrl(iframeUrl), data, subtitleCallback, callback)
            }
        }

        return true
    }
}
