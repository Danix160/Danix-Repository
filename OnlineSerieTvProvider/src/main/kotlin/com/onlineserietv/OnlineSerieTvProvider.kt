package com.onlineserietv

import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.SubtitleFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

// -----------------------------
// TMDB DATA CLASS
// -----------------------------
data class TmdbSearchResult(
    val id: Int,
    val title: String?,
    val name: String?,
    val overview: String?,
    val poster_path: String?
)

data class TmdbEpisodeInfo(
    val name: String?,
    val overview: String?,
    val stillPath: String?,
    val runtime: Int?
)

// -----------------------------
// TMDB SEARCH
// -----------------------------
suspend fun MainAPI.tmdbSearch(title: String, isMovie: Boolean, year: Int?): TmdbSearchResult? = withContext(Dispatchers.IO) {
    val type = if (isMovie) "movie" else "tv"

    val url = buildString {
        append("https://api.themoviedb.org/3/search/$type")
        append("?api_key=e541cb159df14ce70fc51ab75703a1a2")
        append("&language=it-IT")
        append("&query=" + title.replace(" ", "%20"))
        if (year != null) {
            append("&year=$year")
            append("&first_air_date_year=$year")
        }
    }

    val json = app.get(url).parsedSafe<Map<String, Any>>() ?: return@withContext null
    val results = (json["results"] as? List<*>)?.filterIsInstance<Map<String, Any>>() ?: return@withContext null

    val filtered = if (year != null) {
        results.firstOrNull { r ->
            val tmdbYear = if (isMovie) {
                (r["release_date"] as? String)?.take(4)?.toIntOrNull()
            } else {
                (r["first_air_date"] as? String)?.take(4)?.toIntOrNull()
            }
            tmdbYear == year
        }
    } else null

    val first = filtered ?: results.firstOrNull() ?: return@withContext null

    return@withContext TmdbSearchResult(
        id = (first["id"] as Number).toInt(),
        title = first["title"] as? String,
        name = first["name"] as? String,
        overview = first["overview"] as? String,
        poster_path = first["poster_path"] as? String
    )
}

// -----------------------------
// TMDB: TUTTA LA STAGIONE
// -----------------------------
suspend fun MainAPI.getTmdbSeason(tvId: Int, season: Int): Map<Int, TmdbEpisodeInfo> = withContext(Dispatchers.IO) {
    val url = "https://api.themoviedb.org/3/tv/$tvId/season/$season?api_key=e541cb159df14ce70fc51ab75703a1a2&language=it-IT"

    val json = app.get(url).parsedSafe<Map<String, Any>>() ?: return@withContext emptyMap()
    val eps = (json["episodes"] as? List<*>)?.filterIsInstance<Map<String, Any>>() ?: return@withContext emptyMap()

    return@withContext eps.associate { ep ->
        val num = (ep["episode_number"] as Number).toInt()
        num to TmdbEpisodeInfo(
            name = ep["name"] as? String,
            overview = ep["overview"] as? String,
            stillPath = ep["still_path"] as? String,
            runtime = (ep["runtime"] as? Number)?.toInt()
        )
    }
}

// -----------------------------
// CORREZIONI TITOLI
// -----------------------------
private fun fixApostrophes(title: String): String {
    return title
        .replace("\\bl\\s+uomo".toRegex(RegexOption.IGNORE_CASE), "l'uomo")
        .replace("\\bl\\s+amore".toRegex(RegexOption.IGNORE_CASE), "l'amore")
        .replace("\\bl\\s+ombra".toRegex(RegexOption.IGNORE_CASE), "l'ombra")
        .replace("\\bd\\s+amore".toRegex(RegexOption.IGNORE_CASE), "d'amore")
        .replace("\\bd\\s+oro".toRegex(RegexOption.IGNORE_CASE), "d'oro")
        .replace("\\bd\\s+acciaio".toRegex(RegexOption.IGNORE_CASE), "d'acciaio")
}

private fun fixSpecialCases(title: String): String {
    var t = title
    t = t.replace("(?i)pokemon".toRegex(), "Pokémon")
        .replace("(?i)pokèmon".toRegex(), "Pokémon")
        .replace("(?i)pokè mon".toRegex(), "Pokémon")
        .replace("(?i)poke mon".toRegex(), "Pokémon")
    return t
}

private fun cleanTitle(title: String): String {
    var cleaned = title
        .replace("(?i)\\bSUB\\s*[- ]?\\s*ITA\\b".toRegex(), "")
        .replace("(?i)\\bSUBITA\\b".toRegex(), "")
        .replace("(?i)\\bSUB-ITA\\b".toRegex(), "")
        .replace("(?i)\\bSUB IT\\b".toRegex(), "")
        .replace("(?i)\\bSUB-IT\\b".toRegex(), "")
        .replace("""\s+""".toRegex(), " ")
        .replace(" in streaming - OnlineSerieTv", "")
        .replace("(?i)\\b(ITA|STAGIONE \\d+|STAGIONE)\\b".toRegex(), "")
        .replace("(?i)serie animata".toRegex(), "")
        .replace("""\s*[\( \[\-]?\s*(19|20)\d{2}\s*[\)\] \-]?\s*""".toRegex(), " ")
        .replace("""\s*[-–—:|]+\s*$""".toRegex(), "")
        .replace("""^\s*[-–—:|]+\s*""".toRegex(), "")
        .trim()

    cleaned = fixApostrophes(cleaned)
    cleaned = fixSpecialCases(cleaned)

    return cleaned.trim()
}

// -----------------------------
// PARSING NUMERI EPISODIO/STAGIONE
// -----------------------------
private fun parseEpisodeNumberFromText(text: String): Int? {
    val t = text.lowercase()

    val rx1 = "(\\d+)x(\\d+)".toRegex()
    rx1.find(t)?.let {
        return it.groupValues[2].toIntOrNull()
    }

    val rx2 = "(episodio|ep\\.?|episode|capitolo|parte)\\s*(\\d+)".toRegex()
    rx2.find(t)?.let {
        return it.groupValues[2].toIntOrNull()
    }

    val rx3 = "\\b(\\d{1,3})\\b".toRegex()
    rx3.find(t)?.let {
        return it.groupValues[1].toIntOrNull()
    }

    return null
}

private fun parseSeasonAndEpisode(text: String): Pair<Int, Int>? {
    val t = text.lowercase()
    val rx = "(\\d{1,2})x(\\d{1,2})".toRegex()
    val m = rx.find(t) ?: return null
    val s = m.groupValues[1].toIntOrNull() ?: return null
    val e = m.groupValues[2].toIntOrNull() ?: return null
    return s to e
}

// -----------------------------
// PROVIDER
// -----------------------------
class OnlineSerieTvProvider : MainAPI() {
    override var mainUrl = "https://onlineserietv.mom"
    override var name = "OnlineSerieTv"
    override val hasMainPage = true
    override var lang = "it"
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "Film: Ultimi aggiunti",
        "$mainUrl/serie-tv/" to "Serie TV: Ultime aggiunte",
        "$mainUrl/serie-tv-generi/animazione/" to "Serie TV: Animazione",
        "$mainUrl/film-generi/animazione/" to "Film: Animazione",
        "$mainUrl/serie-tv-generi/action-adventure/" to "Serie TV: Azione e Avventura",
        "$mainUrl/film-generi/avventura/" to "Film: Avventura",
        "$mainUrl/film-generi/azione/" to "Film: Azione",
        "$mainUrl/film-generi/supereroi/" to "Film: Supereroi",
        "$mainUrl/serie-tv-generi/sci-fi-fantasy/" to "Serie TV: Fantascienza e Fantasy",
        "$mainUrl/film-generi/fantascienza/" to "Film: Fantascienza",
        "$mainUrl/film-generi/fantasy/" to "Film: Fantasy",
        "$mainUrl/serie-tv-generi/commedia/" to "Serie TV: Commedia",
        "$mainUrl/film-generi/commedia/" to "Film: Commedia",
        "$mainUrl/serie-tv-generi/crime/" to "Serie TV: Crime",
        "$mainUrl/serie-tv-generi/mistero/" to "Serie TV: Mistero",
        "$mainUrl/film-generi/horror/" to "Film: Horror",
        "$mainUrl/film-generi/thriller/" to "Film: Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? = withContext(Dispatchers.IO) {
        val document = app.get(request.data).document
        val homeResults = mutableListOf<SearchResponse>()

        document.select(".uagb-post__inner-wrap").forEach { element ->
            val titleEl = element.selectFirst(".uagb-post__title a") ?: return@forEach
            val rawTitle = titleEl.text()
            val title = cleanTitle(rawTitle)
            val url = titleEl.attr("href")
            val poster = element.selectFirst(".uagb-post__image img")?.attr("src")

            homeResults.add(
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                    this.type = if (url.contains("/film/") || url.contains("/movies/")) TvType.Movie else TvType.TvSeries
                }
            )
        }

        document.select(".movie").forEach { element ->
            val rawTitle = element.selectFirst("h2")?.text() ?: return@forEach
            val title = cleanTitle(rawTitle)
            val linkEl = element.selectFirst(".imagen a") ?: element.selectFirst("a") ?: return@forEach
            val url = linkEl.attr("href")
            val poster = element.selectFirst("img")?.attr("src")

            homeResults.add(
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                    this.type = if (url.contains("/film/") || url.contains("/movies/")) TvType.Movie else TvType.TvSeries
                }
            )
        }

        return@withContext newHomePageResponse(request.name, homeResults)
    }

    override suspend fun search(query: String): List<SearchResponse> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SearchResponse>()

        for (page in 1..5) {
            val url = if (page == 1) "$mainUrl/?s=$query" else "$mainUrl/page/$page/?s=$query"
            val response = app.get(url)
            if (response.code != 200) break

            val document = response.document

            document.select(".movie").forEach { element ->
                val rawTitle = element.selectFirst("h2")?.text() ?: return@forEach
                val title = cleanTitle(rawTitle)
                val targetUrl = element.selectFirst(".imagen a")?.attr("href")
                    ?: element.selectFirst("a")?.attr("href")
                    ?: return@forEach
                val poster = element.selectFirst("img")?.attr("src")

                results.add(
                    newMovieSearchResponse(title, targetUrl, TvType.Movie) {
                        this.posterUrl = poster
                        this.type = if (targetUrl.contains("/film/") || targetUrl.contains("/movies/")) TvType.Movie else TvType.TvSeries
                    }
                )
            }

            document.select(".uagb-post__inner-wrap").forEach { element ->
                val titleEl = element.selectFirst(".uagb-post__title a") ?: return@forEach
                val rawTitle = titleEl.text()
                val title = cleanTitle(rawTitle)
                val targetUrl = titleEl.attr("href")
                val poster = element.selectFirst(".uagb-post__image img")?.attr("src")

                results.add(
                    newMovieSearchResponse(title, targetUrl, TvType.Movie) {
                        this.posterUrl = poster
                        this.type = if (targetUrl.contains("/film/") || targetUrl.contains("/movies/")) TvType.Movie else TvType.TvSeries
                    }
                )
            }
        }

        return@withContext results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse = withContext(Dispatchers.IO) {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("h1")?.text() ?: "Senza Titolo"
        val title = cleanTitle(rawTitle)
        val isMovie = !url.contains("/serietv/") && !url.contains("/serie-tv/")
        val year = document.select("span:contains(Anno:) i").text().trim().toIntOrNull()

        val tmdb = tmdbSearch(title, isMovie, year)

        val poster = tmdb?.poster_path?.let { "https://image.tmdb.org/t/p/w780$it" }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val finalDescription = tmdb?.overview
            ?: document.select("b:contains(Trama), strong:contains(Trama)").firstOrNull()
                ?.nextElementSibling()?.text()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        if (isMovie) {
            var movieRuntime: Int? = null
            var movieGenres: List<String>? = null
            var movieYear: Int? = null
            var movieCast: List<ActorData>? = null
            var movieImdbId: String? = null

            if (tmdb != null) {
                val movieDetails = app.get(
                    "https://api.themoviedb.org/3/movie/${tmdb.id}?api_key=e541cb159df14ce70fc51ab75703a1a2&language=it-IT"
                ).parsedSafe<Map<String, Any>>()

                movieRuntime = (movieDetails?.get("runtime") as? Number)?.toInt()
                val genresList = (movieDetails?.get("genres") as? List<*>)?.filterIsInstance<Map<String, Any>>()
                movieGenres = genresList?.map { it["name"].toString() }
                movieYear = (movieDetails?.get("release_date") as? String)?.take(4)?.toIntOrNull()
                movieImdbId = movieDetails?.get("imdb_id") as? String

                val movieCredits = app.get(
                    "https://api.themoviedb.org/3/movie/${tmdb.id}/credits?api_key=e541cb159df14ce70fc51ab75703a1a2&language=it-IT"
                ).parsedSafe<Map<String, Any>>()

                movieCast = (movieCredits?.get("cast") as? List<*>)?.filterIsInstance<Map<String, Any>>()
                    ?.take(10)
                    ?.map {
                        val actor = Actor(
                            name = it["name"]?.toString() ?: "",
                            image = (it["profile_path"] as? String)?.let { p -> "https://image.tmdb.org/t/p/w500$p" }
                        )
                        ActorData(actor = actor, role = null)
                    }
            }

            return@withContext newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = finalDescription
                movieImdbId?.let { this.addImdbId(it) }
                if (movieRuntime != null && movieRuntime > 0) this.duration = movieRuntime
                if (!movieGenres.isNullOrEmpty()) this.tags = movieGenres
                if (movieYear != null) this.year = movieYear
                if (!movieCast.isNullOrEmpty()) this.actors = movieCast
            }
        }

        // SERIE TV
        val episodesList = mutableListOf<Episode>()
        val tmdbSeasonsCache = mutableMapOf<Int, Map<Int, TmdbEpisodeInfo>>()
        var tmdbSeasonsInfo: List<Pair<Int, Int>> = emptyList()
        var defaultRuntime: Int? = null
        var seriesYear: Int? = null
        var seriesGenres: List<String>? = null
        var seriesCast: List<ActorData>? = null
        var seriesImdbId: String? = null

        if (tmdb != null) {
            val tmdbShow = app.get(
                "https://api.themoviedb.org/3/tv/${tmdb.id}?api_key=e541cb159df14ce70fc51ab75703a1a2&language=it-IT&append_to_response=external_ids"
            ).parsedSafe<Map<String, Any>>()

            seriesYear = (tmdbShow?.get("first_air_date") as? String)?.take(4)?.toIntOrNull()
            val sGenresList = (tmdbShow?.get("genres") as? List<*>)?.filterIsInstance<Map<String, Any>>()
            seriesGenres = sGenresList?.map { it["name"].toString() }

            val externalIds = tmdbShow?.get("external_ids") as? Map<*, *>
            seriesImdbId = externalIds?.get("imdb_id") as? String

            val seriesCredits = app.get(
                "https://api.themoviedb.org/3/tv/${tmdb.id}/credits?api_key=e541cb159df14ce70fc51ab75703a1a2&language=it-IT"
            ).parsedSafe<Map<String, Any>>()

            seriesCast = (seriesCredits?.get("cast") as? List<*>)?.filterIsInstance<Map<String, Any>>()
                ?.take(10)
                ?.map {
                    val actor = Actor(
                        name = it["name"]?.toString() ?: "",
                        image = (it["profile_path"] as? String)?.let { p -> "https://image.tmdb.org/t/p/w500$p" }
                    )
                    ActorData(actor = actor, role = null)
                }

            val seasons = (tmdbShow?.get("seasons") as? List<*>)?.filterIsInstance<Map<String, Any>>()
            if (seasons != null) {
                tmdbSeasonsInfo = seasons
                    .filter { ((it["season_number"] as? Number)?.toInt() ?: 0) > 0 }
                    .sortedBy { (it["season_number"] as? Number)?.toInt() ?: 0 }
                    .map {
                        val sn = (it["season_number"] as Number).toInt()
                        val epCount = (it["episode_count"] as Number).toInt()
                        sn to epCount
                    }
            }

            val runtimes = (tmdbShow?.get("episode_run_time") as? List<*>)?.filterIsInstance<Int>()
            defaultRuntime = runtimes?.firstOrNull()
        }

        val rows = document.select("table tr")
        var siteMaxSeason = 1
        rows.forEach { row ->
            val fullText = row.selectFirst("td")?.text() ?: return@forEach
            val se = parseSeasonAndEpisode(fullText)
            if (se != null && se.first > siteMaxSeason) {
                siteMaxSeason = se.first
            }
        }

        var globalIndex = 0

        rows.forEach { row ->
            val streamLinkEl = row.select("a[href]").firstOrNull { a ->
                val href = a.attr("href")
                href.contains("/msf/") || href.contains("uprot") || href.contains("stream") || href.contains("tape") || href.contains("flexy") || href.contains("delta")
            } ?: row.select("a[href]").firstOrNull() ?: return@forEach

            val epUrl = streamLinkEl.attr("href")
            val fullText = row.selectFirst("td")?.text() ?: ""

            val se = parseSeasonAndEpisode(fullText)
            val explicitEpNum = parseEpisodeNumberFromText(fullText)

            val siteSeason = se?.first ?: 1
            val siteEpisode = se?.second ?: explicitEpNum ?: (episodesList.size + 1)

            globalIndex++

            var seasonNumber = siteSeason
            var epInSeason = siteEpisode

            if (tmdbSeasonsInfo.isNotEmpty()) {
                if (siteMaxSeason == 1 && tmdbSeasonsInfo.size > 1) {
                    var remaining = globalIndex
                    var mapped = false

                    for ((sn, epCount) in tmdbSeasonsInfo) {
                        if (remaining <= epCount) {
                            seasonNumber = sn
                            epInSeason = remaining
                            mapped = true
                            break
                        }
                        remaining -= epCount
                    }

                    if (!mapped) {
                        seasonNumber = siteSeason
                        epInSeason = siteEpisode
                    }
                }
            }

            val seasonMap = if (tmdb != null) {
                tmdbSeasonsCache.getOrPut(seasonNumber) {
                    getTmdbSeason(tmdb.id, seasonNumber)
                }
            } else emptyMap()

            val info = seasonMap[epInSeason]

            episodesList.add(
                newEpisode(epUrl) {
                    this.name = info?.name ?: "Episodio $epInSeason"
                    this.season = seasonNumber
                    this.episode = epInSeason
                    this.posterUrl = info?.stillPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: poster

                    val runtime = info?.runtime ?: defaultRuntime ?: 0
                    this.description = buildString {
                        append(info?.overview ?: "")
                        if (runtime > 0) {
                            append("\n\nDurata: ${runtime} min")
                        }
                    }
                }
            )
        }

        return@withContext newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
            this.posterUrl = poster
            this.plot = finalDescription
            seriesImdbId?.let { imdb -> this.addImdbId(imdb) }
            if (seriesYear != null) this.year = seriesYear
            if (!seriesGenres.isNullOrEmpty()) this.tags = seriesGenres
            if (!seriesCast.isNullOrEmpty()) this.actors = seriesCast
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {

        val fixedDataUrl = fixUrl(data)

        // Tenta il caricamento diretto
        val loadedDirect = loadExtractor(fixedDataUrl, subtitleCallback, callback)
        if (loadedDirect) return@withContext true

        val response = app.get(fixedDataUrl).text
        val doc = Jsoup.parse(response)

        // Cerca iFrame o link ed estrae usando la funzione della classe MainAPI
        doc.select("iframe[src], a[href]").forEach { element ->
            val link = element.attr("src").ifEmpty { element.attr("href") }
            if (link.isNotBlank()) {
                val fullUrl = fixUrl(link)
                loadExtractor(fullUrl, subtitleCallback, callback)
            }
        }

        return@withContext true
    }
}
