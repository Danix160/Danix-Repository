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
// TMDB SEARCH (con anno + fallback)
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

    // Match perfetto titolo + anno
    val filtered = results.firstOrNull { r ->
        val tmdbYear = when {
            isMovie -> (r["release_date"] as? String)?.take(4)?.toIntOrNull()
            else -> (r["first_air_date"] as? String)?.take(4)?.toIntOrNull()
        }
        tmdbYear == year
    }

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

    // Pokémon
    t = t.replace("(?i)pokemon".toRegex(), "Pokémon")
        .replace("(?i)pokèmon".toRegex(), "Pokémon")
        .replace("(?i)pokè mon".toRegex(), "Pokémon")
        .replace("(?i)poke mon".toRegex(), "Pokémon")

    return t
}

// -----------------------------
// PROVIDER PRINCIPALE
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

    // -----------------------------
    // CLEAN TITLE
    // -----------------------------
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
    // MAIN PAGE
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

            val tmdb = tmdbSearch(title, url.contains("/film/"), null)
            val posterFinal = tmdb?.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" } ?: poster

            homeResults.add(
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = posterFinal
                    this.type = if (url.contains("/film/")) TvType.Movie else TvType.TvSeries
                }
            )
        }

        return newHomePageResponse(request.name, homeResults)
    }

    // -----------------------------
    // SEARCH
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

                val tmdb = tmdbSearch(title, targetUrl.contains("/film/"), null)
                val posterFinal = tmdb?.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" } ?: poster

                results.add(
                    newMovieSearchResponse(title, targetUrl, TvType.Movie) {
                        this.posterUrl = posterFinal
                        this.type = if (targetUrl.contains("/film/")) TvType.Movie else TvType.TvSeries
                    }
                )
            }
        }

        return results.distinctBy { it.url }
    }

    // -----------------------------
    // LOAD (FILM + SERIE)
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
        // SERIE TV
        // -----------------------------
        if (!isMovie) {
            val episodesList = mutableListOf<Episode>()
            var currentSeason = 1

            document.select("table tr").forEach { row ->
                val header = row.selectFirst("td[colspan=4] b")
                if (header != null) {
                    val seasonMatch = "Stagione (\\d+)".toRegex().find(header.text())
                    if (seasonMatch != null) currentSeason = seasonMatch.groupValues[1].toInt()
                }

                val maxStreamLink = row.select("a[href*=/msf/]").firstOrNull()
                if (maxStreamLink != null) {
                    val fullText = row.selectFirst("td")?.text() ?: ""
                    val epMatch = "(\\d+)x(\\d+)".toRegex().find(fullText)
                    val episodeNumber = epMatch?.groupValues?.get(2)?.toIntOrNull()
                        ?: (episodesList.size + 1)

                    val episodePoster = tmdb?.id?.let { tmdbId ->
                        val apiUrl =
                            "https://api.themoviedb.org/3/tv/$tmdbId/season/$currentSeason/episode/$episodeNumber?api_key=e541cb159df14ce70fc51ab75703a1a2&language=it-IT"
                        val json = app.get(apiUrl).parsedSafe<Map<String, Any>>()
                        val still = json?.get("still_path") as? String
                        still?.let { "https://image.tmdb.org/t/p/w500$it" }
                    }

                    episodesList.add(
                        newEpisode(maxStreamLink.attr("href")) {
                            this.name = "Episodio $episodeNumber"
                            this.season = currentSeason
                            this.episode = episodeNumber
                            this.posterUrl = episodePoster ?: poster
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.plot = finalDescription
            }
        }

        // -----------------------------
        // FILM
        // -----------------------------
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
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
