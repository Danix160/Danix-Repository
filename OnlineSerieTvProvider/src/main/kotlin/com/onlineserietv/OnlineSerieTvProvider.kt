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
            .replace("'", "")
            .replace("-", "")
            .replace(" ", "")
            .replace("""[^a-z0-9]""".toRegex(), "")
    }

    private fun isTitleMatching(query: String, tmdbTitle: String): Boolean {
        val q = normalizeText(query)
        val t = normalizeText(tmdbTitle)
        return q == t || q.contains(t) || t.contains(q)
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
            val tmdbPosterPath = if (type == TvType.Movie) getTmdbMovieMetadata(title, year)?.posterPath else getTmdbTvMetadata(title, year)?.posterPath
            val poster = tmdbPosterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: element.selectFirst(".uagb-post__image img")?.attr("src")

            homeResults.add(newMovieSearchResponse(title, url, type) { this.posterUrl = poster })
        }
        return newHomePageResponse(request.name, homeResults)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val maxPagesToSearch = 10 

        for (page in 1..maxPagesToSearch) {
            try {
                val url = if (page == 1) "$mainUrl/?s=${base64UrlEncode(query)}" else "$mainUrl/page/$page/?s=${base64UrlEncode(query)}"
                val response = app.get(url)
                if (response.code != 200) break 
                
                val document = response.document
                val initialCount = results.size

                document.select(".movie").forEach { element ->
                    val rawTitle = element.selectFirst("h2")?.text() ?: return@forEach
                    val title = cleanTitle(rawTitle)
                    val targetUrl = element.selectFirst(".imagen a")?.attr("href") ?: element.selectFirst("a")?.attr("href") ?: return@forEach
                    val type = if (targetUrl.contains("/film/")) TvType.Movie else TvType.TvSeries
                    
                    val year = extractYear(rawTitle)
                    val tmdbPosterPath = if (type == TvType.Movie) getTmdbMovieMetadata(title, year)?.posterPath else getTmdbTvMetadata(title, year)?.posterPath
                    val poster = tmdbPosterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: element.selectFirst("img")?.attr("src")

                    results.add(newMovieSearchResponse(title, targetUrl, type) { this.posterUrl = poster })
                }

                document.select(".uagb-post__inner-wrap").forEach { element ->
                    val titleEl = element.selectFirst(".uagb-post__title a")
                    val rawTitle = titleEl?.text() ?: return@forEach
                    val title = cleanTitle(rawTitle)
                    val targetUrl = titleEl.attr("href")
                    val type = if (targetUrl.contains("/film/")) TvType.Movie else TvType.TvSeries
                    
                    val year = extractYear(rawTitle)
                    val tmdbPosterPath = if (type == TvType.Movie) getTmdbMovieMetadata(title, year)?.posterPath else getTmdbTvMetadata(title, year)?.posterPath
                    val poster = tmdbPosterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: element.selectFirst(".uagb-post__image img")?.attr("src")

                    results.add(newMovieSearchResponse(title, targetUrl, type) { this.posterUrl = poster })
                }

                if (results.size == initialCount) break
            } catch (e: Exception) { break }
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val rawTitle = document.selectFirst("h1")?.text() 
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Senza Titolo"
        
        val titleClean = cleanTitle(rawTitle)
        val isSubIta = rawTitle.contains("""(?i)\bSUB[- ]?ITA\b""".toRegex())
        val isMovie = !url.contains("/serietv/")
        
        val sitePoster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: document.selectFirst(".imagen img")?.attr("src")
        val finalSitePlot = document.selectFirst("meta[property=og:description]")?.attr("content")

        if (!isMovie) {
            val tmdbData = getTmdbTvMetadata(titleClean, null)
            val globalPoster = tmdbData?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: sitePoster
            val tmdbSeasonsSizes = if (tmdbData != null) getTmdbSeasonsSizes(tmdbData.id) else null
            
            val episodesList = mutableListOf<Episode>()
            var fallbackEpCount = 1

            document.select("table tr td a, div.data-content a, td a").forEach { element ->
                val link = element.attr("href")
                if (link.isNotBlank() && (link.contains("uprot") || link.contains("stream") || link.contains("tape") || link.contains("flexy"))) {
                    val rowText = element.text()
                    val matchXxX = """(\d+)[xX](\d+)""".toRegex().find(rowText)
                    
                    var finalSeason = 1
                    var finalEpisode = fallbackEpCount

                    if (matchXxX != null) {
                        finalSeason = matchXxX.groupValues[1].toIntOrNull() ?: 1
                        finalEpisode = matchXxX.groupValues[2].toIntOrNull() ?: fallbackEpCount
                    } else if (tmdbSeasonsSizes != null && fallbackEpCount > (tmdbSeasonsSizes[1] ?: 0)) {
                        var currentEp = fallbackEpCount
                        var currentSeason = 1
                        while (true) {
                            val maxInCurrent = tmdbSeasonsSizes[currentSeason] ?: 0
                            if (maxInCurrent > 0 && currentEp > maxInCurrent) {
                                currentEp -= maxInCurrent
                                currentSeason++
                            } else {
                                finalSeason = currentSeason
                                finalEpisode = currentEp
                                break
                            }
                        }
                    }

                    episodesList.add(newEpisode(link) {
                        this.name = "Episodio $finalEpisode"
                        this.season = finalSeason
                        this.episode = finalEpisode
                        this.posterUrl = globalPoster
                    })
                    fallbackEpCount++
                }
            }
            val finalEpisodes = episodesList.distinctBy { "${it.season}-${it.episode}-${it.data}" }
            
            val finalTitle = (tmdbData?.name ?: titleClean) + if (isSubIta) " SUB ITA" else ""
            return newTvSeriesLoadResponse(finalTitle, url, TvType.TvSeries, finalEpisodes) {
                this.posterUrl = globalPoster
                this.plot = tmdbData?.overview ?: finalSitePlot
            }
        } else {
            val tmdbMovie = getTmdbMovieMetadata(titleClean, null)
            val finalTitle = (tmdbMovie?.title ?: titleClean) + if (isSubIta) " SUB ITA" else ""
            return newMovieLoadResponse(finalTitle, url, TvType.Movie, url) {
                this.posterUrl = tmdbMovie?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: sitePoster
                this.plot = tmdbMovie?.overview ?: finalSitePlot
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        loadExtractor(data, mainUrl, subtitleCallback, callback)
        return true
    }

    private suspend fun getTmdbSeasonsSizes(tmdbId: Int): Map<Int, Int>? {
        val url = "$tmdbBaseUrl/tv/$tmdbId?api_key=$tmdbApiKey"
        return try {
            val response = app.get(url).parsed<TmdbTvDetails>()
            val sizes = mutableMapOf<Int, Int>()
            response.seasons?.forEach { if ((it.seasonNumber ?: 0) > 0) sizes[it.seasonNumber!!] = it.episodeCount ?: 0 }
            sizes
        } catch (e: Exception) { null }
    }

    private suspend fun getTmdbMovieMetadata(query: String, year: Int?): TmdbMovieResult? {
        val url = "$tmdbBaseUrl/search/movie?api_key=$tmdbApiKey&query=${base64UrlEncode(query)}"
        return app.get(url).parsed<TmdbMovieResponse>().results?.firstOrNull { isTitleMatching(query, it.title) }
    }

    private suspend fun getTmdbTvMetadata(query: String, year: Int?): TmdbTvResult? {
        val url = "$tmdbBaseUrl/search/tv?api_key=$tmdbApiKey&query=${base64UrlEncode(query)}"
        return app.get(url).parsed<TmdbTvResponse>().results?.firstOrNull { isTitleMatching(query, it.name) }
    }

    data class TmdbMovieResponse(val results: List<TmdbMovieResult>?)
    data class TmdbMovieResult(val title: String, val posterPath: String?)
    data class TmdbTvResponse(val results: List<TmdbTvResult>?)
    data class TmdbTvResult(val id: Int, val name: String, val posterPath: String?, val overview: String?)
    data class TmdbTvDetails(val seasons: List<TmdbSeasonSchema>?)
    data class TmdbSeasonSchema(@JsonProperty("season_number") val seasonNumber: Int?, @JsonProperty("episode_count") val episodeCount: Int?)
}
