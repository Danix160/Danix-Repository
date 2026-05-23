package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType

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

// -----------------------------
// TMDB SEARCH (con anno + filtro)
// -----------------------------
suspend fun MainAPI.tmdbSearch(title: String, isMovie: Boolean, year: Int?): TmdbSearchResult? {
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

    val json = app.get(url).parsedSafe<Map<String, Any>>() ?: return null
    val results = json["results"] as? List<Map<String, Any>> ?: return null

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

    val first = filtered ?: results.firstOrNull() ?: return null

    return TmdbSearchResult(
        id = (first["id"] as Number).toInt(),
        title = first["title"] as? String,
        name = first["name"] as? String,
        overview = first["overview"] as? String,
        poster_path = first["poster_path"] as? String
    )
}

// -----------------------------
// TMDB: INFO EPISODIO (titolo + trama + still)
// -----------------------------
data class TmdbEpisodeInfo(
    val name: String?,
    val overview: String?,
    val stillPath: String?
)

suspend fun MainAPI.getTmdbEpisodeInfo(tvId: Int, season: Int, episode: Int): TmdbEpisodeInfo? {
    val url =
        "https://api.themoviedb.org/3/tv/$tvId/season/$season/episode/$episode?api_key=e541cb159df14ce70fc51ab75703a1a2&language=it-IT"
    val json = app.get(url).parsedSafe<Map<String, Any>>() ?: return null

    val name = json["name"] as? String
    val overview = json["overview"] as? String
    val still = json["still_path"] as? String

    return TmdbEpisodeInfo(name, overview, still)
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

// -----------------------------
// PARSING NUMERO EPISODIO DAL TESTO
// -----------------------------
private fun parseEpisodeNumberFromText(text: String): Int? {
    val t = text.lowercase()

    // 1x01, 01x05, 2x3
    val rx1 = "(\\d+)x(\\d+)".toRegex()
    rx1.find(t)?.let {
        return it.groupValues[2].toIntOrNull()
    }

    // episodio 1, ep 1, ep. 1
    val rx2 = "(episodio|ep\\.?|episode|capitolo|parte)\\s*(\\d+)".toRegex()
    rx2.find(t)?.let {
        return it.groupValues[2].toIntOrNull()
    }

    // solo numero isolato (es. "1", "2")
    val rx3 = "\\b(\\d{1,3})\\b".toRegex()
    rx3.find(t)?.let {
        return it.groupValues[1].toIntOrNull()
    }

    return null
}

// -----------------------------
// PARSING STAGIONE+EPISODIO DAL TESTO (01x05, 02x13, ecc.)
// -----------------------------
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

    private fun cleanTitle(title: String): String {
        val isSubIta = title.contains("(?i)\\bSUB[- ]?ITA\\b".toRegex())

        var cleaned = title
            .replace(" in streaming - OnlineSerieTv", "")
            .replace("(?i)\\bSUB[- ]?ITA\\b".toRegex(), "")
            .replace("(?i)\\b(ITA|STAGIONE \\d+|STAGIONE)\\b".toRegex(), "")
            .replace("(?i)serie animata".toRegex(), "")
            .replace("""\s*[\(

\[-]?\s*(19|20)\d{2}\s*[\)\]

-]?\s*""".toRegex(), " ")
            .replace("""\s*[-–—:|]+\s*$""".toRegex(), "")
            .replace("""^\s*[-–—:|]+\s*""".toRegex(), "")
            .replace("""\s+""".toRegex(), " ")
            .trim()

        cleaned = fixApostrophes(cleaned)
        cleaned = fixSpecialCases(cleaned)

        if (isSubIta) cleaned = "$cleaned SUB ITA"
        return cleaned
    }

    // -----------------------------
    // MAIN PAGE (NO TMDB)
    // -----------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
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
                    this.type = if (url.contains("/film/")) TvType.Movie else TvType.TvSeries
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
                    this.type = if (url.contains("/film/")) TvType.Movie else TvType.TvSeries
                }
            )
        }

        return newHomePageResponse(request.name, homeResults)
    }

    // -----------------------------
    // SEARCH (NO TMDB)
    // -----------------------------
    override suspend fun search(query: String): List<SearchResponse> {
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
                        this.type = if (targetUrl.contains("/film/")) TvType.Movie else TvType.TvSeries
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
                        this.type = if (targetUrl.contains("/film/")) TvType.Movie else TvType.TvSeries
                    }
                )
            }
        }

        return results.distinctBy { it.url }
    }

    // -----------------------------
    // LOAD (FILM + SERIE) — TMDB QUI
    // -----------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("h1")?.text() ?: "Senza Titolo"
        val title = cleanTitle(rawTitle)
        val isMovie = !url.contains("/serietv/")
        val year = document.select("span:contains(Anno:) i").text().trim().toIntOrNull()

        val tmdb = tmdbSearch(title, isMovie, year)

        val poster = tmdb?.poster_path?.let { "https://image.tmdb.org/t/p/w780$it" }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val finalDescription = tmdb?.overview
            ?: document.select("b:contains(Trama), strong:contains(Trama)").firstOrNull()
                ?.nextElementSibling()?.text()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        // -----------------------------
        // FILM
        // -----------------------------
        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = finalDescription
            }
        }

        // -----------------------------
        // SERIE TV
        // -----------------------------
        val episodesList = mutableListOf<Episode>()

        document.select("table tr").forEach { row ->
            val maxStreamLink = row.select("a[href*=/msf/]").firstOrNull()
            if (maxStreamLink == null) return@forEach

            val fullText = row.selectFirst("td")?.text() ?: ""
            val se = parseSeasonAndEpisode(fullText)
            val explicitEpNum = parseEpisodeNumberFromText(fullText)

            val seasonNumber = se?.first ?: 1
            val epInSeason = se?.second ?: explicitEpNum ?: (episodesList.size + 1)

            var epName: String? = null
            var epPlot: String? = null
            var epPoster: String? = null

            if (tmdb != null) {
                getTmdbEpisodeInfo(tmdb.id, seasonNumber, epInSeason)?.let { info ->
                    epName = info.name
                    epPlot = info.overview
                    epPoster = info.stillPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                }
            }

            episodesList.add(
                newEpisode(maxStreamLink.attr("href")) {
                    this.name = epName ?: "Episodio $epInSeason"
                    this.season = seasonNumber
                    this.episode = epInSeason
                    this.posterUrl = epPoster ?: poster
                    this.description = epPlot
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
            this.posterUrl = poster
            this.plot = finalDescription
        }
    }

    // -----------------------------
    // LOAD LINKS
    // -----------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
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
