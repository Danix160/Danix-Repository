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

        // FIX RIGA 38: Usiamo l'helper corretto al posto del costruttore deprecato
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
        val isSerie = genres.contains("serie tv") || card.selectFirst(".se_num") != null
        val type = if (isSerie) TvType.TvSeries else TvType.Movie

        // FIX RIGA 55: Evitiamo il costruttore privato di Score usando la notazione ad intero se supportata, 
        // oppure forzando la conversione corretta. 
        val ratingRaw = card.selectFirst(".ml-imdb b")?.text()?.trim()
        val calculatedScore = ratingRaw?.toFloatOrNull()?.let { (it * 10).toInt() } 

        val qualityRaw = card.selectFirst(".trdublaj")?.text()?.trim() ?: "HD"
        val quality = getQualityFromString(qualityRaw)

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = posterUrl
                // Se l'SDK vuole un intero (base 1000) o un Float, assegniamo direttamente il valore convertito
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

        // Riutilizziamo la struttura nativa dei boxgrid usata dal tema anche nella ricerca
        return document.select("div.boxgrid").mapNotNull {
            parseCard(it)
        }
    }

    // 3. DETTAGLI DELLA PAGINA
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1, .mvi-desc h3")?.text()?.trim() ?: return null
        
        // Controllo incrociato per il poster (gestisce sia lazyload che src classico)
        val imgElement = document.selectFirst(".poster img, .movie-poster img, .mvi-thumb img")
        val poster = imgElement?.attr("data-src")?.ifBlank { imgElement.attr("src") }?.let { fixUrl(it) }
        
        val plot = document.selectFirst(".plot, .story, #description, .description, .mvi-desc .p-ftext")?.text()?.trim()
        
        // Verifica se si tratta di una Serie TV controllando i tab degli episodi o l'URL
        val isTvSeries = url.contains("/serie-tv/") || document.select(".episodes, .season-list, #links-series, .les-title").isNotEmpty()

        return if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            
            // Selettore flessibile per catturare i link degli episodi del tema PsyPlay/Darktemplate
            document.select(".episode-element, .links-episodes a, .les-content a").forEachIndexed { index, element ->
                val epUrl = element.attr("href")
                if (!epUrl.isNullOrBlank()) {
                    val epName = element.text().trim()
                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = if (epName.isNotEmpty()) epName else "Episodio ${index + 1}"
                        }
                    )
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
        isCdn: Boolean, // Aggiornato per riflettere il boolean corretto dell'SDK base
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Cattura gli iframe o i player video (sia con src che con data-src del lazyload)
        document.select("iframe[src*=\"vidxgo\"], a[href*=\"vidxgo\"], iframe[data-src*=\"vidxgo\"], iframe[src*=\"embed\"]").forEach { element ->
            val iframeUrl = element.attr("data-src").ifEmpty { element.attr("src") }.ifEmpty { element.attr("href") }
            
            if (iframeUrl.isNotEmpty()) {
                loadExtractor(fixUrl(iframeUrl), data, subtitleCallback, callback)
            }
        }

        return true
    }
}
