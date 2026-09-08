package com.toonitalia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.loadExtractor
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
    ): List<ToonLine> {
    
        val html = element.html()
    
        return html
            .split(
                Regex(
                    """<br\s*/?>""",
                    RegexOption.IGNORE_CASE
                )
            )
            .mapNotNull { fragment ->
    
                val fragmentDocument =
                    org.jsoup.Jsoup.parse(fragment, mainUrl)
    
                val text = fragmentDocument
                    .text()
                    .trim()
    
                if (text.isBlank()) {
                    return@mapNotNull null
                }
    
                val links = fragmentDocument
                    .select("a[href]")
                    .mapNotNull { link ->
    
                        link.attr("abs:href")
                            .trim()
                            .takeIf {
                                it.isNotBlank()
                            }
                    }
                    .distinct()
    
                ToonLine(
                    text = text,
                    links = links
                )
            }
    }

    // ============================================================
    // LOAD
    // ============================================================

    private data class ToonLine(
    val text: String,
    val links: List<String>
)

    private data class ToonEpisode(
    val season: Int,
    val episode: Int,
    val absoluteEpisode: Int?,
    val originalEpisode: Int?,
    val suffix: String?,
    val title: String,
    val links: List<String> = emptyList()
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
    // LOAD LINKS
    // ============================================================
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
    
        val encodedPart = data
            .substringAfter("||", "")
            .trim()
    
        if (encodedPart.isBlank()) {
            return false
        }
    
        val playerLinks = encodedPart
            .split("|")
            .mapNotNull { encodedUrl ->
    
                runCatching {
                    java.net.URLDecoder.decode(
                        encodedUrl,
                        "UTF-8"
                    )
                }
                    .getOrNull()
                    ?.trim()
                    ?.takeIf {
                        it.startsWith("http://") ||
                            it.startsWith("https://")
                    }
            }
            .distinct()
    
        if (playerLinks.isEmpty()) {
            return false
        }
    
        var loaded = false
    
        playerLinks.forEach { playerUrl ->
    
            val result = runCatching {
                loadExtractor(
                    playerUrl,
                    subtitleCallback,
                    callback
                )
            }.getOrDefault(false)
    
            if (result) {
                loaded = true
            }
        }
    
        return loaded
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

        val explicitSeenCounts = mutableMapOf<Pair<Int, Int>, Int>()
        val explicitDuplicateOffsets = mutableMapOf<Int, Int>()

        val unnumberedSpecialCounters = mutableMapOf<String, Int>()

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

        val decimalEpisodeRegex = Regex(
            """^\s*(\d{1,4})\.(\d+)\s*[-–—]\s*(.+?)\s*$"""
        )

        // Esempio:
        // Special-Tv-01 – Avventura nell'ombelico dell'oceano
        val specialTvRegex = Regex(
            """^\s*Special[\s-]*Tv[\s-]*(\d+)\s*[-–—]\s*(.+?)\s*$""",
            RegexOption.IGNORE_CASE
        )

        val specialGenericRegex = Regex(
            """^\s*(OVA|OAV|Special|Extra)[\s-]*(\d+)\s*[-–—]\s*(.+?)\s*$""",
            RegexOption.IGNORE_CASE
        )

        val unnumberedSpecialRegex = Regex(
            """^\s*(?:(.+?)\s+)?(OVA|OAV|Speciale|Special|Extra)\s*[-–—]\s*(.+?)\s*$""",
            RegexOption.IGNORE_CASE
        )

        val multiEpisodeRegex = Regex(
            """^\s*(\d+)\s*[xX×]\s*(\d+(?:\s*-\s*\d+)+)\s*[-–—]\s*(.+?)\s*$"""
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
                    headingText.contains("oav") ||
                    headingText.contains("extra")
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

            lines.forEach lineLoop@ { lineData ->
            
                val cleanLine = lineData.text
                    .trim()
                    .replace(
                        Regex("""\s+"""),
                        " "
                    )
            
                val playerLinks = lineData.links

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
                        normalizedLine == "special" ||
                        normalizedLine == "speciali tv" ||
                        normalizedLine == "special tv" ||
                        normalizedLine == "episodi speciali" ||
                        normalizedLine == "ova" ||
                        normalizedLine == "oav" ||
                        normalizedLine == "extra" ||
                        normalizedLine == "episodi extra"
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
                // SPECIALI TV
                // ====================================================
                
                val specialMatch = specialTvRegex.find(cleanLine)
                
                if (specialMatch != null) {
                
                    val specialNumber = specialMatch
                        .groupValues
                        .getOrNull(1)
                        ?.toIntOrNull()
                        ?: return@lineLoop
                
                    var specialTitle = specialMatch
                        .groupValues
                        .getOrNull(2)
                        ?.trim()
                        .orEmpty()
                
                    specialTitle = cleanEpisodeTitle(specialTitle)
                
                    if (specialTitle.isBlank()) {
                        return@lineLoop
                    }
                
                    parsedEpisodes += ToonEpisode(
                        season = 0,
                        episode = specialNumber,
                        absoluteEpisode = null,
                        originalEpisode = specialNumber,
                        suffix = "TV",
                        title = specialTitle,
                        links = playerLinks
                    )
                
                    return@lineLoop
                }
                
                
                // ====================================================
                // OVA / OAV / SPECIAL / EXTRA NUMERATI
                // ====================================================
                
                val genericSpecialMatch = specialGenericRegex.find(cleanLine)
                
                if (genericSpecialMatch != null) {
                
                    val specialType = genericSpecialMatch
                        .groupValues
                        .getOrNull(1)
                        ?.uppercase()
                        .orEmpty()
                
                    val specialNumber = genericSpecialMatch
                        .groupValues
                        .getOrNull(2)
                        ?.toIntOrNull()
                        ?: return@lineLoop
                
                    var specialTitle = genericSpecialMatch
                        .groupValues
                        .getOrNull(3)
                        ?.trim()
                        .orEmpty()
                
                    specialTitle = cleanEpisodeTitle(specialTitle)
                
                    if (specialTitle.isBlank()) {
                        return@lineLoop
                    }
                
                    val label = when (specialType) {
                        "OAV" -> "OAV"
                        "OVA" -> "OVA"
                        "SPECIAL" -> "Special"
                        "EXTRA" -> "Extra"
                        else -> specialType
                    }
                
                    parsedEpisodes += ToonEpisode(
                        season = 0,
                        episode = specialNumber,
                        absoluteEpisode = null,
                        originalEpisode = specialNumber,
                        suffix = label.uppercase(),
                        title = "$label ${specialNumber.toString().padStart(2, '0')} - $specialTitle",
                        links = playerLinks
                    )
                
                    return@lineLoop
                }
                
                
                // ====================================================
                // OVA / OAV / SPECIAL / SPECIALE / EXTRA SENZA NUMERO
                // ====================================================
                
                val unnumberedSpecialMatch =
                    unnumberedSpecialRegex.find(cleanLine)
                
                if (unnumberedSpecialMatch != null) {
                
                    val specialType = unnumberedSpecialMatch
                        .groupValues
                        .getOrNull(2)
                        ?.uppercase()
                        .orEmpty()
                
                    var specialTitle = unnumberedSpecialMatch
                        .groupValues
                        .getOrNull(3)
                        ?.trim()
                        .orEmpty()
                
                    specialTitle = cleanEpisodeTitle(specialTitle)
                
                    if (specialTitle.isBlank()) {
                        return@lineLoop
                    }
                
                    val label = when (specialType) {
                        "OAV" -> "OAV"
                        "OVA" -> "OVA"
                        "SPECIALE" -> "Speciale"
                        "SPECIAL" -> "Special"
                        "EXTRA" -> "Extra"
                        else -> specialType
                    }
                
                    val specialNumber =
                        (unnumberedSpecialCounters[specialType] ?: 0) + 1
                
                    unnumberedSpecialCounters[specialType] = specialNumber
                
                    parsedEpisodes += ToonEpisode(
                        season = 0,
                        episode = specialNumber,
                        absoluteEpisode = null,
                        originalEpisode = specialNumber,
                        suffix = "${label.uppercase()}UN",
                        title = "$label ${specialNumber.toString().padStart(2, '0')} - $specialTitle",
                        links = playerLinks
                    )
                
                    return@lineLoop
                }

                    // ====================================================
                    // FORMATO MULTI-EPISODIO
                    // Esempio:
                    // 2x05-06-07 – Carly va in Giappone
                    // 3x08-09 – Lascio iCarly
                    // ====================================================
                    
                    val multiMatch = multiEpisodeRegex.find(cleanLine)
                    
                    if (multiMatch != null) {
                    
                        val explicitSeason = multiMatch
                            .groupValues
                            .getOrNull(1)
                            ?.toIntOrNull()
                            ?: return@lineLoop
                    
                        val episodeNumbers = multiMatch
                            .groupValues
                            .getOrNull(2)
                            ?.split(Regex("""\s*-\s*"""))
                            ?.mapNotNull { it.toIntOrNull() }
                            .orEmpty()
                    
                        var title = multiMatch
                            .groupValues
                            .getOrNull(3)
                            ?.trim()
                            .orEmpty()
                    
                        title = cleanEpisodeTitle(title)
                    
                        if (episodeNumbers.isEmpty() || title.isBlank()) {
                            return@lineLoop
                        }
                    
                        val firstEpisode = episodeNumbers.first()
                    
                        val sourceLabel = buildString {
                            append(explicitSeason)
                            append("x")
                            append(
                                episodeNumbers.joinToString("-") {
                                    it.toString().padStart(2, '0')
                                }
                            )
                        }
                    
                        parsedEpisodes += ToonEpisode(
                            season = explicitSeason,
                            episode = firstEpisode,
                            absoluteEpisode = null,
                            originalEpisode = firstEpisode,
                            suffix = "MULTI",
                            title = "$sourceLabel - $title",
                            links = playerLinks
                        )
                    
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

                    if (originalEpisode == 0) {

                        var specialTitle = explicitMatch
                            .groupValues
                            .getOrNull(4)
                            ?.trim()
                            .orEmpty()
                    
                        specialTitle = cleanEpisodeTitle(specialTitle)
                    
                        if (specialTitle.isBlank()) {
                            return@lineLoop
                        }
                    
                        parsedEpisodes += ToonEpisode(
                            season = 0,
                            episode = 1,
                            absoluteEpisode = null,
                            originalEpisode = 0,
                            suffix = "S${explicitSeason}E00",
                            title = "${explicitSeason}x00 - $specialTitle",
                            links = playerLinks
                        )
                    
                        return@lineLoop
                    }

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

                    val episodeKey = explicitSeason to originalEpisode

                    val seenCount =
                        explicitSeenCounts[episodeKey] ?: 0
                    
                    var duplicateOffset =
                        explicitDuplicateOffsets[explicitSeason] ?: 0
                    
                    if (seenCount > 0) {
                        duplicateOffset++
                    
                        explicitDuplicateOffsets[explicitSeason] =
                            duplicateOffset
                    }
                    
                    val nextEpisode =
                        originalEpisode + duplicateOffset
                    
                    explicitSeenCounts[episodeKey] =
                        seenCount + 1

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
                        title = "$originalLabel - $title",
                        links = playerLinks
                    )

                    return@lineLoop
                }
                
                val decimalMatch = decimalEpisodeRegex.find(cleanLine)
                    
                    if (decimalMatch != null) {
                    
                        val whole = decimalMatch
                            .groupValues
                            .getOrNull(1)
                            ?.toIntOrNull()
                            ?: return@lineLoop
                    
                        val decimal = decimalMatch
                            .groupValues
                            .getOrNull(2)
                            ?.toIntOrNull()
                            ?: return@lineLoop
                    
                        var title = decimalMatch
                            .groupValues
                            .getOrNull(3)
                            ?.trim()
                            .orEmpty()
                    
                        title = cleanEpisodeTitle(title)
                    
                        if (title.isBlank()) {
                            return@lineLoop
                        }
                    
                        val specialNumber = parsedEpisodes.count {
                            it.season == 0 &&
                                it.suffix?.startsWith("DECIMAL") == true
                        } + 1
                    
                        parsedEpisodes += ToonEpisode(
                            season = 0,
                            episode = specialNumber,
                            absoluteEpisode = null,
                            originalEpisode = whole,
                            suffix = "DECIMAL${whole}_${decimal}",
                            title = "$whole.$decimal - $title",
                            links = playerLinks
                        )
                    
                        return@lineLoop
                    }
                // ====================================================
                // FORMATO NUMERICO ASSOLUTO
                // ====================================================

                val season = currentSeason ?: 1

                val match = episodeRegex.find(cleanLine)
                    ?: return@lineLoop

                val absoluteEpisode = match
                    .groupValues
                    .getOrNull(1)
                    ?.toIntOrNull()
                    ?: return@lineLoop

                if (absoluteEpisode == 0) {

                    var specialTitle = match
                        .groupValues
                        .getOrNull(2)
                        ?.trim()
                        .orEmpty()
                
                    specialTitle = cleanEpisodeTitle(specialTitle)
                
                    if (specialTitle.isBlank()) {
                        return@lineLoop
                    }
                
                    parsedEpisodes += ToonEpisode(
                        season = 0,
                        episode = 1,
                        absoluteEpisode = null,
                        originalEpisode = 0,
                        suffix = "ZERO",
                        title = "00 - $specialTitle",
                        links = playerLinks
                    )
                
                    return@lineLoop
                }

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
                    title = finalTitle,
                    links = playerLinks
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
                        item.suffix +
                        "-" +
                        item.episode
                }
            }
            .map { item ->

                val encodedLinks = item.links
                    .map { link ->
                        java.net.URLEncoder.encode(
                            link,
                            "UTF-8"
                        )
                    }

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
                    
                        append("-")
                        append(item.episode)
                    }
                }

                val episodeData = buildString {

                    append(episodeId)
                
                    if (encodedLinks.isNotEmpty()) {
                        append("||")
                
                        append(
                            encodedLinks.joinToString("|")
                        )
                    }
                }

                newEpisode(
                    episodeData
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
