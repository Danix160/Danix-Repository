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
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbApiKey = "e541cb159df14ce70fc51ab75703a1a2"
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Film",
        "$mainUrl/serie-tv/" to "Serie TV"
    )

    // ---------------------------------------------------------
    // UTILITIES
    // ---------------------------------------------------------

    private fun cleanTitle(title: String): String {
        var cleaned = title
            .replace("’", "'")
            .replace("‘", "'")
            .replace("L uomo ragno", "L'uomo ragno", ignoreCase = true)
            .replace("pokemon", "Pokémon", ignoreCase = true)
            .replace(" in streaming - OnlineSerieTv", "", ignoreCase = true)

        val regexDaRimuovere =
            """(?i)\b(serie animata|serie tv|animazione|in streaming|online|hdtv|web-dl)\b""".toRegex()

        cleaned = cleaned
            .replace(regexDaRimuovere, "")
            .replace("""(?i)\bSUB[- ]?ITA\b""".toRegex(), "")
            .replace("""(?i)\b(ITA|STAGIONE \d+|STAGIONE)\b""".toRegex(), "")
            .replace("""\s*[\(

\[-]?\s*(19|20)\d{2}\s*[\)\]

-]?\s*""".toRegex(), " ")
            .replace("""\s+""".toRegex(), " ")
            .replace("'", "")
            .trim()

        return cleaned
    }

    private fun extractYearFallback(text: String): Int? {
        return """(19|20)\d{2}""".toRegex().find(text)?.value?.toIntOrNull()
    }

    private fun extractYearFromDocument(doc: org.jsoup.nodes.Document): Int? {
        return doc.select("span:contains(Anno:) i")
            .firstOrNull()
            ?.text()
            ?.trim()
            ?.toIntOrNull()
            ?: extractYearFallback(doc.text())
    }

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

    private fun isTitleMatching(query: String, tmdbTitle: String): Boolean {
        val q = normalizeText(query)
        val t = normalizeText(tmdbTitle)
        if (q.contains(t) || t.contains(q)) return true

        val queryWords = query.lowercase().split(" ").filter { it.length > 3 }
        val tmdbWords = tmdbTitle.lowercase().split(" ").filter { it.length > 3 }
        return queryWords.any { tmdbWords.contains(it) }
    }

    private fun isSubIta(raw: String): Boolean {
        return raw.contains("""(?i)\bSUB[- ]?ITA\b""".toRegex())
    }

    // ---------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = app.get(request.data).document
        val list = mutableListOf<SearchResponse>()

        doc.select(".uagb-post__inner-wrap").forEach { el ->
            val titleEl = el.selectFirst(".uagb-post__title a") ?: return@forEach
            val rawTitle = titleEl.text()
            val title = cleanTitle(rawTitle)
            val url = titleEl.attr("href")
            val type = if (url.contains("/film/")) TvType.Movie else TvType.TvSeries

            val year = extractYearFallback(rawTitle)
            val tmdbPoster =
                if (type == TvType.Movie) getTmdbMovieMetadata(title, year)?.posterPath
                else getTmdbTvMetadata(title, year)?.posterPath

            val poster =
                tmdbPoster?.let { "https://image.tmdb.org/t/p/w500$it" }
                    ?: el.selectFirst(".uagb-post__image img")?.attr("src")

            list.add(newMovieSearchResponse(title, url, type) { this.posterUrl = poster })
        }

        return newHomePageResponse(request.name, list)
    }

    // ---------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        val list = mutableListOf<SearchResponse>()

        doc.select(".movie").forEach { el ->
            val rawTitle = el.selectFirst("h2")?.text() ?: return@forEach
            val title = cleanTitle(rawTitle)
            val targetUrl =
                el.selectFirst(".imagen a")?.attr("href")
                    ?: el.selectFirst("a")?.attr("href")
                    ?: return@forEach

            val type = if (targetUrl.contains("/film/")) TvType.Movie else TvType.TvSeries

            val year = extractYearFallback(rawTitle)
            val tmdbPoster =
                if (type == TvType.Movie) getTmdbMovieMetadata(title, year)?.posterPath
                else getTmdbTvMetadata(title, year)?.posterPath

            val poster =
                tmdbPoster?.let { "https://image.tmdb.org/t/p/w500$it" }
                    ?: el.selectFirst("img")?.attr("src")

            list.add(newMovieSearchResponse(title, targetUrl, type) { this.posterUrl = poster })
        }

        return list.distinctBy { it.url }
    }

    // ---------------------------------------------------------
    // LOAD (MOVIE / TV)
    // ---------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val rawTitle = doc.selectFirst("h1")?.text() ?: "Senza Titolo"
        val titleClean = cleanTitle(rawTitle)
        val subIta = isSubIta(rawTitle)

        var year = extractYearFromDocument(doc)
        if (year == null) year = extractYearFallback(rawTitle)

        val sitePoster =
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".imagen img")?.attr("src")

        val isMovie = url.contains("/film/") || url.contains("/movies")

        // ---------------------------------------------------------
        // TV SERIES
        // ---------------------------------------------------------

        if (!isMovie) {
            val tmdbData = getTmdbTvMetadata(titleClean, year)
            val episodes = mutableListOf<Episode>()
            var epCount = 1

            val cachedSeasons = mutableMapOf<Int, Map<Int, TmdbEpisode>?>()
            val cachedSizes = mutableMapOf<Int, Int>()

            doc.select("table tr, div.data-content a, td a").forEach { el ->
                val link = el.attr("href")
                if (link.isBlank()) return@forEach
                if (!(link.contains("uprot") || link.contains("stream") || link.contains("tape") || link.contains("flexy"))) return@forEach

                val rowText =
                    el.parents().select("tr").first()?.selectFirst("td")?.text()
                        ?: el.text()

                val match = """(\d+)x(\d+)""".toRegex().find(rowText)
                var season = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                var episode = match?.groupValues?.get(2)?.toIntOrNull() ?: epCount++

                if (tmdbData != null) {
                    var checkSeason = season
                    while (true) {
                        if (!cachedSeasons.containsKey(checkSeason)) {
                            val eps = getTmdbSeasonEpisodes(tmdbData.id, checkSeason)
                            cachedSeasons[checkSeason] = eps
                            cachedSizes[checkSeason] = eps?.size ?: 0
                        }
                        val max = cachedSizes[checkSeason] ?: 0
                        if (max > 0 && episode > max) {
                            episode -= max
                            checkSeason++
                        } else {
                            season = checkSeason
                            break
                        }
                    }
                }

                val tmdbEp = tmdbData?.let { cachedSeasons[season]?.get(episode) }

                val epPoster =
                    tmdbEp?.stillPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                        ?: tmdbData?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                        ?: sitePoster

                episodes.add(
                    newEpisode(link) {
                        this.name = tmdbEp?.name ?: "Episodio $episode"
                        this.season = season
                        this.episode = episode
                        this.description = tmdbEp?.overview
                        this.posterUrl = epPoster
                    }
                )
            }

            val finalTitle = (tmdbData?.name ?: titleClean) + if (subIta) " SUB ITA" else ""

            return newTvSeriesLoadResponse(finalTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl =
                    tmdbData?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                        ?: sitePoster
                this.plot =
                    tmdbData?.overview
                        ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
            }
        }

        // ---------------------------------------------------------
        // MOVIE
        // ---------------------------------------------------------

        val tmdbMovie = getTmdbMovieMetadata(titleClean, year)
        val finalTitle = (tmdbMovie?.title ?: titleClean) + if (subIta) " SUB ITA" else ""

        return newMovieLoadResponse(finalTitle, url, TvType.Movie, url) {
            this.posterUrl =
                tmdbMovie?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                    ?: sitePoster
            this.plot =
                tmdbMovie?.overview
                    ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
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

    // ---------------------------------------------------------
    // TMDB HELPERS (con patch Pokémon)
    // ---------------------------------------------------------

    private suspend fun getTmdbTvMetadata(query: String, year: Int?): TmdbTvResult? {

        // PATCH SPECIALE PER POKÉMON
        if (query.contains("pokemon", ignoreCase = true)) {
            return try {
                val url = "$tmdbBaseUrl/search/tv?api_key=$tmdbApiKey&query=pokemon&language=it-IT"
                val results = app.get(url).parsed<TmdbTvResponse>().results ?: emptyList()

                results.firstOrNull { it.firstAirDate?.startsWith("1997") == true }
                    ?: results.firstOrNull { it.name.equals("Pokémon", ignoreCase = true) }
            } catch (e: Exception) {
                null
            }
        }

        // NORMALE PER TUTTE LE ALTRE SERIE
        val encodedQuery = query
        val yearParam = if (year != null) "&first_air_date_year=$year" else ""
        val url =
            "$tmdbBaseUrl/search/tv?api_key=$tmdbApiKey&query=$encodedQuery&language=it-IT$yearParam"

        return try {
            val results = app.get(url).parsed<TmdbTvResponse>().results ?: emptyList()

            results.firstOrNull {
                isTitleMatching(query, it.name) &&
                        (year == null || it.firstAirDate?.startsWith(year.toString()) == true)
            }
                ?: results.firstOrNull { isTitleMatching(query, it.name) }
                ?: app.get(
                    "$tmdbBaseUrl/search/tv?api_key=$tmdbApiKey&query=$encodedQuery&language=it-IT"
                ).parsed<TmdbTvResponse>().results?.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getTmdbMovieMetadata(query: String, year: Int?): TmdbMovieResult? {
        val encodedQuery = query
        val yearParam = if (year != null) "&year=$year" else ""
        val url =
            "$tmdbBaseUrl/search/movie?api_key=$tmdbApiKey&query=$encodedQuery&language=it-IT$yearParam"

        return try {
            val results = app.get(url).parsed<TmdbMovieResponse>().results ?: emptyList()

            results.firstOrNull {
                isTitleMatching(query, it.title) &&
                        (year == null || it.releaseDate?.startsWith(year.toString()) == true)
            }
                ?: results.firstOrNull { isTitleMatching(query, it.title) }
                ?: app.get(
                    "$tmdbBaseUrl/search/movie?api_key=$tmdbApiKey&query=$encodedQuery&language=it-IT"
                ).parsed<TmdbMovieResponse>().results?.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getTmdbSeasonEpisodes(
        tvId: Int,
        season: Int
    ): Map<Int, TmdbEpisode>? {
        return try {
            app.get(
                "$tmdbBaseUrl/tv/$tvId/season/$season?api_key=$tmdbApiKey&language=it-IT"
            ).parsed<TmdbSeasonResponse>().episodes?.associateBy { it.episodeNumber }
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
}
