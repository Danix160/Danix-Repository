// Daniele — Provider completo TMDB + TVMaze + fallback
// Compatibile con Cloudstream stabile (senza seasons/loadEpisodes)

package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.fasterxml.jackson.annotation.JsonProperty

class OnlineSerieTvProvider : MainAPI() {

    override var mainUrl = "https://onlineserietv.lol"
    override var name = "OnlineSerieTv"
    override val hasMainPage = true
    override var lang = "it"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbApiKey = "e541cb159df14ce70fc51ab75703a1a2"
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Film",
        "$mainUrl/serie-tv/" to "Serie TV"
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

    // ---------------------------------------------------------
    // TVMAZE DATA CLASSES
    // ---------------------------------------------------------

    data class TvMazeSearchResult(val show: TvMazeShow)
    data class TvMazeShow(
        val id: Int,
        val name: String,
        val summary: String?,
        val image: TvMazeImage?
    )
    data class TvMazeImage(val medium: String?, val original: String?)
    data class TvMazeSeason(val id: Int, val number: Int)
    data class TvMazeEpisode(
        val id: Int,
        val name: String,
        val number: Int,
        val season: Int,
        val summary: String?,
        val image: TvMazeImage?
    )

    // ---------------------------------------------------------
    // TVMAZE FUNCTIONS
    // ---------------------------------------------------------

    private suspend fun tvmazeSearch(query: String): TvMazeShow? {
        return try {
            val url = "https://api.tvmaze.com/search/shows?q=${query}"
            val results = app.get(url).parsed<List<TvMazeSearchResult>>()
            results.firstOrNull()?.show
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun tvmazeGetSeasons(id: Int): List<TvMazeSeason>? {
        return try {
            app.get("https://api.tvmaze.com/shows/$id/seasons").parsed()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun tvmazeGetEpisodes(seasonId: Int): List<TvMazeEpisode>? {
        return try {
            app.get("https://api.tvmaze.com/seasons/$seasonId/episodes").parsed()
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------
    // TMDB DATA CLASSES
    // ---------------------------------------------------------

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
    data class TmdbEpisode(
        val name: String,
        val overview: String?,
        @JsonProperty("episode_number") val episodeNumber: Int,
        @JsonProperty("still_path") val stillPath: String?
    )

    // ---------------------------------------------------------
    // TMDB FUNCTIONS
    // ---------------------------------------------------------

    private suspend fun getTmdbTvMetadata(query: String, year: Int?): TmdbTvResult? {

        // PATCH POKÉMON
        if (query.contains("pokemon", ignoreCase = true)) {
            return try {
                val url = "$tmdbBaseUrl/search/tv?api_key=$tmdbApiKey&query=pokemon&language=it-IT"
                val results = app.get(url).parsed<TmdbTvResponse>().results ?: emptyList()
                results.firstOrNull { it.firstAirDate?.startsWith("1997") == true }
            } catch (e: Exception) {
                null
            }
        }

        // PATCH SCOOBY-DOO SHOW
        if (query.contains("scooby", true) && query.contains("show", true)) {
            return null // forza TVMaze
        }

        val url =
            "$tmdbBaseUrl/search/tv?api_key=$tmdbApiKey&query=$query&language=it-IT"

        return try {
            val results = app.get(url).parsed<TmdbTvResponse>().results ?: emptyList()
            results.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getTmdbMovieMetadata(query: String, year: Int?): TmdbMovieResult? {
        val url =
            "$tmdbBaseUrl/search/movie?api_key=$tmdbApiKey&query=$query&language=it-IT"

        return try {
            val results = app.get(url).parsed<TmdbMovieResponse>().results ?: emptyList()
            results.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getTmdbSeasonEpisodes(tvId: Int, season: Int): Map<Int, TmdbEpisode>? {
        return try {
            app.get(
                "$tmdbBaseUrl/tv/$tvId/season/$season?api_key=$tmdbApiKey&language=it-IT"
            ).parsed<TmdbSeasonResponse>().episodes?.associateBy { it.episodeNumber }
        } catch (e: Exception) {
            null
        }
    }
///////////////////////////////////////////////////////////////////////////////////////////////
///GETMAINPAGE///////////////////////////////////////////////////////////////////////////////////
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


    // ---------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        val list = mutableListOf<SearchResponse>()

        doc.select(".movie").forEach { el ->
            val title = el.selectFirst("h2")?.text() ?: return@forEach
            val link = el.selectFirst("a")?.attr("href") ?: return@forEach
            val poster = el.selectFirst("img")?.attr("src")

            list.add(
                newMovieSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            )
        }

        return list
    }

    // ---------------------------------------------------------
    // LOAD (SERIE + EPISODI)
    // ---------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url).document
        val rawTitle = doc.selectFirst("h1")?.text() ?: "Senza titolo"
        val title = rawTitle.replace("Streaming", "").trim()

        // TMDB
        val tmdb = getTmdbTvMetadata(title, null)

        // TVMaze fallback
        val tvmaze = if (tmdb == null) tvmazeSearch(title) else null

        val poster =
            tmdb?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                ?: tvmaze?.image?.original
                ?: doc.selectFirst("img")?.attr("src")

        val plot =
            tmdb?.overview
                ?: tvmaze?.summary?.replace("<[^>]*>".toRegex(), "")
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        // EPISODI
        val episodes = mutableListOf<Episode>()

        // TVMAZE EPISODI
        if (tvmaze != null) {
            val seasons = tvmazeGetSeasons(tvmaze.id) ?: emptyList()

            seasons.forEach { season ->
                val eps = tvmazeGetEpisodes(season.id) ?: emptyList()

                eps.forEach { ep ->
                    episodes.add(
                        newEpisode(url) {
                            this.name = ep.name
                            this.season = ep.season
                            this.episode = ep.number
                            this.description = ep.summary?.replace("<[^>]*>".toRegex(), "")
                            this.posterUrl = ep.image?.original ?: poster
                        }
                    )
                }
            }
        }

        // FALLBACK: EPISODI DAL SITO
        if (episodes.isEmpty()) {
            doc.select("a").forEach { el ->
                val link = el.attr("href")
                if (link.contains("stream") || link.contains("tape")) {
                    episodes.add(
                        newEpisode(link) {
                            this.name = "Episodio"
                        }
                    )
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ---------------------------------------------------------
    // LINKS
    // ---------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadExtractor(data, mainUrl, subtitleCallback, callback)
        return true
    }
}
