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
    private val tvMazeBaseUrl = "https://api.tvmaze.com"

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Film",
        "$mainUrl/serie-tv/" to "Serie TV",
        "$mainUrl/serie-tv-generi/animazione/" to "Cartoni & Anime"
    )

    private fun cleanTitle(title: String): String {
        var cleaned = title
            .replace("’", "'")
            .replace("‘", "'")
            .replace("L uomo ragno", "L'uomo ragno", ignoreCase = true)
            .replace("Pokemon", "Pokémon", ignoreCase = true)
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
            .replace("'", "")
            .trim()

        return cleaned
    }

    private fun extractYear(title: String): Int? {
        val match = """(19|20)\d{2}""".toRegex().find(title)
        return match?.value?.toIntOrNull()
    }

    private fun base64UrlEncode(string: String): String = URLEncoder.encode(string, "UTF-8")

    private fun normalizeText(text: String): String {
        return text.lowercase()
            .replace("é", "e")
            .replace("è", "e")
            .replace("à", "a")
            .replace("ò", "o")
            .replace("ì", "i")
            .replace("ù", "u")
            .replace("""[^a-z0-9]""".toRegex(), "")
    }

    private fun isTitleMatching(query: String, targetTitle: String): Boolean {
        val q = normalizeText(query)
        val t = normalizeText(targetTitle)
        if (q.contains(t) || t.contains(q)) return true
        
        val queryWords = query.lowercase().replace("é", "e").split(" ").filter { it.length > 3 }
        val targetWords = targetTitle.lowercase().replace("é", "e").split(" ").filter { it.length > 3 }
        return queryWords.any { targetWords.contains(it) }
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
            
            val titleOnly = title.replace(" SUB ITA", "").trim()
            val year = extractYear(rawTitle)
            
            val poster = if (type == TvType.Movie) {
                getTmdbMovieMetadata(titleOnly, year)?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            } else {
                // Se la query riguarda Scooby, bypassa TMDB per evitare locandine errate
                val tmdb = if (titleOnly.contains("scooby", ignoreCase = true)) null else getTmdbTvMetadata(titleOnly, year)
                if (tmdb != null) {
                    "https://image.tmdb.org/t/p/w500${tmdb.posterPath}"
                } else {
                    getTvMazeMetadata(titleOnly, year)?.image?.medium
                }
            } ?: element.selectFirst(".uagb-post__image img")?.attr("src")

            homeResults.add(newMovieSearchResponse(title, url, type) { this.posterUrl = poster })
        }
        return newHomePageResponse(request.name, homeResults)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${base64UrlEncode(query)}"
        val document = app.get(url).document
        val results = mutableListOf<SearchResponse>()

        document.select(".movie").forEach { element ->
            val rawTitle = element.selectFirst("h2")?.text() ?: return@forEach
            val title = cleanTitle(rawTitle)
            val targetUrl = element.selectFirst(".imagen a")?.attr("href") ?: element.selectFirst("a")?.attr("href") ?: return@forEach
            val type = if (targetUrl.contains("/film/")) TvType.Movie else TvType.TvSeries
            
            val titleOnly = title.replace(" SUB ITA", "").trim()
            val year = extractYear(rawTitle)
            
            val poster = if (type == TvType.Movie) {
                getTmdbMovieMetadata(titleOnly, year)?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            } else {
                val tmdb = if (titleOnly.contains("scooby", ignoreCase = true)) null else getTmdbTvMetadata(titleOnly, year)
                if (tmdb != null) {
                    "https://image.tmdb.org/t/p/w500${tmdb.posterPath}"
                } else {
                    getTvMazeMetadata(titleOnly, year)?.image?.medium
                }
            } ?: element.selectFirst("img")?.attr("src")

            results.add(newMovieSearchResponse(title, targetUrl, type) { this.posterUrl = poster })
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val rawTitle = document.selectFirst("h1")?.text() ?: "Senza Titolo"
        val titleClean = cleanTitle(rawTitle)
        val titleOnly = titleClean.replace(" SUB ITA", "").trim()
        val isSubIta = rawTitle.contains("""(?i)\bSUB[- ]?ITA\b""".toRegex())
        
        var year: Int? = null
        document.select(".score .stars span").forEach { element ->
            if (element.text().contains("Anno:", ignoreCase = true)) {
                val yearText = element.selectFirst("i")?.text()
                year = yearText?.toIntOrNull()
            }
        }
        
        if (year == null) {
            val genericText = document.select(".score, .stars, .info").text()
            year = """(19|20)\d{2}""".toRegex().find(genericText)?.value?.toIntOrNull() ?: extractYear(rawTitle)
        }

        val sitePoster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: document.selectFirst(".imagen img")?.attr("src")

        if (url.contains("/serietv/") || !url.contains("/film/")) {
            // Se è Scooby Doo, forziamo il fallimento di TMDB impostando tmdbData direttamente a null
            val tmdbData = if (titleOnly.contains("scooby", ignoreCase = true)) null else getTmdbTvMetadata(titleOnly, year)
            var tvMazeData: TvMazeShow? = null
            
            if (tmdbData == null) {
                tvMazeData = getTvMazeMetadata(titleOnly, year)
            }

            val episodesList = mutableListOf<Episode>()
            var epCount = 1
            
            val cachedStagioniTmdb = mutableMapOf<Int, Map<Int, TmdbEpisode>?>()
            val cachedStagioniSizes = mutableMapOf<Int, Int>()
            var cachedTvMazeEpisodes: List<TvMazeEpisode>? = null

            document.select("table tr, div.data-content a, td a").forEach { element ->
                val link = element.attr("href")
                if (link.isNotBlank() && (link.contains("uprot") || link.contains("stream") || link.contains("tape") || link.contains("flexy"))) {
                    val rowText = element.parents().select("tr").first()?.selectFirst("td")?.text() ?: element.text()
                    val match = """(\d+)x(\d+)""".toRegex().find(rowText)
                    var season = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    var episode = match?.groupValues?.get(2)?.toIntOrNull() ?: epCount++

                    var epName = "Episodio $episode"
                    var epPlot: String? = null
                    var epPoster = sitePoster

                    // Flusso Metadati TMDB
                    if (tmdbData != null) {
                        var checkSeason = season
                        while (true) {
                            if (!cachedStagioniTmdb.containsKey(checkSeason)) {
                                val epsMap = getTmdbSeasonEpisodes(tmdbData.id, checkSeason)
                                cachedStagioniTmdb[checkSeason] = epsMap
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
                        val tmdbEp = cachedStagioniTmdb[season]?.get(episode)
                        if (tmdbEp != null) {
                            epName = tmdbEp.name
                            epPlot = tmdbEp.overview
                            epPoster = tmdbEp.stillPath?.let { "https://image.tmdb.org/t/p/w500$it" } 
                                ?: "https://image.tmdb.org/t/p/w500${tmdbData.posterPath}"
                        }
                    } 
                    // Flusso Metadati TVMaze (Attivo per Scooby o serie non trovate su TMDB)
                    else if (tvMazeData != null) {
                        if (cachedTvMazeEpisodes == null) {
                            cachedTvMazeEpisodes = getTvMazeEpisodes(tvMazeData.id)
                        }
                        val tvMazeEp = cachedTvMazeEpisodes?.firstOrNull { it.season == season && it.number == episode }
                        if (tvMazeEp != null) {
                            epName = tvMazeEp.name ?: epName
                            epPlot = tvMazeEp.summary?.replace("<[^>]*>".toRegex(), "") 
                            epPoster = tvMazeEp.image?.medium ?: tvMazeData.image?.medium ?: sitePoster
                        }
                    }

                    episodesList.add(newEpisode(link) {
                        this.name = epName
                        this.season = season
                        this.episode = episode
                        this.description = epPlot
                        this.posterUrl = epPoster
                    })
                }
            }

            val finalTitle = (tmdbData?.name ?: tvMazeData?.name ?: titleOnly) + if (isSubIta) " SUB ITA" else ""
            val finalPlot = tmdbData?.overview ?: tvMazeData?.summary?.replace("<[^>]*>".toRegex(), "") ?: document.selectFirst("meta[property=og:description]")?.attr("content")
            val finalPoster = tmdbData?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: tvMazeData?.image?.medium ?: sitePoster

            return newTvSeriesLoadResponse(finalTitle, url, TvType.TvSeries, episodesList) {
                this.posterUrl = finalPoster
                this.plot = finalPlot
            }
        } else {
            val tmdbMovie = getTmdbMovieMetadata(titleOnly, year)
            val finalTitle = (tmdbMovie?.title ?: titleOnly) + if (isSubIta) " SUB ITA" else ""
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

    // ==============================================
    // STRATO DI RECUPERO METADATI TMDB
    // ==============================================
    private suspend fun getTmdbMovieMetadata(query: String, year: Int?): TmdbMovieResult? {
        val encodedQuery = base64UrlEncode(query)
        val yearParam = if (year != null) "&year=$year" else ""
        val url = "$tmdbBaseUrl/search/movie?api_key=$tmdbApiKey&query=$encodedQuery&language=it-IT$yearParam"
        return try {
            val results = app.get(url).parsed<TmdbMovieResponse>().results
            results?.firstOrNull { isTitleMatching(query, it.title) && (year == null || it.releaseDate?.contains(year.toString()) == true) }
                ?: results?.firstOrNull { isTitleMatching(query, it.title) }
                ?: app.get("$tmdbBaseUrl/search/movie?api_key=$tmdbApiKey&query=$encodedQuery&language=it-IT").parsed<TmdbMovieResponse>().results?.firstOrNull()
        } catch (e: Exception) { null }
    }

    private suspend fun getTmdbTvMetadata(query: String, year: Int?): TmdbTvResult? {
        val encodedQuery = base64UrlEncode(query)
        val yearParam = if (year != null) "&first_air_date_year=$year" else ""
        val url = "$tmdbBaseUrl/search/tv?api_key=$tmdbApiKey&query=$encodedQuery&language=it-IT$yearParam"
        return try {
            val results = app.get(url).parsed<TmdbTvResponse>().results
            results?.firstOrNull { isTitleMatching(query, it.name) && (year == null || it.firstAirDate?.contains(year.toString()) == true) }
                ?: results?.firstOrNull { isTitleMatching(query, it.name) }
                ?: app.get("$tmdbBaseUrl/search/tv?api_key=$tmdbApiKey&query=$encodedQuery&language=it-IT").parsed<TmdbTvResponse>().results?.firstOrNull()
        } catch (e: Exception) { null }
    }

    private suspend fun getTmdbSeasonEpisodes(tvId: Int, season: Int): Map<Int, TmdbEpisode>? {
        return try {
            app.get("$tmdbBaseUrl/tv/$tvId/season/$season?api_key=$tmdbApiKey&language=it-IT").parsed<TmdbSeasonResponse>().episodes?.associateBy { it.episodeNumber }
        } catch (e: Exception) { null }
    }

    // ==============================================
    // STRATO DI RECUPERO METADATI TVMAZE
    // ==============================================
    private suspend fun getTvMazeMetadata(query: String, year: Int?): TvMazeShow? {
        val url = "$tvMazeBaseUrl/search/shows?q=${base64UrlEncode(query)}"
        return try {
            val response = app.get(url).parsed<List<TvMazeSearchResponse>>()
            response.firstOrNull { 
                isTitleMatching(query, it.show.name) && (year == null || it.show.premiered?.contains(year.toString()) == true)
            }?.show ?: response.firstOrNull { isTitleMatching(query, it.show.name) }?.show
        } catch (e: Exception) { null }
    }

    private suspend fun getTvMazeEpisodes(showId: Int): List<TvMazeEpisode>? {
        return try {
            app.get("$tvMazeBaseUrl/shows/$showId/episodes").parsed<List<TvMazeEpisode>>()
        } catch (e: Exception) { null }
    }

    // DATA CLASSES TMDB
    data class TmdbMovieResponse(val results: List<TmdbMovieResult>?)
    data class TmdbMovieResult(val title: String, val overview: String?, @JsonProperty("poster_path") val posterPath: String?, @JsonProperty("release_date") val releaseDate: String?)
    data class TmdbTvResponse(val results: List<TmdbTvResult>?)
    data class TmdbTvResult(val id: Int, val name: String, val overview: String?, @JsonProperty("poster_path") val posterPath: String?, @JsonProperty("first_air_date") val firstAirDate: String?)
    data class TmdbSeasonResponse(val episodes: List<TmdbEpisode>?)
    data class TmdbEpisode(val name: String, val overview: String?, @JsonProperty("episode_number") val episodeNumber: Int, @JsonProperty("still_path") val stillPath: String?)

    // DATA CLASSES TVMAZE
    data class TvMazeSearchResponse(val show: TvMazeShow)
    data class TvMazeShow(val id: Int, val name: String, val summary: String?, val premiered: String?, val image: TvMazeImage?)
    data class TvMazeImage(val medium: String?, val original: String?)
    data class TvMazeEpisode(val name: String?, val season: Int, val number: Int, val summary: String?, val image: TvMazeImage?)
}
