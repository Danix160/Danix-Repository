package com.altadefinizione

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class AltaDefinizioneProvider : MainAPI() {
    override var mainUrl = "https://altadefinizione-01.forum"
    override var name = "AltaDefinizione"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: HomePageRequest): HomePageList? {
    // Carichiamo l'HTML della home (valido solo per la pagina 1, dato che i caroselli di solito cambiano o spariscono nelle pagine successive)
    val document = app.get(mainUrl).document
    val homePageList = mutableListOf<HomePageRequestData>()

    // 1. Lista "Al Cinema Ora" (Estratta dal carosello dello Slider)
    val sliderItems = document.select("div#slider div.boxgrid")
    if (sliderItems.isNotEmpty()) {
        val sliderResults = sliderItems.mapNotNull { card ->
            parseCard(card)
        }
        if (sliderResults.isNotEmpty()) {
            homePageList.add(HomePageRequestData("Al Cinema Ora", sliderResults, isMainEntries = true))
        }
    }

    // 2. Lista "Ultimi Film Aggiunti" (La griglia standard sotto lo slider)
    // Se la griglia principale usa gli stessi nodi 'div.boxgrid' ma FUORI da 'div#slider'
    val gridItems = document.select("div.son_eklenen div.boxgrid:not(.slidercaprion)")
    // Nota: Se fuori dal carosello usano classi diverse, adatta il selettore (es. "div.boxgrid:not(#slider div.boxgrid)")
    if (gridItems.isNotEmpty()) {
        val gridResults = gridItems.mapNotNull { card ->
            parseCard(card)
        }
        if (gridResults.isNotEmpty()) {
            homePageList.add(HomePageRequestData("Ultimi Arrivi", gridResults, isMainEntries = false))
        }
    }

    return newHomePageList(homePageList, false)
}
    private fun parseCard(card: org.jsoup.nodes.Element): SearchResponse? {
    // Estrazione Titolo e URL (il tag può essere h2 o h3 a seconda del CSS, cerchiamo l'anchor direttamente nella maschera)
    val titleElement = card.selectFirst(".ml-mask h3 a, .ml-mask h2 a, h2 a, h3 a")
    val title = titleElement?.text()?.trim() ?: return null
    val url = titleElement.attr("href") ?: return null

    // Gestione Immagine (Lazyload)
    val imgElement = card.selectFirst("img")
    val posterRaw = imgElement?.attr("data-src")?.ifBlank { imgElement.attr("src") } ?: ""
    val posterUrl = fixUrl(posterRaw)

    // Riconoscimento automatico tra Film e Serie TV
    // Nel tuo HTML, tag come "Spider-Noir" o "Euphoria" hanno il genere <a href=".../serie-tv/">Serie TV</a>
    val genres = card.select(".ml-cat a").map { it.text().lowercase() }
    val isSerie = genres.contains("serie tv") || card.selectFirst(".se_num") != null
    val type = if (isSerie) TvType.TvSeries else TvType.Movie

    // Estrazione del voto (es: 7.9)
    val ratingRaw = card.selectFirst(".ml-imdb b")?.text()?.trim()
    val rating = ratingRaw?.toRatingInt()

    // Estrazione della qualità (se presente, altrimenti default HD)
    val qualityRaw = card.selectFirst(".trdublaj")?.text()?.trim() ?: "HD"
    val quality = getQualityFromString(qualityRaw)

    // Creazione della SearchResponse corretta per CloudStream
    return if (type == TvType.TvSeries) {
        newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.rating = rating
            this.quality = quality
        }
    } else {
        newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = posterUrl
            this.rating = rating
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
