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
    override var name = "Altadefinizione Fast"
    override var lang = "it"

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Film",
        "$mainUrl/serie-tv/" to "Serie TV",
        "$mainUrl/cinema" to "Cinema",
        "$mainUrl/azione" to "Azione",
        "$mainUrl/commedia" to "Commedia",
        "$mainUrl/horror" to "Horror",
        "$mainUrl/fantascienza" to "Fantascienza",
        "$mainUrl/animazione" to "Animazione"
    )

    // ============================================================
    // MAIN PAGE
    // ============================================================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page <= 1) {
            request.data
        } else {
            "${request.data.trimEnd('/')}?page=$page"
        }

        val document = app.get(url).document

        val results = parseCards(document)

        return newHomePageResponse(
            request.name,
            results,
            hasNext = results.isNotEmpty()
        )
    }

    // ============================================================
    // SEARCH
    // ============================================================

    override suspend fun search(query: String): List<SearchResponse> {

        /*
         * Il sito usa un input "story".
         *
         * Manteniamo questo endpoint isolato così, se la ricerca
         * Next.js utilizza un endpoint differente, dovremo cambiare
         * solamente questa parte.
         */

        val encoded = java.net.URLEncoder.encode(
            query,
            Charsets.UTF_8.name()
        )

        val candidates = listOf(
            "$mainUrl/?story=$encoded",
            "$mainUrl/search?story=$encoded"
        )

        for (url in candidates) {

            try {

                val document = app.get(url).document
                val results = parseCards(document)

                if (results.isNotEmpty()) {
                    return results
                }

            } catch (_: Exception) {
            }
        }

        return emptyList()
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

            if (
                episodes.none {
                    it.season == candidate.season &&
                    it.episode == candidate.episode
                }
            ) {
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

        val results =
            mutableListOf<Episode>()

        /*
         * Primo fallback semplice:
         * cerca tutte le occorrenze "S-E" presenti come
         * data-episode nel sorgente serializzato.
         */

        Regex(
            """data-episode\\?["']?\s*[:=]\s*\\?["'](\d+)-(\d+)"""
        )
            .findAll(html)
            .forEach { match ->

                val season =
                    match.groupValues[1]
                        .toIntOrNull()
                        ?: return@forEach

                val episode =
                    match.groupValues[2]
                        .toIntOrNull()
                        ?: return@forEach

                results += newEpisode(
                    LinkData(
                        url = "https://v.vidxgo.co/t/$vidxId/$season/$episode",
                        referer = referer
                    )
                ) {
                    this.name = "Episodio $episode"
                    this.season = season
                    this.episode = episode
                }
            }

        return results
            .distinctBy {
                "${it.season}-${it.episode}"
            }
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
