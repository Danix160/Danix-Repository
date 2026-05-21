package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Document

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

        if (isSubIta) { cleaned = "$cleaned SUB ITA" }
        return cleaned
    }

    /**
     * Estrae l'anno dal titolo originale del sito (utile per migliorare l'accuratezza di TMDB)
     */
    private fun extractYear(title: String): Int? {
        val match = """(19|20)\d{2}""".toRegex().find(title)
        return match?.value?.toIntOrNull()
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

            homeResults.add(newMovieSearchResponse(title, url, type) { this.posterUrl = poster })
        }

        document.select(".movie").forEach { element ->
            val rawTitle = element.selectFirst("h2")?.text() ?: return@forEach
            val title = cleanTitle(rawTitle)
            val linkEl = element.selectFirst(".imagen a") ?: element.selectFirst("a")
            val url = linkEl?.attr("href") ?: return@forEach
            val poster = element.selectFirst("img")?.attr("src")
            val type = if (url.contains("/film/")) TvType.Movie else TvType.TvSeries

            homeResults.add(newMovieSearchResponse(title, url, type) { this.posterUrl = poster })
        }

        return newHomePageResponse(request.name, homeResults)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document

        document.select(".movie").forEach { element ->
            val rawTitle = element.selectFirst("h2")?.text() ?: return@forEach
            val title = cleanTitle(rawTitle)
            val targetUrl = element.selectFirst(".imagen a")?.attr("href") ?: element.selectFirst("a")?.attr("href") ?: return@forEach
            val poster = element.selectFirst("img")?.attr("src")
            val type = if (targetUrl.contains("/film/")) TvType.Movie else TvType.TvSeries

            results.add(newMovieSearchResponse(title, targetUrl, type) { this.posterUrl = poster })
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val rawTitle = document.selectFirst("h1")?.text() ?: "Senza Titolo"
        
        val year = extractYear(rawTitle)
        val titleOnly = cleanTitle(rawTitle).replace(" SUB ITA", "").trim()
        val isSubIta = rawTitle.contains("(?i)\\bSUB[- ]?ITA\\b".toRegex())
        
        val sitePoster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".imagen img")?.attr("src")

        if (url.contains("/serietv/")) {
            // Sincronizzazione Serie TV con TMDB
            val tmdbData = getTmdbTvMetadata(titleOnly, year)
            val episodesList = mutableListOf<Episode>()
            var epCount = 1
            
            // Cache per ridurre al minimo le richieste HTTP delle stagioni
            val cachedStagioni = mutableMapOf<Int, Map<Int, TmdbEpisode>?>()

            document.select("table tr, div.data-content a, td a").forEach { element ->
                val link = element.attr("href")
                if (link.isNotBlank() && (link.contains("uprot") || link.contains("stream") || link.contains("tape") || link.contains("flexy"))) {
                    val rowText = element.parents().select("tr").first()?.selectFirst("td")?.text() ?: element.text()
                    
                    val match = "(\\d+)x(\\d+)".toRegex().find(rowText)
                    val season = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val episode = match?.groupValues?.get(2)?.toIntOrNull() ?: epCount++

                    if (tmdbData != null && !cachedStagioni.containsKey(season)) {
                        cachedStagioni[season] = getTmdbSeasonEpisodes(tmdbData.id, season)
                    }

                    val tmdbEp = cachedStagioni[season]?.get(episode)

                    episodesList.add(
                        newEpisode(link) {
                            this.name = tmdbEp?.name ?: "Episodio $episode"
                            this.season = season
                            this.episode = episode
                            this.plot = tmdbEp?.overview
                            this.posterUrl = tmdbEp?.stillPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: (tmdbData?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: sitePoster)
                        }
                    )
                }
            }

            val finalTitle = (tmdbData?.name ?: titleOnly) + if (isSubIta) " SUB ITA" else ""

            return newTvSeriesLoadResponse(finalTitle, url, TvType.TvSeries, episodesList) {
                this.posterUrl = tmdbData?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: sitePoster
                this.plot = tmdbData?.overview ?: document.selectFirst("meta[property=og:description]")?.attr("content")
            }
        } else {
            // Sincronizzazione Film con TMDB
            val tmdbMovie = getTmdbMovieMetadata(titleOnly, year)
            val finalTitle = (tmdbMovie?.title ?: titleOnly) + if (isSubIta) " SUB ITA" else ""

            return newMovieLoadResponse(finalTitle, url, TvType.Movie, url) {
                this.posterUrl = tmdbMovie?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: sitePoster
                this.plot = tmdbMovie?.overview ?: document.selectFirst("meta[property=og:description]")?.attr("content")
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

    // ==========================================
    // METODI API THE MOVIE DATABASE (TMDB)
    // ==========================================

    private suspend fun getTmdbMovieMetadata(query: String, year: Int?): TmdbMovieResult? {
        return try {
            val yearParam = if (year != null) "&year=$year" else ""
            val url = "$tmdbBaseUrl/search/movie?api_key=$tmdbApiKey&query=${base64UrlEncode(query)}&language=it-IT$yearParam"
            val response = app.get(url).parsed<TmdbMovieResponse>()
            response.results?.firstOrNull()
        } catch (e: Exception) { null }
    }

    private suspend fun getTmdbTvMetadata(query: String, year: Int?): TmdbTvResult? {
        return try {
            val yearParam = if (year != null) "&first_air_date_year=$year" else ""
            val url = "$tmdbBaseUrl/search/tv?api_key=$tmdbApiKey&query=${base64UrlEncode(query)}&language=it-IT$yearParam"
            val response = app.get(url).parsed<TmdbTvResponse>()
            response.results?.firstOrNull()
        } catch (e: Exception) { null }
    }

    private suspend fun getTmdbSeasonEpisodes(tvId: Int, season: Int): Map<Int, TmdbEpisode>? {
        return try {
            val url = "$tmdbBaseUrl/tv/$tvId/season/$season?api_key=$tmdbApiKey&language=it-IT"
            val response = app.get(url).parsed<TmdbSeasonResponse>()
            response.episodes?.associateBy { it.episodeNumber }
        } catch (e: Exception) { null }
    }

    private fun base64UrlEncode(string: String): String {
        return java.net.URLEncoder.encode(string, "UTF-8")
    }

    // Classi di data-mapping JSON per l'SDK di Cloudstream
    data class TmdbMovieResponse(val results: List<TmdbMovieResult>?)
    data class TmdbMovieResult(val title: String, val overview: String?, @JsonProperty("poster_path") val posterPath: String?)

    data class TmdbTvResponse(val results: List<TmdbTvResult>?)
    data class TmdbTvResult(val id: Int, val name: String, val overview: String?, @JsonProperty("poster_path") val posterPath: String?)

    data class TmdbSeasonResponse(val episodes: List<TmdbEpisode>?)
    data class TmdbEpisode(
        val name: String, 
        val overview: String?, 
        @JsonProperty("episode_number") val episodeNumber: Int, 
        @JsonProperty("still_path") val stillPath: String?
    )
}
