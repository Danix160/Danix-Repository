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

    // ============================================================
    // SPLIT BLOCCHI CON <br>
    // ============================================================

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
        val absoluteEpisode: Int?,
        val originalEpisode: Int?,
        val suffix: String?,
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

        // --------------------------------------------------------
        // EPISODI
        // --------------------------------------------------------

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

    // ============================================================
    // RILEVAMENTO TIPO
    // ============================================================

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

    // ============================================================
    // ANNO
    // ============================================================

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

    // ============================================================
    // TRAMA
    // ============================================================

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

    // ============================================================
    // PARSER EPISODI
    // ============================================================

    private fun parseEpisodes(
        content: Element?
    ): List<Episode> {

        if (content == null) {
            return emptyList()
        }

        val parsedEpisodes = mutableListOf<ToonEpisode>()

        var currentSeason: Int? = null
        var seasonFirstAbsolute: Int? = null

        var currentGroupName: String? = null
        var groupSeasonCounter = 0

        val explicitCounters = mutableMapOf<Int, Int>()

        // Esempio:
        // 1° Stagione
        // Stagione 1
        val seasonRegex = Regex(
            """(?:Stagione\s*(\d+)|(\d+)\s*°?\s*Stagione)""",
            RegexOption.IGNORE_CASE
        )

        // Esempio:
        // 52 – Il più forte vince
        val episodeRegex = Regex(
            """^\s*(\d{1,4})\s*[-–—]\s*(.+?)\s*$"""
        )

        // Esempio:
        // 1x01A – Bugie pericolose
        // 1x01B – Al lupo al lupo
        // 2x03 – Titolo episodio
        val explicitEpisodeRegex = Regex(
            """^\s*(\d+)\s*[xX×]\s*(\d+)([A-Za-z])?\s*[-–—]\s*(.+?)\s*$"""
        )

        val diskRegex = Regex(
            """^(?:Disk|Disc|Disco)\s*(\d+)\s*[-–—]?\s*(.*)$""",
            RegexOption.IGNORE_CASE
        )

        content.children().forEach elementLoop@ { element ->

            // ----------------------------------------------------
            // CAMBIO STAGIONE
            // ----------------------------------------------------
            if (
                element.tagName() == "h2" ||
                element.tagName() == "h3" ||
                element.tagName() == "h4"
            ) {
                
                val rawHeadingText = element.text().trim()
                val headingText = normalize(rawHeadingText)

                // ----------------------------------------------------
                // SPECIALI / OVA
                // ----------------------------------------------------

                if (
                    headingText.contains("speciali") ||
                    headingText.contains("special") ||
                    headingText.contains("ova") ||
                    headingText.contains("oav")
                ) {
                    currentSeason = 0
                    seasonFirstAbsolute = null
                    currentGroupName = null
                
                    return@elementLoop
                }

                // ----------------------------------------------------
                // DISK / DISC / DISCO
                // ----------------------------------------------------

                val diskMatch = diskRegex.find(rawHeadingText)
                
                if (diskMatch != null) {
                
                    val diskNumber = diskMatch
                        .groupValues
                        .getOrNull(1)
                        ?.toIntOrNull()
                
                    val diskTitle = diskMatch
                        .groupValues
                        .getOrNull(2)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                
                    groupSeasonCounter++
                
                    currentSeason = groupSeasonCounter
                    seasonFirstAbsolute = null
                
                    currentGroupName = buildString {
                
                        append("Disk")
                
                        if (diskNumber != null) {
                            append(" ")
                            append(diskNumber)
                        }
                
                        if (diskTitle != null) {
                            append(" - ")
                            append(diskTitle)
                        }
                    }
                
                    return@elementLoop
                }

                // ----------------------------------------------------
                // STAGIONI NORMALI
                // ----------------------------------------------------

                val match = seasonRegex.find(
                    element.text()
                )

                if (match != null) {

                    val seasonNumber =
                        match.groupValues
                            .getOrNull(1)
                            ?.toIntOrNull()
                            ?: match.groupValues
                                .getOrNull(2)
                                ?.toIntOrNull()

                    if (seasonNumber != null) {

                        currentGroupName = null

                        // Se l'HTML ripete due volte la stessa stagione,
                        // non azzeriamo inutilmente il riferimento.
                        if (currentSeason != seasonNumber) {
                            currentSeason = seasonNumber
                            seasonFirstAbsolute = null
                        }
                    }
                }

                return@elementLoop
            }

            // ----------------------------------------------------
            // BLOCCHI EPISODI
            // ----------------------------------------------------

            if (element.tagName() != "p") {
                return@elementLoop
            }

            val lines = splitByBr(element)

            lines.forEach lineLoop@ { line ->

                val cleanLine = line
                    .trim()
                    .replace(
                        Regex("""\s+"""),
                        " "
                    )

                    // ====================================================
                    // CAMBIO SEZIONE DENTRO I PARAGRAFI
                    // ====================================================
                    
                    val normalizedLine = normalize(cleanLine)
                    
                    // ----------------------------------------------------
                    // SPECIALI / OVA
                    // Esempio: <span>Speciali Tv:</span>
                    // ----------------------------------------------------
                    
                    if (
                        normalizedLine == "speciali" ||
                        normalizedLine.startsWith("speciali ") ||
                        normalizedLine == "special" ||
                        normalizedLine.startsWith("special ") ||
                        normalizedLine == "speciali tv" ||
                        normalizedLine.startsWith("speciali tv ") ||
                        normalizedLine == "ova" ||
                        normalizedLine.startsWith("ova ") ||
                        normalizedLine == "oav" ||
                        normalizedLine.startsWith("oav ")
                    ) {
                        currentSeason = 0
                        seasonFirstAbsolute = null
                        currentGroupName = null
                    
                        return@lineLoop
                    }
                    
                    // ----------------------------------------------------
                    // DISK / DISC / DISCO
                    // ----------------------------------------------------
                    
                    val paragraphDiskMatch = diskRegex.find(cleanLine)
                    
                    if (paragraphDiskMatch != null) {
                    
                        val diskNumber = paragraphDiskMatch
                            .groupValues
                            .getOrNull(1)
                            ?.toIntOrNull()
                    
                        val diskTitle = paragraphDiskMatch
                            .groupValues
                            .getOrNull(2)
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                    
                        groupSeasonCounter++
                    
                        currentSeason = groupSeasonCounter
                        seasonFirstAbsolute = null
                    
                        currentGroupName = buildString {
                    
                            append("Disk")
                    
                            if (diskNumber != null) {
                                append(" ")
                                append(diskNumber)
                            }
                    
                            if (diskTitle != null) {
                                append(" - ")
                                append(diskTitle)
                            }
                        }
                    
                        return@lineLoop
                    }

                // ====================================================
                // FORMATO ESPLICITO SxE / SxEA-B
                // ====================================================

                val explicitMatch =
                    explicitEpisodeRegex.find(cleanLine)

                if (explicitMatch != null) {

                    val explicitSeason = explicitMatch
                        .groupValues
                        .getOrNull(1)
                        ?.toIntOrNull()
                        ?: return@lineLoop

                    val originalEpisode = explicitMatch
                        .groupValues
                        .getOrNull(2)
                        ?.toIntOrNull()
                        ?: return@lineLoop

                    val suffix = explicitMatch
                        .groupValues
                        .getOrNull(3)
                        ?.trim()
                        ?.uppercase()
                        ?.takeIf {
                            it.isNotBlank()
                        }

                    var title = explicitMatch
                        .groupValues
                        .getOrNull(4)
                        ?.trim()
                        .orEmpty()

                    title = cleanEpisodeTitle(title)

                    if (title.isBlank()) {
                        return@lineLoop
                    }

                    val nextEpisode =
                        (explicitCounters[explicitSeason] ?: 0) + 1

                    explicitCounters[explicitSeason] =
                        nextEpisode

                    val originalLabel = buildString {

                        append(explicitSeason)
                        append("x")

                        append(
                            originalEpisode
                                .toString()
                                .padStart(2, '0')
                        )

                        if (suffix != null) {
                            append(suffix)
                        }
                    }

                    parsedEpisodes += ToonEpisode(
                        season = explicitSeason,
                        episode = nextEpisode,
                        absoluteEpisode = null,
                        originalEpisode = originalEpisode,
                        suffix = suffix,
                        title = "$originalLabel - $title"
                    )

                    return@lineLoop
                }

                // ====================================================
                // FORMATO NUMERICO ASSOLUTO
                // ====================================================

                val season = currentSeason
                    ?: return@lineLoop

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

                title = cleanEpisodeTitle(title)

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

                if (relativeEpisode <= 0) {
                    return@lineLoop
                }

                val finalTitle = if (currentGroupName != null) {
                    "${currentGroupName} - $title"
                } else {
                    title
                }
                
                parsedEpisodes += ToonEpisode(
                    season = season,
                    episode = relativeEpisode,
                    absoluteEpisode = absoluteEpisode,
                    originalEpisode = absoluteEpisode,
                    suffix = null,
                    title = finalTitle
                )
            }
        }

        return parsedEpisodes
            .distinctBy { item ->

                if (item.absoluteEpisode != null) {

                    "ABS-" +
                        item.season +
                        "-" +
                        item.absoluteEpisode

                } else {

                    "EXP-" +
                        item.season +
                        "-" +
                        item.originalEpisode +
                        "-" +
                        item.suffix
                }
            }
            .map { item ->

                val episodeId = buildString {

                    append("toonitalia://")
                    append(item.season)
                    append("/")

                    if (item.absoluteEpisode != null) {

                        append(item.absoluteEpisode)

                    } else {

                        append(
                            item.originalEpisode
                                ?: item.episode
                        )

                        if (item.suffix != null) {
                            append(item.suffix)
                        }
                    }
                }

                newEpisode(
                    episodeId
                ) {
                    name = item.title
                    season = item.season
                    episode = item.episode
                }
            }
    }

    // ============================================================
    // PULIZIA TITOLO EPISODIO
    // ============================================================

    private fun cleanEpisodeTitle(
        input: String
    ): String {

        return input
            .replace(
                Regex(
                    """\s*[-–—]\s*PLAYER\s*\d+.*$""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(
                Regex(
                    """\s+PLAYER\s*\d+.*$""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .trim()
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
    // NORMALIZZAZIONE
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
