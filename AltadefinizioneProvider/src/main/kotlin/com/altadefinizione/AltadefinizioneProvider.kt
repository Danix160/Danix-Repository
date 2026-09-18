package com.altadefinizione

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

class AltadefinizioneProvider : MainAPI() {

    override var mainUrl = "https://altadefinizione.fast"
    override var name = "Altadefinizione"
    override var lang = "it"

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
    "$mainUrl/" to "Home"
)

    // ============================================================
    // MAIN PAGE
    // ============================================================

    override suspend fun getMainPage(
    page: Int,
    request: MainPageRequest
): HomePageResponse {

    val document = app.get(mainUrl).document

    val sections = document
        .select("section.section")
        .mapNotNull { section ->

            val sectionTitle = section
                .selectFirst(".section-title")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val items = section
                .select(".movie")
                .mapNotNull { movie ->
                    toHomeSearchResponse(movie)
                }
                .distinctBy { it.url }

            if (items.isEmpty()) {
                return@mapNotNull null
            }

            HomePageList(
                name = sectionTitle,
                list = items,
                isHorizontalImages = false
            )
        }

    return newHomePageResponse(
        sections,
        false
    )
}

    private fun toHomeSearchResponse(
    movie: Element
): SearchResponse? {

    val link = movie.selectFirst(
        "a[href*='-streaming.html']"
    ) ?: return null

    val href = movie
        .attr("data-link")
        .ifBlank {
            link.attr("href")
        }
        .takeIf { it.isNotBlank() }
        ?: return null

    val url = fixUrl(href)

    val title = movie
        .attr("data-title")
        .ifBlank {
            link.attr("data-title")
        }
        .ifBlank {
            movie.selectFirst("img")
                ?.attr("alt")
                .orEmpty()
        }
        .trim()
        .let(::cleanTitle)
        .takeIf { it.isNotBlank() }
        ?: return null

    val poster = movie
        .selectFirst("img")
        ?.let {
            it.attr("data-src")
                .ifBlank { it.attr("src") }
        }
        ?.takeIf { it.isNotBlank() }
        ?.let(::fixUrl)

    val kind = movie
        .attr("data-kind")
        .ifBlank {
            link.attr("data-kind")
        }

    val isSeries =
        kind.equals("series", ignoreCase = true) ||
        url.contains("/serie-tv/")

    return if (isSeries) {

        newTvSeriesSearchResponse(
            title,
            url,
            TvType.TvSeries
        ) {
            this.posterUrl = poster
        }

    } else {

        newMovieSearchResponse(
            title,
            url,
            TvType.Movie
        ) {
            this.posterUrl = poster
        }
    }
}

    // ============================================================
    // SEARCH
    // ============================================================

    override suspend fun search(query: String): List<SearchResponse> {

    val encoded = java.net.URLEncoder.encode(
        query.trim(),
        Charsets.UTF_8.name()
    )

    val document = app.get(
        "$mainUrl/archivio?search=$encoded"
    ).document

    return document
        .select("table.catalog-table tbody tr.mlnew")
        .mapNotNull { row ->

            val link = row.selectFirst(
                "h2 a[href*='-streaming.html']"
            ) ?: row.selectFirst(
                "a[href*='-streaming.html']"
            ) ?: return@mapNotNull null

            val href = link.attr("href")
                .takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val url = fixUrl(href)

            val title = link.attr("title")
                .ifBlank { link.text() }
                .trim()
                .let(::cleanTitle)
                .takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val poster = row
                .selectFirst("img")
                ?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?.let(::fixUrl)

            val isSeries =
                url.contains("/serie-tv/")

            if (isSeries) {

                newTvSeriesSearchResponse(
                    title,
                    url,
                    TvType.TvSeries
                ) {
                    this.posterUrl = poster
                }

            } else {

                newMovieSearchResponse(
                    title,
                    url,
                    TvType.Movie
                ) {
                    this.posterUrl = poster
                }
            }
        }
        .distinctBy { it.url }
}

    // ============================================================
    // CARD PARSER
    // ============================================================

    private fun parseCards(
        document: Document
    ): List<SearchResponse> {

        /*
         * Usiamo href come elemento principale invece di dipendere
         * eccessivamente dalle classi CSS.
         *
         * Le schede hanno URL del tipo:
         *
         * /fantascienza/12345-titolo-streaming.html
         * /serie-tv/12345-titolo-streaming.html
         */

        return document
            .select("a[href*='-streaming.html']")
            .mapNotNull(::toSearchResponse)
            .distinctBy { it.url }
    }

    private fun toSearchResponse(
        element: Element
    ): SearchResponse? {

        val href = element.attr("href")

        if (
            href.isBlank() ||
            href.startsWith("#")
        ) {
            return null
        }

        val url = fixUrl(href)

        val image = element
            .selectFirst("img")

        val poster = image
            ?.let {
                it.attr("data-src")
                    .ifBlank { it.attr("src") }
            }
            ?.takeIf { it.isNotBlank() }
            ?.let(::fixUrl)

        var title = image
            ?.attr("alt")
            ?.trim()
            .orEmpty()

        if (title.isBlank()) {
            title = element
                .selectFirst(
                    ".title, .movie-title, .card-title, h2, h3"
                )
                ?.text()
                ?.trim()
                .orEmpty()
        }

        if (title.isBlank()) {
            title = element
                .attr("title")
                .trim()
        }

        if (title.isBlank()) {
            return null
        }

        title = cleanTitle(title)

        val isSeries =
            url.contains("/serie-tv/")

        return if (isSeries) {

            newTvSeriesSearchResponse(
                title,
                url,
                TvType.TvSeries
            ) {
                this.posterUrl = poster
            }

        } else {

            newMovieSearchResponse(
                title,
                url,
                TvType.Movie
            ) {
                this.posterUrl = poster
            }
        }
    }

    // ============================================================
    // LOAD
    // ============================================================

    override suspend fun load(
        url: String
    ): LoadResponse {

        val document = app.get(url).document

        val title = document
            .selectFirst(
                "h1.movie_entry-title, h1"
            )
            ?.text()
            ?.trim()
            ?.let(::cleanTitle)
            ?: throw ErrorLoadingException(
                "Titolo non trovato"
            )

        val poster = document
            .selectFirst(
                "img.movie_entry-poster"
            )
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let(::fixUrl)

        val background = document
            .selectFirst(
                ".player img.layer-image"
            )
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let(::fixUrl)

        val plot = extractPlot(document)

        val year = extractYear(document)

        val tags = extractGenres(document)

        val rating = extractRating(document)

        val actors = extractActors(document)

        val trailer = document
            .selectFirst(
                ".trailer iframe[src]"
            )
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }

        val player = document
            .selectFirst(
                ".player-embed iframe[src*='vidxgo.co']"
            )
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException(
                "Player VidxGo non trovato"
            )

        val isSeries =
            url.contains("/serie-tv/") ||
            document.selectFirst(
                ".series-select"
            ) != null

        return if (isSeries) {

            loadSeries(
                document = document,
                pageUrl = url,
                title = title,
                poster = poster,
                background = background,
                plot = plot,
                year = year,
                tags = tags,
                rating = rating,
                actors = actors,
                trailer = trailer,
                player = player
            )

        } else {

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                LinkData(
                    url = player,
                    referer = url
                )
            ) {

                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.tags = tags


                if (actors.isNotEmpty()) {
                    addActors(actors)
                }

                trailer?.let {
                    addTrailer(it)
                }
            }
        }
    }

    // ============================================================
    // SERIES
    // ============================================================

    private suspend fun loadSeries(
        document: Document,
        pageUrl: String,
        title: String,
        poster: String?,
        background: String?,
        plot: String?,
        year: Int?,
        tags: List<String>,
        rating: Int?,
        actors: List<String>,
        trailer: String?,
        player: String
    ): TvSeriesLoadResponse {

        /*
         * Esempio:
         *
         * https://v.vidxgo.co/0374419/1/1?se=0
         *
         * Ricaviamo:
         *
         * 0374419
         */

        val vidxId = extractVidxId(player)
            ?: throw ErrorLoadingException(
                "ID VidxGo non trovato"
            )

        val episodes = mutableListOf<Episode>()

        /*
         * Il DOM contiene:
         *
         * data-episode="1-1"
         * data-episode="1-2"
         * ...
         */

        document
            .select("[data-episode]")
            .forEach { element ->

                val value =
                    element.attr("data-episode")

                val match =
                    Regex("""(\d+)-(\d+)""")
                        .find(value)
                        ?: return@forEach

                val season =
                    match.groupValues[1]
                        .toIntOrNull()
                        ?: return@forEach

                val episode =
                    match.groupValues[2]
                        .toIntOrNull()
                        ?: return@forEach

                val episodeName =
                    element.text()
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?: "Episodio $episode"

                episodes += newEpisode(
                    LinkData(
                        url = "https://v.vidxgo.co/t/$vidxId/$season/$episode",
                        referer = pageUrl
                    )
                ) {
                    this.name = episodeName
                    this.season = season
                    this.episode = episode
                }
            }

        /*
         * Se il DOM iniziale non contiene tutte le stagioni ma
         * Next.js le inserisce nei dati serializzati, proviamo
         * anche una scansione dell'HTML.
         */

        extractEpisodesFromHtml(
            document.html(),
            vidxId,
            pageUrl
        ).forEach { candidate ->
        
            val existingIndex = episodes.indexOfFirst {
                it.season == candidate.season &&
                it.episode == candidate.episode
            }
        
            if (existingIndex >= 0) {
                // Il dato Next.js è più completo:
                // titolo, poster e descrizione.
                episodes[existingIndex] = candidate
            } else {
                episodes += candidate
            }
        }

        val sortedEpisodes =
            episodes.sortedWith(
                compareBy<Episode>(
                    { it.season ?: 0 },
                    { it.episode ?: 0 }
                )
            )

        return newTvSeriesLoadResponse(
            title,
            pageUrl,
            TvType.TvSeries,
            sortedEpisodes
        ) {

            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.plot = plot
            this.year = year
            this.tags = tags

            if (actors.isNotEmpty()) {
                addActors(actors)
            }

            trailer?.let {
                addTrailer(it)
            }
        }
    }

    // ============================================================
    // NEXT.JS EPISODE FALLBACK
    // ============================================================

    private fun extractEpisodesFromHtml(
    html: String,
    vidxId: String,
    referer: String
): List<Episode> {

    val results = mutableListOf<Episode>()

    /*
     * I dati Next.js arrivano escaped, ad esempio:
     *
     * \"number\":1,
     * \"name\":\"Stagione 1\",
     * \"episodes\":[
     *   {
     *     \"number\":1,
     *     \"title\":\"L'uomo nero\",
     *     \"plot\":\"\",
     *     \"still\":\"https://...\"
     *   }
     * ]
     *
     * Normalizziamo prima l'HTML per poterlo analizzare.
     */

    val normalized = html
        .replace("\\\\\"", "\"")
        .replace("\\\"", "\"")
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("\\u003c", "<")
        .replace("\\u003e", ">")

    /*
     * Troviamo ogni stagione e prendiamo il blocco degli episodi
     * fino alla stagione successiva.
     */
    val seasonRegex = Regex(
        """"number"\s*:\s*(\d+)\s*,\s*"name"\s*:\s*"Stagione\s+\d+"\s*,\s*"episodes"\s*:\s*\["""
    )

    val seasonMatches = seasonRegex.findAll(normalized).toList()

    seasonMatches.forEachIndexed { index, seasonMatch ->

        val season = seasonMatch.groupValues[1].toIntOrNull()
            ?: return@forEachIndexed

        val start = seasonMatch.range.last + 1

        val end = if (index + 1 < seasonMatches.size) {
            seasonMatches[index + 1].range.first
        } else {
            normalized.length
        }

        val seasonBlock = normalized.substring(start, end)

        /*
         * Ogni episodio:
         * number
         * title
         * plot
         * still
         */
        val episodeRegex = Regex(
            """"number"\s*:\s*(\d+)\s*,\s*"title"\s*:\s*(null|"(?:\\.|[^"\\])*")\s*,\s*"plot"\s*:\s*(null|"(?:\\.|[^"\\])*")\s*,\s*"still"\s*:\s*(null|"(?:\\.|[^"\\])*")"""
        )

        episodeRegex.findAll(seasonBlock).forEach { match ->

            val episode = match.groupValues[1].toIntOrNull()
                ?: return@forEach

            val title = decodeNextValue(
                match.groupValues[2]
            )

            val plot = decodeNextValue(
                match.groupValues[3]
            )

            val still = decodeNextValue(
                match.groupValues[4]
            )

            results += newEpisode(
                LinkData(
                    url = "https://v.vidxgo.co/t/$vidxId/$season/$episode",
                    referer = referer
                )
            ) {
                this.name = title ?: "Episodio $episode"
                this.season = season
                this.episode = episode

                still?.let {
                    this.posterUrl = it
                }

                plot?.let {
                    this.description = it
                }
            }
        }
    }

    return results
        .distinctBy {
            "${it.season}-${it.episode}"
        }
        .sortedWith(
            compareBy<Episode>(
                { it.season ?: 0 },
                { it.episode ?: 0 }
            )
        )
}

    private fun decodeNextValue(
    value: String
): String? {

    if (
        value.isBlank() ||
        value == "null"
    ) {
        return null
    }

    return value
        .removePrefix("\"")
        .removeSuffix("\"")
        .replace("\\\"", "\"")
        .replace("\\/", "/")
        .replace("\\n", "\n")
        .replace("\\r", "")
        .replace("\\t", "\t")
        .replace("\\u0026", "&")
        .replace("\\u003c", "<")
        .replace("\\u003e", ">")
        .trim()
        .takeIf { it.isNotBlank() }
}

    // ============================================================
    // LOAD LINKS
    // ============================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val link =
            try {
                parseJson<LinkData>(data)
            } catch (_: Exception) {
                LinkData(
                    url = data,
                    referer = mainUrl
                )
            }

        loadExtractor(
            link.url,
            link.referer,
            subtitleCallback,
            callback
        )

        return true
    }

    // ============================================================
    // METADATA
    // ============================================================

    private fun extractPlot(
        document: Document
    ): String? {

        val selectors = listOf(
            ".movie_entry-description",
            ".movie_entry-plot",
            ".description",
            ".plot",
            "[itemprop='description']"
        )

        for (selector in selectors) {

            val text = document
                .selectFirst(selector)
                ?.text()
                ?.trim()

            if (!text.isNullOrBlank()) {
                return text
            }
        }

        return null
    }

    private fun extractYear(
        document: Document
    ): Int? {

        val text = document
            .select("#movie-details")
            .text()

        return Regex(
            """\b(19\d{2}|20\d{2})\b"""
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun extractRating(
        document: Document
    ): Int? {

        val value = document
            .selectFirst(
                ".movie_entry-info .imdb, .meta .imdb"
            )
            ?.text()
            ?.replace(",", ".")
            ?.let {
                Regex("""\d+(?:\.\d+)?""")
                    .find(it)
                    ?.value
            }
            ?.toDoubleOrNull()
            ?: return null

        return (value * 1000)
            .toInt()
    }

    private fun extractGenres(
        document: Document
    ): List<String> {

        return document
            .select(
                "#movie-details a[href]"
            )
            .mapNotNull { element ->

                val href =
                    element.attr("href")

                val text =
                    element.text().trim()

                if (
                    text.isBlank() ||
                    href == "/" ||
                    href.contains("serie-tv") ||
                    href.contains("film")
                ) {
                    null
                } else {
                    text
                }
            }
            .filter {
                it.length in 3..30
            }
            .distinct()
            .take(8)
    }

    private fun extractActors(
        document: Document
    ): List<String> {

        val selectors = listOf(
            ".actors a",
            ".cast a",
            "[itemprop='actor']"
        )

        for (selector in selectors) {

            val actors =
                document
                    .select(selector)
                    .map {
                        it.text().trim()
                    }
                    .filter {
                        it.isNotBlank()
                    }
                    .distinct()

            if (actors.isNotEmpty()) {
                return actors
            }
        }

        return emptyList()
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private fun extractVidxId(
        url: String
    ): String? {

        return Regex(
            """vidxgo\.co/(?:t/)?(\d+)"""
        )
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun cleanTitle(
        title: String
    ): String {

        return title
            .replace(
                Regex(
                    """\s+streaming(?:\s+ita)?$""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .trim()
    }

    private fun fixUrl(
        url: String
    ): String {

        if (url.startsWith("http")) {
            return url
        }

        return try {

            URI(mainUrl)
                .resolve(url)
                .toString()

        } catch (_: Exception) {

            mainUrl.trimEnd('/') +
                "/" +
                url.trimStart('/')
        }
    }

    data class LinkData(
        val url: String,
        val referer: String
    )
}
