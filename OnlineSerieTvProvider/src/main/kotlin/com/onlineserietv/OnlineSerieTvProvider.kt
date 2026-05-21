package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.parallelMap // Utilizza il parallelMap interno di Cloudstream
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
            .replace("""(?i)\b(ITA|STAGIONE \\d+|STAGIONE)\\b""".toRegex(), "")
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
            val tmdbPosterPath = if (type == TvType.Movie) {
                getTmdbMovieMetadata(title, year)?.posterPath
            } else {
                getTmdbTvMetadata(title, year)?.posterPath
            }
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
                val url = if (page == 1) {
                    "$mainUrl/?s=${base64UrlEncode(query)}"
                } else {
                    "$mainUrl/page/$page/?s=${base64UrlEncode(query)}"
                }
                
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
                    val tmdbPosterPath = if (type == TvType.Movie) {
                        getTmdbMovieMetadata(title, year)?.posterPath
                    } else {
                        getTmdbTvMetadata(title, year)?.posterPath
                    }
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
                    val tmdbPosterPath = if (type == TvType.Movie) {
                        getTmdbMovieMetadata(title, year)?.posterPath
                    } else {
                        getTmdbTvMetadata(title, year)?.posterPath
                    }
                    val poster = tmdbPosterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: element.selectFirst(".uagb-post__image img")?.attr("src")

                    results.add(newMovieSearchResponse(title, targetUrl, type) { this.posterUrl = poster })
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
        val rawTitle = document.selectFirst("h1")?.text() ?: "Senza Titolo"
        val titleClean = cleanTitle(rawTitle)
        val isSubIta = rawTitle.contains("""(?i)\bSUB[- ]?ITA\b""".toRegex())
        
        var siteYear: Int? = null
        document.select(".score .stars span").forEach { element ->
            if (element.text().contains("Anno:", ignoreCase = true)) {
                val yearText = element.selectFirst("i")?.text()?.trim()
                siteYear = yearText?.toIntOrNull()
            }
        }
        
        if (siteYear == null) {
            val genericText = document.select(".score, .stars, .info").text()
            siteYear = """(19|20)\d{2}""".toRegex().find(genericText)?.value?.toIntOrNull() ?: extractYear(rawTitle)
        }

        val sitePoster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: document.selectFirst(".imagen img")?.attr("src")
        val isMovie = url.contains("/film/") || url.contains("/movies")

        var siteDescription: String? = null
        val tramaElement = document.select("b:contains(Trama), strong:contains(Trama)").firstOrNull()
        if (tramaElement != null) {
            siteDescription = tramaElement.nextElementSibling()?.selectFirst("p")?.text()
                ?: tramaElement.nextElementSiblings().firstOrNull { it.tagName() == "p" }?.text()
        }
        if (siteDescription.isNullOrBlank()) {
            siteDescription = document.select("div.tsll p, .entry-content p, .post-content p, div.post p")
                .map { it.text().trim() }
                .firstOrNull { it.length > 30 && !it.contains("generato") && !it.contains("creata da") && !it.contains("visto in streaming") }
        }
        if (siteDescription.isNullOrBlank()) {
            siteDescription = document.selectFirst("meta[property=og:description]")?.attr("content")
        }
        val finalSitePlot = siteDescription?.replace("(?i)^Trama:\\s*".toRegex(), "")?.trim()

        if (!isMovie) {
            val tmdbData = getTmdbTvMetadata(titleClean, siteYear)
            val episodesList = mutableListOf<Episode>()
            
            val validElements = document.select("table tr, div.data-content a, td a").filter { element ->
                val link = element.attr("href")
                link.isNotBlank() && (link.contains("uprot") || link.contains("stream") || link.contains("tape") || link.contains("flexy"))
            }

            val detectedSeasons = mutableSetOf<Int>()
            var backupEpCount = 1
            
            val parsedElementsData = validElements.map { element ->
                val link = element.attr("href")
                val rowText = element.parents().select("tr").first()?.selectFirst("td")?.text() ?: element.text()
                val match = """(\d+)x(\d+)""".toRegex().find(rowText)
                val season = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episode = match?.groupValues?.get(2)?.toIntOrNull() ?: backupEpCount++
                detectedSeasons.add(season)
                Triple(link, season, episode)
            }

            val cachedStagioni = mutableMapOf<Int, Map<Int, TmdbEpisode>?>()
            val cachedStagioniSizes = mutableMapOf<Int, Int>()

            // OTTIMIZZAZIONE FULMINEA: Scarica i dati di tutte le stagioni in PARALLELO usando parallelMap di Cloudstream
            if (tmdbData != null && detectedSeasons.isNotEmpty()) {
                detectedSeasons.toList().parallelMap { season ->
                    val epsMap = getTmdbSeasonEpisodes(tmdbData.id, season)
                    Pair(season, epsMap)
                }.forEach { (season, epsMap) ->
                    cachedStagioni[season] = epsMap
                    cachedStagioniSizes[season] = epsMap?.size ?: 0
                }
            }

            parsedElementsData.forEach { (link, initialSeason, initialEpisode) ->
                var season = initialSeason
                var episode = initialEpisode

                if (tmdbData != null) {
                    var checkSeason = season
                    while (true) {
                        val maxEpisodesInSeason = cachedStagioniSizes[checkSeason] ?: 0
                        if (maxEpisodesInSeason > 0 && episode > maxEpisodesInSeason) {
                            episode -= maxEpisodesInSeason
                            checkSeason++
                            
                            // Se mancano dati della stagione successiva (es. sforamento), la recuperiamo al volo in sicurezza
                            if (!cachedStagioni.containsKey(checkSeason)) {
                                val epsMap = getTmdbSeasonEpisodes(tmdbData.id, checkSeason)
                                cachedStagioni[checkSeason] = epsMap
                                cachedStagioniSizes[checkSeason] = epsMap?.size ?: 0
                            }
                        } else {
                            season = checkSeason
                            break
                        }
                        if (maxEpisodesInSeason == 0) break
                    }
                }

                val tmdbEp = if (tmdbData != null) cachedStagioni[season]?.get(episode) else null
                val episodePoster = tmdbEp?.stillPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                    ?: tmdbData?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                    ?: sitePoster

                episodesList.add(newEpisode(link) {
                    this.name = tmdbEp?.name ?: "Episodio $episode"
                    this.season = season
                    this.episode = episode
                    this.description = tmdbEp?.overview
                    this.posterUrl = episodePoster
                })
            }

            val finalTitle = (tmdbData?.name ?: titleClean) + if (isSubIta) " SUB ITA" else ""
            return newTvSeriesLoadResponse(finalTitle, url, TvType.TvSeries, episodesList) {
                this.posterUrl = tmdbData?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: sitePoster
                this.plot = tmdbData?.overview ?: finalSitePlot
            }
        } else {
            val tmdbMovie = getTmdbMovieMetadata(titleClean, siteYear)
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

    private suspend fun getTmdbMovieMetadata(query: String, year: Int?): TmdbMovieResult? {
        val encodedQuery = base64UrlEncode(query)
        val yearParam = if (year != null) "&year=$year" else ""
        val url = "$tmdbBaseUrl/search/movie?api_key=$tmdbApiKey&query=$encodedQuery&language=it-IT$yearParam"
        return try {
            val results = app.get(url).parsed<TmdbMovieResponse>().results ?: return null
            
            val exactMatch = results.firstOrNull { isTitleMatching(query, it.title) && (year == null || it.releaseDate?.contains(year.toString()) == true) }
            if (exactMatch != null) return exactMatch
            
            val partialMatch = results.firstOrNull { isTitleMatching(query, it.title) }
            if (partialMatch != null) {
                val tmdbYear = partialMatch.releaseDate?.take(4)?.toIntOrNull()
                if (year != null && tmdbYear != year) return null
                return partialMatch
            }
            null
        } catch (e: Exception) { null }
    }

    private suspend fun getTmdbTvMetadata(query: String, year: Int?): TmdbTvResult? {
        val encodedQuery = base64UrlEncode(query)
        val yearParam = if (year != null) "&first_air_date_year=$year" else ""
        val url = "$tmdbBaseUrl/search/tv?api_key=$tmdbApiKey&query=$encodedQuery&language=it-IT$yearParam"
        return try {
            val results = app.get(url).parsed<TmdbTvResponse>().results ?: return null
            
            val exactMatch = results.firstOrNull { isTitleMatching(query, it.name) && (year == null || it.firstAirDate?.contains(year.toString()) == true) }
            if (exactMatch != null) return exactMatch
            
            val partialMatch = results.firstOrNull { isTitleMatching(query, it.name) }
            if (partialMatch != null) {
                val tmdbYear = partialMatch.firstAirDate?.take(4)?.toIntOrNull()
                if (year != null && tmdbYear != year) return null
                
                val qNorm = normalizeText(query)
                val tNorm = normalizeText(partialMatch.name)
                if (qNorm == tNorm || tNorm.startsWith(qNorm)) return partialMatch
            }
            null
        } catch (e: Exception) { null }
    }

    private suspend fun getTmdbSeasonEpisodes(tvId: Int, season: Int): Map<Int, TmdbEpisode>? {
        return try {
            app.get("$tmdbBaseUrl/tv/$tvId/season/$season?api_key=$tmdbApiKey&language=it-IT").parsed<TmdbSeasonResponse>().episodes?.associateBy { it.episodeNumber }
        } catch (e: Exception) { null }
    }

    data class TmdbMovieResponse(val results: List<TmdbMovieResult>?)
    data class TmdbMovieResult(
        val title: String, 
        val overview: String?, 
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("release_date") val releaseDate: String?
    )
    data class TmdbTvResponse(val results: List<TmdbTvResult>?)
    data class TmdbTvResult(
        val id: Int, 
        val name: String, 
        val overview: String?, 
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("first_air_date") val firstAirDate: String?
    )
    data class TmdbSeasonResponse(val episodes: List<TmdbEpisode>?)
    data class TmdbEpisode(val name: String, val overview: String?, @JsonProperty("episode_number") val episodeNumber: Int, @JsonProperty("still_path") val stillPath: String?)
}
