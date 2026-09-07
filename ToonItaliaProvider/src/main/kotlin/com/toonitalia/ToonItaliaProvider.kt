package com.toonitalia

import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Element

class ToonItaliaProvider : MainAPI() {

    override var mainUrl = "https://toonitalia.xyz"
    override var name = "ToonItalia"
    override var lang = "it"

    override val hasMainPage = true
    override val hasQuickSearch = false

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.Cartoon,
        TvType.AnimeMovie,
        TvType.Movie,
        TvType.TvSeries
    )

    
 // ============================================================
// HOMEPAGE
// ============================================================

    override val mainPage = mainPageOf(
        mainUrl to "Home"
    )
    
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
    
        if (page > 1) {
            return newHomePageResponse(
                emptyList(),
                hasNext = false
            )
        }
    
        val document = app.get(mainUrl).document
    
        val sections = document
            .select(".grid > .col")
            .mapNotNull { column ->
    
                val sectionTitle = column
                    .selectFirst("h2")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
    
                val type = getTypeFromHomeSection(sectionTitle)
    
                val items = column
                    .select(".item a.card-link[href]")
                    .mapNotNull { card ->
                        card.toHomeSearchResponse(type)
                    }
                    .distinctBy { it.url }
    
                if (items.isEmpty()) {
                    null
                } else {
                    HomePageList(
                        name = cleanSectionTitle(sectionTitle),
                        list = items
                    )
                }
            }
    
        return newHomePageResponse(
            sections,
            hasNext = false
        )
    }
    
    private fun getTypeFromHomeSection(
        title: String
    ): TvType? {
    
        val normalized = normalize(title)
    
        return when {
    
            normalized.contains("serie tv") ->
                TvType.TvSeries
    
            normalized.contains("film animazione") ->
                TvType.AnimeMovie
    
            normalized.contains("anime") ->
                TvType.Anime
    
            else ->
                null
        }
    }

    private fun Element.toHomeSearchResponse(
        forcedType: TvType?
    ): SearchResponse? {
    
        val href = attr("abs:href")
            .takeIf { it.isNotBlank() }
            ?: return null
    
        if (!href.startsWith(mainUrl)) {
            return null
        }
    
        val title = selectFirst(".title")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
    
        val poster = selectFirst("img")
            ?.attr("abs:src")
            ?.takeIf { it.isNotBlank() }
    
        val type = forcedType ?: TvType.TvSeries
    
        return when (type) {
    
            TvType.Anime -> {
                newAnimeSearchResponse(
                    title,
                    href,
                    TvType.Anime
                ) {
                    posterUrl = poster
                }
            }
    
            TvType.AnimeMovie -> {
                newMovieSearchResponse(
                    title,
                    href,
                    TvType.AnimeMovie
                ) {
                    posterUrl = poster
                }
            }
    
            TvType.TvSeries -> {
                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                ) {
                    posterUrl = poster
                }
            }
    
            else -> {
                newMovieSearchResponse(
                    title,
                    href,
                    type
                ) {
                    posterUrl = poster
                }
            }
        }
    }

    private fun cleanSectionTitle(
        title: String
    ): String {
    
        return title
            .replace(
                Regex("""^[^\p{L}\p{N}]+"""),
                ""
            )
            .trim()
    }

    // ============================================================
// SEARCH
// ============================================================

    override suspend fun search(
        query: String
    ): List<SearchResponse> {
    
        val cleanQuery = query.trim()
    
        if (cleanQuery.isBlank()) {
            return emptyList()
        }
    
        val document = runCatching {
            app.get(
                "$mainUrl/",
                params = mapOf(
                    "s" to cleanQuery
                )
            ).document
        }.getOrNull() ?: return emptyList()
    
        return document
            .select("article.post")
            .mapNotNull { article ->
                article.toSearchResult()
            }
            .distinctBy { it.url }
    }

    // ============================================================
    // CONVERSIONE ELEMENTI TOONITALIA
    // ============================================================

    private fun Element.toSearchResult(): SearchResponse? {

        val link = selectFirst(
            "h2.entry-title a[href], .entry-title a[href]"
        ) ?: return null
    
        val href = link
            .attr("abs:href")
            .takeIf { it.isNotBlank() }
            ?: return null
    
        val title = link
            .text()
            .trim()
            .takeIf { it.isNotBlank() }
            ?: return null
    
        if (!href.startsWith(mainUrl)) {
            return null
        }
    
        if (isNavigationUrl(href)) {
            return null
        }
    
        val classes = classNames()
            .map { it.lowercase() }
            .toSet()
    
        val type = when {
    
            classes.any {
                it == "category-serie-tv" ||
                it == "category-serie"
            } -> TvType.TvSeries
    
            classes.any {
                it == "category-film-animazione" ||
                it == "category-film"
            } -> TvType.AnimeMovie
    
            classes.any {
                it == "category-anime"
            } -> TvType.Anime
    
            else -> TvType.TvSeries
        }
    
        val poster = selectFirst("img")
            ?.let { img ->
    
                img.attr("abs:src")
                    .takeIf { it.isNotBlank() }
    
                    ?: img.attr("abs:data-src")
                        .takeIf { it.isNotBlank() }
            }
    
        return when (type) {
    
            TvType.Anime -> {
                newAnimeSearchResponse(
                    title,
                    href,
                    TvType.Anime
                ) {
                    posterUrl = poster
                }
            }
    
            TvType.AnimeMovie -> {
                newMovieSearchResponse(
                    title,
                    href,
                    TvType.AnimeMovie
                ) {
                    posterUrl = poster
                }
            }
    
            TvType.TvSeries -> {
                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                ) {
                    posterUrl = poster
                }
            }
    
            else -> {
                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                ) {
                    posterUrl = poster
                }
            }
        }
    }

    private fun splitByBr(
        element: Element
    ): List<String> {
    
        val html = element.html()
    
        return html
            .split(
                Regex(
                    """<br\s*/?>""",
                    RegexOption.IGNORE_CASE
                )
            )
            .map { fragment ->
    
                org.jsoup.Jsoup
                    .parse(fragment)
                    .text()
                    .trim()
            }
            .filter {
                it.isNotBlank()
            }
    }

    // ============================================================
    // LOAD
    // ============================================================

    private data class ToonEpisode(
    val season: Int,
    val episode: Int,
    val absoluteEpisode: Int,
    val title: String
)
    
    override suspend fun load(
        url: String
    ): LoadResponse? {
    
        val document = runCatching {
            app.get(url).document
        }.getOrNull() ?: return null
    
        val article = document.selectFirst("article")
        val content = document.selectFirst(".entry-content")
    
        // --------------------------------------------------------
        // TITOLO
        // --------------------------------------------------------
    
        val title = document
            .selectFirst("h1.entry-title")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    
            ?: content
                ?.selectFirst("h2")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
    
            ?: document
                .selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
    
            ?: return null
    
        // --------------------------------------------------------
        // POSTER
        // --------------------------------------------------------
    
        val poster = content
            ?.selectFirst("img")
            ?.let { img ->
    
                img.attr("abs:src")
                    .takeIf { it.isNotBlank() }
    
                    ?: img.attr("abs:data-src")
                        .takeIf { it.isNotBlank() }
            }
    
        // --------------------------------------------------------
        // ANNO
        // --------------------------------------------------------
    
        val year = extractYear(content)
    
        // --------------------------------------------------------
        // TRAMA
        // --------------------------------------------------------
    
        val plot = extractPlot(content)

        val episodes = parseEpisodes(content)
    
        // --------------------------------------------------------
        // TIPO
        // --------------------------------------------------------
    
        val type = detectLoadType(
            article = article,
            content = content
        )
    
        // --------------------------------------------------------
        // LOAD RESPONSE
        // --------------------------------------------------------
    
        return when (type) {
    
            TvType.Anime -> {
                newAnimeLoadResponse(
                    title,
                    url,
                    TvType.Anime
                ) {
                    posterUrl = poster
                    this.year = year
                    this.plot = plot
            
                    addEpisodes(
                        DubStatus.Dubbed,
                        episodes
                    )
                }
            }
    
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                    title,
                    url,
                    TvType.TvSeries,
                    episodes
                ) {
                    posterUrl = poster
                    this.year = year
                    this.plot = plot
                }
            }
    
            TvType.AnimeMovie -> {
                newMovieLoadResponse(
                    title,
                    url,
                    TvType.AnimeMovie,
                    url
                ) {
                    posterUrl = poster
                    this.year = year
                    this.plot = plot
                }
            }
    
            else -> {
                newMovieLoadResponse(
                    title,
                    url,
                    TvType.Movie,
                    url
                ) {
                    posterUrl = poster
                    this.year = year
                    this.plot = plot
                }
            }
        }
    }

    private fun detectLoadType(
        article: Element?,
        content: Element?
    ): TvType {
    
        val classes = article
            ?.classNames()
            ?.map { it.lowercase() }
            ?.toSet()
            ?: emptySet()
    
        return when {
    
            classes.any {
                it == "category-film-animazione" ||
                it == "category-film"
            } -> TvType.AnimeMovie
    
            classes.any {
                it == "category-serie-tv" ||
                it == "category-serie"
            } -> TvType.TvSeries
    
            classes.any {
                it == "category-anime"
            } -> TvType.Anime
    
            else -> {
                detectTypeFromContent(content)
            }
        }
    }

    private fun detectTypeFromContent(
        content: Element?
    ): TvType {
    
        val text = normalize(
            content
                ?.text()
                .orEmpty()
        )
    
        return when {
    
            text.contains("film animazione") ->
                TvType.AnimeMovie
    
            text.contains("serie tv") ->
                TvType.TvSeries
    
            text.contains("anime") ->
                TvType.Anime
    
            else ->
                TvType.TvSeries
        }
    }

    private fun extractYear(
        content: Element?
    ): Int? {
    
        if (content == null) {
            return null
        }
    
        val text = content.text()
    
        val publicationRegex = Regex(
            """Data\s+di\s+pubblicazione\s*:?\s*(?:Jap\s*:?\s*)?(\d{4})""",
            RegexOption.IGNORE_CASE
        )
    
        publicationRegex
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let {
                return it
            }
    
        val genericYearRegex = Regex(
            """\b(19\d{2}|20\d{2})\b"""
        )
    
        return genericYearRegex
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun extractPlot(
        content: Element?
    ): String? {
    
        if (content == null) {
            return null
        }
    
        val plotHeader = content
            .select("h2, h3, h4")
            .firstOrNull { element ->
    
                normalize(element.text())
                    .startsWith("trama")
            }
    
        if (plotHeader != null) {
    
            val paragraph = plotHeader
                .nextElementSibling()
    
            if (paragraph != null) {
    
                val clone = paragraph.clone()
    
                clone
                    .select("a")
                    .filter {
                        normalize(it.text()) == "wikipedia"
                    }
                    .forEach {
                        it.remove()
                    }
    
                var text = clone
                    .text()
                    .trim()
    
                text = text
                    .replace(
                        Regex(
                            """Fonte\s*:?.*$""",
                            setOf(
                                RegexOption.IGNORE_CASE,
                                RegexOption.DOT_MATCHES_ALL
                            )
                        ),
                        ""
                    )
                    .trim()
    
                if (text.isNotBlank()) {
                    return text
                }
            }
        }
    
        return null
    }

    private fun parseEpisodes(
        content: Element?
    ): List<Episode> {
    
        if (content == null) {
            return emptyList()
        }
    
        val parsedEpisodes = mutableListOf<ToonEpisode>()
    
        var currentSeason: Int? = null
        var seasonFirstAbsolute: Int? = null
    
        val seasonRegex = Regex(
            """(\d+)\s*°?\s*Stagione""",
            RegexOption.IGNORE_CASE
        )
    
        val episodeRegex = Regex(
            """^\s*(\d{1,4})\s*[-–—]\s*(.+?)\s*$"""
        )
    
        content.children().forEach { element ->
    
            // ----------------------------------------------------
            // CAMBIO STAGIONE
            // ----------------------------------------------------
    
            if (
                element.tagName() == "h2" ||
                element.tagName() == "h3" ||
                element.tagName() == "h4"
            ) {
    
                val match = seasonRegex.find(
                    element.text()
                )
    
                if (match != null) {
    
                    currentSeason = match
                        .groupValues
                        .getOrNull(1)
                        ?.toIntOrNull()
    
                    seasonFirstAbsolute = null
                }
    
                return@forEach
            }
    
            // ----------------------------------------------------
            // BLOCCHI EPISODI
            // ----------------------------------------------------
    
            if (element.tagName() != "p") {
                return@forEach
            }
    
            val season = currentSeason
                ?: return@forEach
    
            val lines = splitByBr(element)
    
            lines.forEach lineLoop@ { line ->

            val cleanLine = line
                .trim()
                .replace(
                    Regex("""\s+"""),
                    " "
                )
        
            val match = episodeRegex.find(cleanLine)
                ?: return@lineLoop
        
            val absoluteEpisode = match
                .groupValues
                .getOrNull(1)
                ?.toIntOrNull()
                ?: return@lineLoop
        
            var title = match
                .groupValues
                .getOrNull(2)
                ?.trim()
                .orEmpty()
        
            title = title
                .replace(
                    Regex(
                        """\s*[-–—]\s*PLAYER\s*\d+.*$""",
                        RegexOption.IGNORE_CASE
                    ),
                    ""
                )
                .trim()
        
            if (title.isBlank()) {
                return@lineLoop
            }
                if (seasonFirstAbsolute == null) {
                    seasonFirstAbsolute = absoluteEpisode
                }
    
                val relativeEpisode =
                    absoluteEpisode -
                    (seasonFirstAbsolute ?: absoluteEpisode) +
                    1
    
                parsedEpisodes += ToonEpisode(
                    season = season,
                    episode = relativeEpisode,
                    absoluteEpisode = absoluteEpisode,
                    title = title
                )
            }
        }
    
        return parsedEpisodes
            .distinctBy {
                "${it.season}-${it.absoluteEpisode}"
            }
            .map { item ->
    
                newEpisode(
                    "toonitalia://${item.season}/${item.absoluteEpisode}"
                ) {
                    name = item.title
                    season = item.season
                    episode = item.episode
                }
            }
    }
    
    // ============================================================
    // FILTRI
    // ============================================================

    private fun isNavigationUrl(
        url: String
    ): Boolean {

        val clean = url
            .substringBefore("#")
            .trimEnd('/')

        val excluded = setOf(
            mainUrl,
            "$mainUrl/anime-ita",
            "$mainUrl/contatti",
            "$mainUrl/film-animazione",
            "$mainUrl/serie-tv"
        )

        return clean in excluded
    }

    // ============================================================
    // RICERCA
    // ============================================================

    private fun normalize(
        text: String
    ): String {

        return text
            .lowercase()
            .replace("à", "a")
            .replace("è", "e")
            .replace("é", "e")
            .replace("ì", "i")
            .replace("ò", "o")
            .replace("ù", "u")
            .replace(
                Regex("""[^\p{L}\p{N}\s]"""),
                " "
            )
            .replace(
                Regex("""\s+"""),
                " "
            )
            .trim()
    }
}
