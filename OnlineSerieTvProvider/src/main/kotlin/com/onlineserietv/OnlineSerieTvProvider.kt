package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor 
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
        "$mainUrl/serie-tv-generi/animazione/" to "Cartoni & Anime"
    )

    private val omdbApiKey = "d6f266ee"
    private val omdbBaseUrl = "https://www.omdbapi.com/"

    /**
     * Cerca l'opera su OMDb tramite titolo e restituisce l'ID IMDb (ttXXXXXXX) se trovato.
     */
    private suspend fun getImdbIdViaOmdb(title: String, isTv: Boolean): String? {
        val type = if (isTv) "series" else "movie"
        val url = "$omdbBaseUrl?apikey=$omdbApiKey&s=${java.net.URLEncoder.encode(title, "UTF-8")}&type=$type"
        
        return try {
            val response = app.get(url).text
            "\"imdbID\":\"(tt\\d+)\"".toRegex().find(response)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Scarica l'intero JSON di una specifica stagione per estrarre le locandine in blocco.
     */
    private suspend fun getSeasonEpisodesPosters(imdbId: String?, seriesTitle: String, season: Int): Map<Int, String> {
        val postersMap = mutableMapOf<Int, String>()
        val queryParam = if (imdbId != null) "i=$imdbId" else "t=${java.net.URLEncoder.encode(seriesTitle, "UTF-8")}"
        val url = "$omdbBaseUrl?apikey=$omdbApiKey&$queryParam&Season=$season"

        try {
            val response = app.get(url).text
            // Trova tutti i blocchi degli episodi nel JSON della stagione
            val episodeBlocks = """\{"Title"[^}]+""".toRegex().findAll(response)
            
            episodeBlocks.forEach { block ->
                val epText = block.value
                val epNum = "\"Episode\":\"(\\d+)\"".toRegex().find(epText)?.groupValues?.get(1)?.toIntOrNull()
                val posterLink = "\"Poster\":\"([^\"]+)\"".toRegex().find(epText)?.groupValues?.get(1)
                
                if (epNum != null && posterLink != null && posterLink != "N/A" && posterLink.startsWith("http")) {
                    postersMap[epNum] = posterLink
                }
            }
        } catch (_: Exception) {}
        
        return postersMap
    }

    /**
     * Rimuove l'anno, tag come STAGIONE e suffissi del sito,
     * ma mantiene e formatta la dicitura SUB ITA alla fine del titolo.
     */
    private fun cleanTitle(title: String): String {
        val isSubIta = title.contains("(?i)\\bSUB[- ]?ITA\\b".toRegex())

        var cleaned = title
            .replace(" in streaming - OnlineSerieTv", "")
            .replace("(?i)\\bSUB[- ]?ITA\\b".toRegex(), "")
            .replace("(?i)\\b(ITA|STAGIONE \\d+|STAGIONE)\\b".toRegex(), "")
            .replace("""\s*[\(\[-]?\s*(19|20)\d{2}\s*[\)\]-]?\s*""".toRegex(), " ")
            .replace("""\s*[-–—:|]+\s*$""".toRegex(), "") 
            .replace("""^\s*[-–—:|]+\s*""".toRegex(), "")
            .replace("""\s+""".toRegex(), " ")
            .trim()

        if (isSubIta) {
            cleaned = "$cleaned SUB ITA"
        }

        return cleaned
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(request.data).document
        val homeResults = mutableListOf<SearchResponse>()

        // Selettore 1: Struttura dei blocchi in Home (uagb-post)
        document.select(".uagb-post__inner-wrap").forEach { element ->
            val titleEl = element.selectFirst(".uagb-post__title a")
            val rawTitle = titleEl?.text() ?: return@forEach
            val title = cleanTitle(rawTitle)
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
            val rawTitle = element.selectFirst("h2")?.text() ?: return@forEach
            val title = cleanTitle(rawTitle)
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
        val maxPagesToSearch = 10

        for (page in 1..maxPagesToSearch) {
            try {
                val url = if (page == 1) "$mainUrl/?s=$query" else "$mainUrl/page/$page/?s=$query"
                val response = app.get(url)
                
                if (response.code != 200) break
                
                val document = response.document
                val initialCount = results.size

                // 1. Parsing dei risultati di ricerca (struttura .movie)
                document.select(".movie").forEach { element ->
                    val rawTitle = element.selectFirst("h2")?.text() ?: return@forEach
                    val title = cleanTitle(rawTitle)
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
                    val rawTitle = titleEl?.text() ?: return@forEach
                    val title = cleanTitle(rawTitle)
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

                if (results.size == initialCount) {
                    break
                }

            } catch (e: Exception) {
                break
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val rawTitle = document.selectFirst("h1")?.text() 
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Senza Titolo"
        
        val title = cleanTitle(rawTitle)
        
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".imagen img")?.attr("src")

        var description: String? = null
        
        val tramaElement = document.select("b:contains(Trama), strong:contains(Trama)").firstOrNull()
        if (tramaElement != null) {
            description = tramaElement.nextElementSibling()?.selectFirst("p")?.text()
                ?: tramaElement.nextElementSiblings().firstOrNull { it.tagName() == "p" }?.text()
        }

        if (description.isNullOrBlank()) {
            description = document.select("div.tsll p, .entry-content p, .post-content p, div.post p")
                .map { it.text().trim() }
                .firstOrNull { it.length > 30 && !it.contains("generato") && !it.contains("creata da") && !it.contains("visto in streaming") }
        }

        if (description.isNullOrBlank()) {
            description = document.selectFirst("meta[property=og:description]")?.attr("content")
        }

        val finalDescription = description?.replace("(?i)^Trama:\\s*".toRegex(), "")?.trim()

        return if (url.contains("/serietv/") || url.contains("/serie-tv/")) {
            val episodesList = mutableListOf<Episode>()
            var epCount = 1
            
            val imdbId = getImdbIdViaOmdb(title, isTv = true)
            
            // Mappa per salvare le locandine delle stagioni già scaricate ed evitare chiamate doppie
            val seasonsCache = mutableMapOf<Int, Map<Int, String>>()
            
            document.select("table tr, div.data-content a, td a").forEach { element ->
                val link = element.attr("href")
                if (link.isNotBlank() && (link.contains("uprot") || link.contains("stream") || link.contains("tape") || link.contains("flexy"))) {
                    val rowText = element.parents().select("tr").first()?.selectFirst("td")?.text() ?: element.text()
                    
                    val match = "(\\d+)x(\\d+)".toRegex().find(rowText)
                    val season = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val episode = match?.groupValues?.get(2)?.toIntOrNull() ?: epCount++

                    // Se la stagione non è in cache, scarica tutti i suoi poster in una volta sola
                    if (!seasonsCache.containsKey(season)) {
                        seasonsCache[season] = getSeasonEpisodesPosters(imdbId, title, season)
                    }
                    
                    val episodePoster = seasonsCache[season]?.get(episode) ?: poster

                    episodesList.add(
                        newEpisode(link) {
                            this.name = "Episodio $episode"
                            this.season = season
                            this.episode = episode
                            this.posterUrl = episodePoster
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.plot = finalDescription
                if (imdbId != null) this.syncData = mapOf("imdb" to imdbId)
            }
        } else {
            val imdbId = getImdbIdViaOmdb(title, isTv = false)
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = finalDescription
                if (imdbId != null) this.syncData = mapOf("imdb" to imdbId)
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
                    loadExtractor(link, mainUrl, subtitleCallback, callback)
                }
            }
        } else {
            loadExtractor(data, mainUrl, subtitleCallback, callback)
        }
        return true
    }
}
