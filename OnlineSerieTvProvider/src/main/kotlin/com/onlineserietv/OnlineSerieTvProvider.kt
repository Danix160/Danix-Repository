package com.onlineserietv

import android.util.Log
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

    private val cinemetaUrl = "https://v3-cinemeta.strem.io"

    /**
     * Cerca l'opera su Cinemeta tramite titolo e restituisce l'ID IMDb (ttXXXXXXX)
     */
    private suspend fun getImdbIdViaCinemeta(title: String, isTv: Boolean): String? {
        val type = if (isTv) "series" else "movie"
        // Pulizia totale per Cinemeta: via l'anno e via il SUB ITA per non rompere la ricerca
        val cleanQuery = title.replace("(?i)\\bSUB[- ]?ITA\\b".toRegex(), "").trim()
        val url = "$cinemetaUrl/catalog/$type/top/search=${java.net.URLEncoder.encode(cleanQuery, "UTF-8")}.json"
        
        return try {
            val response = app.get(url).text
            val id = "\"id\":\"(tt\\d+)\"".toRegex().find(response)?.groupValues?.get(1)
                ?: "\"imdb_id\":\"(tt\\d+)\"".toRegex().find(response)?.groupValues?.get(1)
            Log.d("Cinemeta-Debug", "ID Cinemeta per '$cleanQuery': $id")
            id
        } catch (e: Exception) {
            Log.e("Cinemeta-Debug", "Errore ricerca ID Cinemeta per '$cleanQuery'", e)
            null
        }
    }

    /**
     * Scarica l'elenco completo di tutti i video (episodi) della serie con i rispettivi poster.
     */
    private suspend fun getCinemetaEpisodesPosters(imdbId: String): Map<String, String> {
        val postersMap = mutableMapOf<String, String>()
        val url = "$cinemetaUrl/meta/series/$imdbId.json"

        try {
            val response = app.get(url).text
            val videoBlocks = """\{"season":[^}]+""".toRegex().findAll(response)
            
            videoBlocks.forEach { block ->
                val text = block.value
                val season = "\"season\":(\\d+)".toRegex().find(text)?.groupValues?.get(1)?.toIntOrNull()
                val episode = "\"episode\":(\\d+)".toRegex().find(text)?.groupValues?.get(1)?.toIntOrNull()
                val thumbnail = "\"thumbnail\":\"([^\"]+)\"".toRegex().find(text)?.groupValues?.get(1)
                
                if (season != null && episode != null && !thumbnail.isNullOrBlank()) {
                    postersMap["${season}_${episode}"] = thumbnail.replace("\\/", "/")
                }
            }
            Log.d("Cinemeta-Debug", "Mappati ${postersMap.size} poster episodi da Cinemeta")
        } catch (e: Exception) {
            Log.e("Cinemeta-Debug", "Errore parsing episodi Cinemeta per $imdbId", e)
        }
        
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

        // Corretto il controllo: il sito usa sia "/serietv/" che "/serie-tv/"
        return if (url.contains("/serietv/") || url.contains("/serie-tv/")) {
            val episodesList = mutableListOf<Episode>()
            var epCount = 1
            
            val imdbId = getImdbIdViaCinemeta(title, isTv = true)
            val episodesPostersCache = if (imdbId != null) getCinemetaEpisodesPosters(imdbId) else null
            
            document.select("table tr, div.data-content a, td a").forEach { element ->
                val link = element.attr("href")
                if (link.isNotBlank() && (link.contains("uprot") || link.contains("stream") || link.contains("tape") || link.contains("flexy"))) {
                    val rowText = element.parents().select("tr").first()?.selectFirst("td")?.text() ?: element.text()
                    
                    val match = "(\\d+)x(\\d+)".toRegex().find(rowText)
                    val season = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val episode = match?.groupValues?.get(2)?.toIntOrNull() ?: epCount++

                    // Recupera l'immagine dell'episodio ("stagione_episodio"), altrimenti mette il poster generico della serie
                    val episodePoster = episodesPostersCache?.get("${season}_${episode}") ?: poster

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
                if (imdbId != null) this.syncData = mutableMapOf("imdb" to imdbId)
            }
        } else {
            val imdbId = getImdbIdViaCinemeta(title, isTv = false)
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = finalDescription
                if (imdbId != null) this.syncData = mutableMapOf("imdb" to imdbId)
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
