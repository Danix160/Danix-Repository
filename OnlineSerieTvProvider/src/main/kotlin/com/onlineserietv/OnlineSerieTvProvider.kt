package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLEncoder

class OnlineSerieTvProvider : MainAPI() {
    override var mainUrl = "https://onlineserietv.lol"
    override var name = "OnlineSerieTv"
    override val hasMainPage = true
    override var lang = "it"
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbApiKey = "e541cb159df14ce70fc51ab75703a1a2" 
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"

    override val mainPage = mainPageOf(
        "$mainUrl/serie-tv-generi/animazione/" to "Cartoni & Anime",
        "$mainUrl/movies" to "Film",
        "$mainUrl/serie-tv/" to "Serie TV",
    )

    private fun cleanTitle(title: String): String {
        // Normalizzazione apostrofi e traduzione nomi comuni per TMDB
        var cleaned = title
            .replace("’", "'")
            .replace("‘", "'")
            .replace("Pokemon", "Pokémon", ignoreCase = true)
            .replace("L uomo ragno", "L'uomo ragno", ignoreCase = true)
            .replace(" in streaming - OnlineSerieTv", "", ignoreCase = true)
            
        val regexDaRimuovere = """(?i)\b(serie animata|serie tv|animazione|in streaming|online|hdtv|web-dl)\b""".toRegex()
        
        cleaned = cleaned
            .replace(regexDaRimuovere, "")
            .replace("""(?i)\bSUB[- ]?ITA\b""".toRegex(), "")
            .replace("""(?i)\b(ITA|STAGIONE \d+|STAGIONE)\b""".toRegex(), "")
            .replace("""\s*[\(\[-]?\s*(19|20)\d{2}\s*[\)\]-]?\s*""".toRegex(), " ")
            .replace("""\s*[-–—:|]+\s*$""".toRegex(), "") 
            .replace("""^\s*[-–—:|]+\s*""".toRegex(), "")
            .replace("""\s+""".toRegex(), " ")
            .replace("'", "") // Rimuove apostrofo dopo la pulizia
            .trim()

        return cleaned
    }

    private fun extractYear(title: String): Int? {
        val match = """(19|20)\d{2}""".toRegex().find(title)
        return match?.value?.toIntOrNull()
    }

    private fun base64UrlEncode(string: String) = URLEncoder.encode(string, "UTF-8")

    private fun isTitleMatching(query: String, tmdbTitle: String): Boolean {
        val q = query.lowercase().replace("""[^a-z0-9]""".toRegex(), "")
        val t = tmdbTitle.lowercase().replace("""[^a-z0-9]""".toRegex(), "")
        if (q.contains(t) || t.contains(q)) return true
        
        val queryWords = query.lowercase().split(" ").filter { it.length > 3 }
        val tmdbWords = tmdbTitle.lowercase().split(" ").filter { it.length > 3 }
        return queryWords.any { tmdbWords.contains(it) }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(request.data).document
        val homeResults = mutableListOf<SearchResponse>()

        document.select(".uagb-post__inner-wrap").forEach { element ->
            val titleEl = element.selectFirst(".uagb-post__title a")
            val rawTitle = titleEl?.text() ?: return@forEach
            val title = cleanTitle(rawTitle)
            val url = titleEl.attr("href")
            val type = if (url.contains("/film/")) TvType.Movie else TvType.TvSeries
            
            val year = extractYear(rawTitle)
            val tmdbPoster = if (type == TvType.Movie) getTmdbMovieMetadata(title, year)?.posterPath else getTmdbTvMetadata(title, year)?.posterPath
            val poster = tmdbPoster?.let { "https://image.tmdb.org/t/p/w500$it" } ?: element.selectFirst(".uagb-post__image img")?.attr("src")

            homeResults.add(newMovieSearchResponse(title, url, type) { this.posterUrl = poster })
        }
        return newHomePageResponse(request.name, homeResults)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        val results = mutableListOf<SearchResponse>()

        document.select(".movie").forEach { element ->
            val rawTitle = element.selectFirst("h2")?.text() ?: return@forEach
            val title = cleanTitle(rawTitle)
            val targetUrl = element.selectFirst(".imagen a")?.attr("href") ?: element.selectFirst("a")?.attr("href") ?: return@forEach
            val type = if (targetUrl.contains("/film/")) TvType.Movie else TvType.TvSeries
            
            val year = extractYear(rawTitle)
            val tmdbPoster = if (type == TvType.Movie) getTmdbMovieMetadata(title, year)?.posterPath else getTmdbTvMetadata(title, year)?.posterPath
            val poster = tmdbPoster?.let { "https://image.tmdb.org/t/p/w500$it" } ?: element.selectFirst("img")?.attr("src")

            results.add(newMovieSearchResponse(title, targetUrl, type) { this.posterUrl = poster })
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val rawTitle = document.selectFirst("h1")?.text() ?: "Senza Titolo"
        
        val year = extractYear(rawTitle)
        val titleClean = cleanTitle(rawTitle)
        val isSubIta = rawTitle.contains("""(?i)\bSUB[- ]?ITA\b""".toRegex())
        
        val sitePoster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: document.selectFirst(".imagen img")?.attr("src")

        if (url.contains("/serietv/")) {
            val tmdbData = getTmdbTvMetadata(titleClean, year)
            val episodesList = mutableListOf<Episode>()
            var epCount = 1
            
            val cachedStagioni = mutableMapOf<Int, Map<Int, TmdbEpisode>?>()
            val cachedStagioniSizes = mutableMapOf<Int, Int>()

            document.select("table tr, div.data-content a, td a").forEach { element ->
                val link = element.attr("href")
                if (link.isNotBlank() && (link.contains("uprot") || link.contains("stream") || link.contains("tape") || link.contains("flexy"))) {
                    val rowText = element.parents().select("tr").first()?.selectFirst("td")?.text() ?: element.text()
                    val match = """(\d+)x(\d+)""".toRegex().find(rowText)
                    var season = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    var episode = match?.groupValues?.get(2)?.toIntOrNull() ?: epCount++

                    if (tmdbData != null) {
                        var checkSeason = season
                        while (true) {
                            if (!cachedStagioni.containsKey(checkSeason)) {
                                val epsMap = getTmdbSeasonEpisodes(tmdbData.id, checkSeason)
                                cachedStagioni[checkSeason] = epsMap
                                cachedStagioniSizes[checkSeason] = epsMap?.size ?: 0
                            }
                            val maxEpisodesInSeason = cachedStagioniSizes[checkSeason] ?: 0
                            if (maxEpisodesInSeason > 0 && episode > maxEpisodesInSeason) {
                                episode -= maxEpisodesInSeason
                                checkSeason++
                            } else {
                                season = checkSeason
                                break
                            }
                        }
                    }
                    
                    val tmdbEp = cachedStagioni[season]?.get(episode)
                    
                    episodesList.add(newEpisode(link) {
                        this.name = tmdbEp?.name ?: "Episodio $episode"
                        this.season = season
                        this.episode = episode
                        this.description = tmdbEp?.overview
                        // Prende la miniatura dell'episodio (stillPath), se manca usa il poster della serie, altrimenti quello del sito
                        this.posterUrl = tmdbEp?.stillPath?.let { "https://image.tmdb.org/t/p/w500$it" } 
                            ?: (tmdbData?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: sitePoster)
                    })
                }
            }
            val finalTitle = (tmdbData?.name ?: titleClean) + if (isSubIta) " SUB ITA" else ""
            return newTvSeriesLoadResponse(finalTitle, url, TvType.TvSeries, episodesList) {
                this.posterUrl = tmdbData?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: sitePoster
                this.plot = tmdbData?.overview ?: document.selectFirst("meta[property=og:description]")?.attr("content")
            }
        } else {
            val tmdbMovie = getTmdbMovieMetadata(titleClean, year)
            val finalTitle = (tmdbMovie?.title ?: titleClean) + if (isSubIta) " SUB ITA" else ""
            return newMovieLoadResponse(finalTitle, url, TvType.Movie, url) {
                this.posterUrl = tmdbMovie?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: sitePoster
                this.plot = tmdbMovie?.overview ?: document.selectFirst("meta[property=og:description]")?.attr("content")
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        loadExtractor(data, mainUrl, subtitleCallback, callback)
        return true
    }

    private suspend fun getTmdbMovieMetadata(query: String, year: Int?): TmdbMovieResult? {
        val yearParam = if (year != null) "&year=$year" else ""
        val url = "$tmdbBaseUrl/search/movie?api_key=$tmdbApiKey&query=${base64UrlEncode(query)}&language=it-IT$yearParam"
        return app.get(url).parsed<TmdbMovieResponse>().results?.firstOrNull { isTitleMatching(query, it.title) }
    }

    private suspend fun getTmdbTvMetadata(query: String, year: Int?): TmdbTvResult? {
        val yearParam = if (year != null) "&first_air_date_year=$year" else ""
        val url = "$tmdbBaseUrl/search/tv?api_key=$tmdbApiKey&query=${base64UrlEncode(query)}&language=it-IT$yearParam"
        return app.get(url).parsed<TmdbTvResponse>().results?.firstOrNull { isTitleMatching(query, it.name) }
    }

    private suspend fun getTmdbSeasonEpisodes(tvId: Int, season: Int): Map<Int, TmdbEpisode>? {
        return try {
            app.get("$tmdbBaseUrl/tv/$tvId/season/$season?api_key=$tmdbApiKey&language=it-IT").parsed<TmdbSeasonResponse>().episodes?.associateBy { it.episodeNumber }
        } catch (e: Exception) { null }
    }

    data class TmdbMovieResponse(val results: List<TmdbMovieResult>?)
    data class TmdbMovieResult(val title: String, val overview: String?, @JsonProperty("poster_path") val posterPath: String?)
    data class TmdbTvResponse(val results: List<TmdbTvResult>?)
    data class TmdbTvResult(val id: Int, val name: String, val overview: String?, @JsonProperty("poster_path") val posterPath: String?)
    data class TmdbSeasonResponse(val episodes: List<TmdbEpisode>?)
    data class TmdbEpisode(val name: String, val overview: String?, @JsonProperty("episode_number") val episodeNumber: Int, @JsonProperty("still_path") val stillPath: String?)
}
