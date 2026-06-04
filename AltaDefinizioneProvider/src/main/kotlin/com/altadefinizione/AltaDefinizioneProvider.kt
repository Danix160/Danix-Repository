package com.altadefinizione

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class AltaDefinizioneProvider : MainAPI() {
    override var mainUrl = "https://altadefinizione.casino" // Sostituisci con il dominio corrente funzionante
    override var name = "AltaDefinizione"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    // 1. HOME PAGE: Genera le liste (Nuove Uscite, Cinema, ecc.) nella schermata principale
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val homePages = mutableListOf<HomePageList>()

        // Esempio generico di parsing dei blocchi di film in Home Page
        val items = document.select(".box-film, .movie-item, .post").mapNotNull {
            it.toSearchResult()
        }

        if (items.isNotEmpty()) {
            homePages.add(HomePageList("Ultime Uscite", items))
        }

        return HomePageResponse(homePages, hasNext = false)
    }

    // 2. RICERCA: Gestisce la barra di ricerca nell'app
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query" // Modifica il parametro di query in base alla struttura del sito
        val document = app.get(searchUrl).document

        return document.select(".box-film, .movie-item, .post").mapNotNull {
            it.toSearchResult()
        }
    }

    // 3. DETTAGLI DELLA PAGINA: Carica la scheda del titolo, la trama, la copertina e gli episodi
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.select("h1").text().trim()
        val poster = document.select(".poster img, .movie-poster img").attr("src")
        val plot = document.select(".plot, .story, #description").text().trim()
        
        // Determina se si tratta di una Serie TV o di un Film cercando la presenza di stagioni/episodi
        val isTvSeries = document.select(".episodes, .season-list, #links-series").isNotEmpty()

        return if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            
            // Logica d'esempio per estrarre gli episodi
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
                dataUrl = url, // Passa l'URL stesso come destinazione per l'estrazione dei link
                posterUrl = poster,
                plot = plot
            )
        }
    }

    // 4. ESTRAZIONE LINK: Trova gli iframe dei lettori video e li passa agli estrattori dedicati
    override suspend fun loadLinks(
        data: String,
        isCouchtuner: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Cerca tutti gli iframe o i link che puntano a VidxGo all'interno della pagina
        document.select("iframe[src*=\"vidxgo\"], a[href*=\"vidxgo\"], iframe[data-src*=\"vidxgo\"]").forEach { element ->
            val iframeUrl = element.attr("src").ifEmpty { element.attr("data-src") }.ifEmpty { element.attr("href") }
            
            if (iframeUrl.isNotEmpty()) {
                // Sfrutta l'architettura di CloudStream per caricare l'estrattore VidxGo personalizzato
                loadExtractor(iframeUrl, data, callback)
            }
        }

        return true
    }

    // Helper Extension per evitare la duplicazione del codice di mapping DOM -> SearchResponse
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.select(".title, h2, h3").text().trim()
        val href = this.select("a").attr("href")
        val posterUrl = this.select("img").attr("src")

        if (title.isEmpty() || href.isEmpty()) return null

        return MovieSearchResponse(
            name = title,
            url = fixUrl(href),
            apiName = this@AltaDefinizioneProvider.name,
            type = TvType.Movie, // Può essere convertito dinamicamente se la card mostra un badge serie
            posterUrl = fixUrlNull(posterUrl)
        )
    }
}
