package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor // Import corretto del metodo di estensione
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import org.jsoup.nodes.Document

class OnlineSerieTvProvider : MainAPI() {
    override var mainUrl = "https://onlineserietv.lol"
    override var name = "OnlineSerieTv"
    override val hasMainPage = true
    override var lang = "it"
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Film",
        "$mainUrl/serie-tv/" to "Serie TV",
        "$mainUrl/serie-tv/animazione/" to "Cartoni & Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
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
        val results = mutableListOf<SearchResponse>()
        val maxPagesToSearch = 10 // Puoi aumentare o diminuire questo limite a piacimento

        for (page in 1..maxPagesToSearch) {
            try {
                // Costruiamo l'URL per la pagina corrente. Esempio: https://onlineserietv.lol/page/2/?s=query
                val url = if (page == 1) "$mainUrl/?s=$query" else "$mainUrl/page/$page/?s=$query"
                val response = app.get(url)
                
                // Se la pagina non esiste (es. 404), ci fermiamo
                if (response.code != 200) break
                
                val document = response.document
                val initialCount = results.size

                // 1. Parsing dei risultati di ricerca (struttura .movie)
                document.select(".movie").forEach { element ->
                    val title = element.selectFirst("h2")?.text() ?: return@forEach
                    val targetUrl = element.selectFirst(".imagen a")?.attr("href") ?: element.selectFirst("a")?.attr("href") ?: return@forEach
                    val poster = element.selectFirst("img")?.attr("src")

                    val type = if (targetUrl.contains("/film/")) TvType.Movie else TvType.TvSeries

                    results.add(
                        newMovieSearchResponse(title, targetUrl, TvType.Movie) {
                            this.posterUrl = poster
                            this.type = type
                        }
                    )
                }
                
                // 2. Fallback per risultati strutturati come uagb-post
                document.select(".uagb-post__inner-wrap").forEach { element ->
                    val titleEl = element.selectFirst(".uagb-post__title a")
                    val title = titleEl?.text() ?: return@forEach
                    val targetUrl = titleEl.attr("href")
                    val poster = element.selectFirst(".uagb-post__image img")?.attr("src")
                    
                    val type = if (targetUrl.contains("/film/")) TvType.Movie else TvType.TvSeries

                    results.add(
                        newMovieSearchResponse(title, targetUrl, TvType.Movie) {
                            this.posterUrl = poster
                            this.type = type
                        }
                    )
                }

                // Se in questa pagina non abbiamo trovato alcun elemento nuovo, 
                // significa che siamo andati oltre l'ultima pagina disponibile. Interrompiamo il ciclo.
                if (results.size == initialCount) {
                    break
                }

            } catch (e: Exception) {
                // In caso di errore di rete su una pagina successiva, interrompiamo il ciclo 
                // per salvare i risultati ottenuti fino a quel momento
                break
            }
        }

        // Ritorna la lista combinata di tutte le pagine scansionate, ripulita da eventuali duplicati
        return results.distinctBy { it.url }
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
            val episodesList = mutableListOf<Episode>()
            var epCount = 1
            
            document.select("table tr, div.data-content a, td a").forEach { element ->
                val link = element.attr("href")
                if (link.isNotBlank() && (link.contains("uprot") || link.contains("stream") || link.contains("tape") || link.contains("flexy"))) {
                    val rowText = element.parents().select("tr").first()?.selectFirst("td")?.text() ?: element.text()
                    
                    val match = "(\\d+)x(\\d+)".toRegex().find(rowText)
                    val season = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val episode = match?.groupValues?.get(2)?.toIntOrNull() ?: epCount++

                    episodesList.add(
                        newEpisode(link) {
                            this.name = "Episodio $episode"
                            this.season = season
                            this.episode = episode
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
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
        if (data.contains("/film/")) {
            val document = app.get(data).document
            document.select("a").forEach { element ->
                val link = element.attr("href")
                if (link.contains("uprot") || link.contains("stream") || link.contains("tape") || link.contains("flexy")) {
                    // Rimosso ExtractorApi. e invocato direttamente il metodo globale
                    loadExtractor(link, mainUrl, subtitleCallback, callback)
                }
            }
        } else {
            // Rimosso ExtractorApi. e invocato direttamente il metodo globale
            loadExtractor(data, mainUrl, subtitleCallback, callback)
        }
        return true
    }
}
