package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import org.jsoup.nodes.Document

class OnlineSerieTvProvider : MainAPI() {
    override var mainUrl = "https://onlineserietv.lol"
    override var name = "OnlineSerieTv"
    override val hasMainPage = true
    override var lang = "it"
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ultime Serie e Film",
        "$mainUrl/serie-tv/" to "Serie TV",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val homeResults = mutableListOf<SearchResponse>()

        // Selettore 1: Struttura dei blocchi in Home (uagb-post)
        document.select(".uagb-post__inner-wrap").forEach { element ->
            val titleEl = element.selectFirst(".uagb-post__title a")
            val title = titleEl?.text() ?: return@forEach
            val url = titleEl.attr("href")
            val poster = element.selectFirst(".uagb-post__image img")?.attr("src")
            
            val type = if (url.contains("/film/")) TvType.Movie else TvType.TvSeries

            homeResults.add(
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                    this.type = type
                }
            )
        }

        // Selettore 2: Struttura classica dei blocchi standard (.movie)
        document.select(".movie").forEach { element ->
            val title = element.selectFirst("h2")?.text() ?: return@forEach
            val linkEl = element.selectFirst(".imagen a") ?: element.selectFirst("a")
            val url = linkEl?.attr("href") ?: return@forEach
            val poster = element.selectFirst("img")?.attr("src")

            val type = if (url.contains("/film/")) TvType.Movie else TvType.TvSeries

            homeResults.add(
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                    this.type = type
                }
            )
        }

        return newHomePageResponse(request.name, homeResults)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Il sito usa la query di ricerca classica di WordPress ?s=query
        val document = app.get("$mainUrl/?s=$query").document
        val results = mutableListOf<SearchResponse>()

        // Parsing dei risultati di ricerca (struttura .movie da cerca.txt)
        document.select(".movie").forEach { element ->
            val title = element.selectFirst("h2")?.text() ?: return@forEach
            val url = element.selectFirst(".imagen a")?.attr("href") ?: element.selectFirst("a")?.attr("href") ?: return@forEach
            val poster = element.selectFirst("img")?.attr("src")

            val type = if (url.contains("/film/")) TvType.Movie else TvType.TvSeries

            results.add(
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                    this.type = type
                }
            )
        }
        
        // Fallback per risultati strutturati come uagb-post
        document.select(".uagb-post__inner-wrap").forEach { element ->
            val titleEl = element.selectFirst(".uagb-post__title a")
            val title = titleEl?.text() ?: return@forEach
            val url = titleEl.attr("href")
            val poster = element.selectFirst(".uagb-post__image img")?.attr("src")
            
            val type = if (url.contains("/film/")) TvType.Movie else TvType.TvSeries

            results.add(
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                    this.type = type
                }
            )
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text() 
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.replace(" in streaming - OnlineSerieTv", "") 
            ?: "Senza Titolo"
        
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".imagen img")?.attr("src")
            
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst(".tsll p")?.text()

        return if (url.contains("/serietv/")) {
            // Struttura Serie TV: Generiamo gli episodi partendo dalle tabelle dei link
            val episodesList = mutableListOf<Episode>()
            
            // Seleziona tutte le righe della tabella o i link che contengono la dicitura Stagione/Episodio (es. 01x01)
            var epCount = 1
            document.select("table tr, div.data-content a, td a").forEach { element ->
                val link = element.attr("href")
                // Filtriamo per i link di streaming esterni (es. uprot, streamtape, maxstream, flexy)
                if (link.isNotBlank() && (link.contains("uprot") || link.contains("stream") || link.contains("tape") || link.contains("flexy"))) {
                    // Proviamo ad estrarre il nome dell'episodio dal testo circostante o dal tag della riga
                    val rowText = element.parents().select("tr").first()?.selectFirst("td")?.text() ?: element.text()
                    
                    // Cerchiamo pattern tipo 01x02 per capire stagione ed episodio
                    val match = "(\\d+)x(\\d+)".toRegex().find(rowText)
                    val season = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val episode = match?.groupValues?.get(2)?.toIntOrNull() ?: epCount++

                    // Per evitare duplicati dello stesso episodio con host differenti, 
                    // usiamo come data il link completo inserendolo nel campo data dell'episodio
                    episodesList.add(
                        Episode(
                            data = link,
                            name = "Episodio $episode",
                            season = season,
                            episode = episode
                        )
                    )
                }
            }

            // Raggruppiamo per stagione/episodio se necessario o passiamo la lista pulita
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            // Struttura Film
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Se stiamo gestendo una serie tv, 'data' conterrà già il link dell'host estratto dall'episodio.
        // Se è un film, 'data' sarà l'URL della pagina del film, quindi dobbiamo estrarre i link dalla pagina.
        if (data.contains("/film/")) {
            val document = app.get(data).document
            document.select("a").forEach { element ->
                val link = element.attr("href")
                if (link.contains("uprot") || link.contains("stream") || link.contains("tape") || link.contains("flexy")) {
                    ExtractorApi.loadExtractor(link, mainUrl, subtitleCallback, callback)
                }
            }
        } else {
            // È un link diretto a un host/redirector estratto precedentemente dall'episodio
            ExtractorApi.loadExtractor(data, mainUrl, subtitleCallback, callback)
        }
        return true
    }
}
