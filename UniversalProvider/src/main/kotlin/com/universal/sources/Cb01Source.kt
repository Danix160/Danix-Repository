package com.universal.sources

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.universal.models.ProviderEpisode
import com.universal.models.UniversalMedia
import com.universal.utils.EpisodeMapper
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.Normalizer

class Cb01Source : SourceAdapter {

    override val name =
        "CB01"

    /*
     * CB01 può portare a Uprot
     * e quindi richiedere CAPTCHA.
     */
    override val requiresInteraction =
        true

    /*
     * Altadefinizione = 10
     * OnlineSerieTV   = 20
     * CB01            = 30
     */
    override val priority =
        30

    companion object {

        private const val TAG =
            "UNIVERSAL_CB01"

        private const val MAIN_URL =
            "https://cb01uno.cam"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
    }

    private val headers =
        mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$MAIN_URL/"
        )

    private val supportedHosts =
        listOf(
            "voe",
            "mixdrop",
            "streamtape",
            "fastream",
            "filemoon",
            "wolfstream",
            "streamwish",
            "maxstream",
            "lulustream",
            "uprot",
            "stayonline",
            "swzz",
            "supervideo",
            "vidmoly",
            "maxsa"
        )

    // ============================================================
    // MODELLI
    // ============================================================

    private data class Candidate(
        val title: String,
        val url: String,
        val isTv: Boolean,
        val score: Int
    )

    private data class ParsedEpisodeNumber(
        val season: Int?,
        val episode: Int,
        val part: Int? = null
    )

    // ============================================================
    // URL
    // ============================================================

    private fun fixUrl(
        value: String
    ): String {

        val url =
            value.trim()

        return when {

            url.isBlank() ->
                ""

            url.startsWith(
                "http",
                ignoreCase = true
            ) ->
                url

            url.startsWith("//") ->
                "https:$url"

            url.startsWith("/") ->
                MAIN_URL.trimEnd('/') +
                    url

            else ->
                "$MAIN_URL/$url"
        }
    }

    // ============================================================
    // NORMALIZZAZIONE
    // ============================================================

    private fun normalize(
        value: String?
    ): String {

        if (
            value.isNullOrBlank()
        ) {
            return ""
        }

        return Normalizer
            .normalize(
                value,
                Normalizer.Form.NFD
            )
            .replace(
                Regex(
                    "\\p{InCombiningDiacriticalMarks}+"
                ),
                ""
            )
            .lowercase()
            .replace(
                "&",
                " e "
            )
            .replace(
                Regex(
                    "[^a-z0-9]+"
                ),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    private fun cleanTitle(
        value: String
    ): String {

        return value
            .replace(
                Regex(
                    """(?i)\b(?:streaming|completa|ITA|HD|FullHD)\b"""
                ),
                " "
            )
            .replace(
                Regex(
                    """(?i)film gratis by cb01 official"""
                ),
                " "
            )
            .replace(
                Regex(
                    """(?i)serie tv gratis by cb01 official"""
                ),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    // ============================================================
    // MATCH TITOLO
    // ============================================================

    private fun calculateTitleScore(
        media: UniversalMedia,
        candidateTitle: String
    ): Int {

        val candidate =
            normalize(
                candidateTitle
            )

        val title =
            normalize(
                media.title
            )

        val original =
            normalize(
                media.originalTitle
            )

        if (candidate.isBlank()) {
            return 0
        }

        var score =
            0

        if (
            title.isNotBlank() &&
            candidate == title
        ) {
            score += 100
        }

        if (
            original.isNotBlank() &&
            candidate == original
        ) {
            score += 90
        }

        if (
            title.isNotBlank() &&
            (
                candidate.contains(title) ||
                    title.contains(candidate)
                )
        ) {
            score += 40
        }

        if (
            original.isNotBlank() &&
            (
                candidate.contains(original) ||
                    original.contains(candidate)
                )
        ) {
            score += 35
        }

        val words =
            title
                .split(" ")
                .filter {
                    it.length >= 3
                }

        score +=
            words.count {
                candidate.contains(it)
            } * 5

        return score
    }

    // ============================================================
    // RISULTATO RICERCA
    // ============================================================

    private fun parseCandidate(
        element: Element,
        media: UniversalMedia,
        searchIsTv: Boolean
    ): Candidate? {

        val anchor =
            element.selectFirst(
                ".card-title a, " +
                    "h3 a, " +
                    "h2 a, " +
                    ".post-title a, " +
                    "a[title]"
            )
                ?: return null

        val rawTitle =
            anchor.text()
                .trim()

        val title =
            cleanTitle(
                rawTitle
            )

        val url =
            fixUrl(
                anchor.attr("abs:href")
                    .ifBlank {
                        anchor.attr("href")
                    }
            )

        if (
            title.isBlank() ||
            url.isBlank()
        ) {
            return null
        }

        if (
            url.contains("/tag/") ||
            url.contains("/category/")
        ) {
            return null
        }

        val isTv =
            searchIsTv ||
                url.contains(
                    "/serietv/",
                    ignoreCase = true
                ) ||
                url.contains(
                    "/serie/",
                    ignoreCase = true
                ) ||
                rawTitle.contains(
                    "stagion",
                    ignoreCase = true
                ) ||
                rawTitle.contains(
                    "serie tv",
                    ignoreCase = true
                )

        var score =
            calculateTitleScore(
                media,
                title
            )

        if (
            media.isMovie ==
            !isTv
        ) {
            score += 30
        } else {
            score -= 50
        }

        return Candidate(
            title = title,
            url = url,
            isTv = isTv,
            score = score
        )
    }

    // ============================================================
    // RICERCA
    // ============================================================

    private suspend fun searchCandidates(
        media: UniversalMedia
    ): List<Candidate> {

        val queries =
            buildList {

                add(
                    media.title
                )

                media.originalTitle
                    ?.takeIf {
                        it.isNotBlank() &&
                            !it.equals(
                                media.title,
                                ignoreCase = true
                            )
                    }
                    ?.let {
                        add(it)
                    }
            }
                .distinct()

        val result =
            mutableListOf<Candidate>()

        queries.forEach { query ->

            val encoded =
                URLEncoder.encode(
                    query,
                    "UTF-8"
                )

            val bases =
                listOf(
                    "$MAIN_URL/?s=$encoded" to false,
                    "$MAIN_URL/serietv/?s=$encoded" to true
                )

            bases.forEach { base ->

                val baseUrl =
                    base.first

                val searchIsTv =
                    base.second

                for (page in 1..5) {

                    val url =
                        if (page == 1) {

                            baseUrl

                        } else if (searchIsTv) {

                            "$MAIN_URL/serietv/page/$page/?s=$encoded"

                        } else {

                            "$MAIN_URL/page/$page/?s=$encoded"
                        }

                    Log.d(
                        TAG,
                        "SEARCH [$query] page=$page tv=$searchIsTv"
                    )

                    val response =
                        try {

                            app.get(
                                url,
                                headers = headers
                            )

                        } catch (
                            e: Exception
                        ) {

                            Log.e(
                                TAG,
                                "Errore ricerca CB01: ${e.message}"
                            )

                            break
                        }

                    if (
                        response.code !=
                        200
                    ) {
                        break
                    }

                    val document =
                        response.document

                    val blocks =
                        document.select(
                            "article, " +
                                "div.card, " +
                                "div.post-video, " +
                                ".result-item, " +
                                ".post, " +
                                ".mp-post, " +
                                ".entry, " +
                                ".card-content"
                        )

                    if (blocks.isEmpty()) {
                        break
                    }

                    val pageResults =
                        blocks
                            .mapNotNull {
                                parseCandidate(
                                    it,
                                    media,
                                    searchIsTv
                                )
                            }

                    result.addAll(
                        pageResults
                    )

                    /*
                     * Titolo già praticamente perfetto.
                     */
                    if (
                        result.any {
                            it.score >= 120
                        }
                    ) {
                        break
                    }
                }
            }
        }

        return result
            .distinctBy {
                it.url
            }
            .sortedByDescending {
                it.score
            }
    }

    // ============================================================
    // ANNO
    // ============================================================

    private fun findYear(
        document: Document
    ): Int? {

        val preferred =
            document.select(
                "*:matchesOwn((?i)Anno)"
            )
                .text()

        Regex(
            """(19|20)\d{2}"""
        )
            .find(preferred)
            ?.value
            ?.toIntOrNull()
            ?.let {
                return it
            }

        /*
         * Fallback.
         */
        return Regex(
            """(19|20)\d{2}"""
        )
            .find(
                document.body()
                    ?.text()
                    .orEmpty()
            )
            ?.value
            ?.toIntOrNull()
    }

    // ============================================================
    // MIGLIOR PAGINA
    // ============================================================

    private suspend fun selectBestCandidate(
        media: UniversalMedia
    ): Pair<Candidate, Document>? {

        val candidates =
            searchCandidates(
                media
            )

        if (
            candidates.isEmpty()
        ) {

            Log.d(
                TAG,
                "Nessun candidato CB01"
            )

            return null
        }

        var best:
            Pair<Candidate, Document>? =
            null

        var bestScore =
            Int.MIN_VALUE

        candidates
            .take(8)
            .forEach { candidate ->

                try {

                    val document =
                        app.get(
                            candidate.url,
                            headers = headers
                        ).document

                    var score =
                        candidate.score

                    val year =
                        findYear(
                            document
                        )

                    if (
                        media.year != null &&
                        year != null
                    ) {

                        val difference =
                            kotlin.math.abs(
                                media.year -
                                    year
                            )

                        if (
                            difference <= 1
                        ) {

                            score +=
                                120

                        } else {

                            /*
                             * Come AD01/OSTV:
                             * per serie omonime di anni diversi
                             * scartiamo direttamente.
                             */
                            if (
                                !media.isMovie
                            ) {

                                Log.d(
                                    TAG,
                                    "CB01 SCARTATO per anno: " +
                                        "${candidate.title} " +
                                        "$year != ${media.year}"
                                )

                                return@forEach
                            }

                            score -=
                                150
                        }
                    }

                    Log.d(
                        TAG,
                        "CB01 candidato ${candidate.title} " +
                            "score=$score year=$year"
                    )

                    if (
                        score >
                        bestScore
                    ) {

                        bestScore =
                            score

                        best =
                            candidate to
                                document
                    }

                } catch (
                    e: Exception
                ) {

                    Log.e(
                        TAG,
                        "Errore candidato CB01: ${e.message}"
                    )
                }
            }

        if (
            bestScore <
            30
        ) {

            Log.d(
                TAG,
                "CB01 match troppo debole: $bestScore"
            )

            return null
        }

        Log.d(
            TAG,
            "CB01 SELEZIONATO = ${best?.first?.title} score=$bestScore"
        )

        return best
    }

    // ============================================================
    // PARSER 1x01 / 1x01.1
    // ============================================================

    private fun parseEpisodeNumber(
        text: String
    ): ParsedEpisodeNumber? {

        val value =
            text.lowercase()

        /*
         * Supporta:
         *
         * 1x01
         * 1×01
         * 1x01.1
         * 1x01.2
         */
        val match =
            Regex(
                """(\d{1,2})\s*[x×]\s*(\d{1,3})(?:\.(\d+))?"""
            )
                .find(
                    value
                )

        if (
            match != null
        ) {

            val season =
                match
                    .groupValues[1]
                    .toIntOrNull()

            val episode =
                match
                    .groupValues[2]
                    .toIntOrNull()
                    ?: return null

            val part =
                match
                    .groupValues
                    .getOrNull(3)
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?.toIntOrNull()

            return ParsedEpisodeNumber(
                season =
                    season,

                episode =
                    episode,

                part =
                    part
            )
        }

        /*
         * S01E01 / S01E01.2
         */
        val seMatch =
            Regex(
                """(?i)S(\d{1,2})E(\d{1,3})(?:\.(\d+))?"""
            )
                .find(
                    value
                )

        if (
            seMatch != null
        ) {

            return ParsedEpisodeNumber(
                season =
                    seMatch
                        .groupValues[1]
                        .toIntOrNull(),

                episode =
                    seMatch
                        .groupValues[2]
                        .toIntOrNull()
                        ?: return null,

                part =
                    seMatch
                        .groupValues
                        .getOrNull(3)
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?.toIntOrNull()
            )
        }

        /*
         * Episodio 3
         */
        val episode =
            Regex(
                """(?i)(?:episodio|episode|ep)\s*0*(\d{1,3})"""
            )
                .find(
                    value
                )
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: return null

        return ParsedEpisodeNumber(
            season = null,
            episode = episode,
            part = null
        )
    }

    // ============================================================
    // TITOLO EPISODIO
    // ============================================================

    private fun cleanEpisodeTitle(
        value: String,
        mediaTitle: String?
    ): String {

        var result =
            value

        /*
         * Numerazioni.
         */
        result =
            result.replace(
                Regex(
                    """(?i)\b(?:S\d{1,2}E\d{1,3}|\d{1,2}\s*[x×]\s*\d{1,3})(?:\.\d+)?\b"""
                ),
                " "
            )

        /*
         * Nome serie.
         */
        if (
            !mediaTitle.isNullOrBlank()
        ) {

            result =
                result.replace(
                    mediaTitle,
                    "",
                    ignoreCase = true
                )
        }

        /*
         * Pulsanti e server.
         */
        result =
            result.replace(
                Regex(
                    """(?i)\b(?:MaxStream|Uprot|StayOnline|Scarica|Download|Guarda|Play|Mirror|Server|Streaming)\b"""
                ),
                " "
            )

        return result
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    // ============================================================
    // BLOCCO AGGREGATO
    // ============================================================

    private fun isGenericSeasonTitle(
        value: String
    ): Boolean {

        val title =
            normalize(
                value
            )

        return title.matches(
            Regex(
                """(?:stagione|season)\s*\d+"""
            )
        ) ||
            title.matches(
                Regex("""\d+""")
            )
    }

    private fun blockMatchesMedia(
        media: UniversalMedia,
        blockTitle: String
    ): Boolean {

        val block =
            normalize(
                blockTitle
            )

        val title =
            normalize(
                media.title
            )

        val original =
            normalize(
                media.originalTitle
            )

        if (
            block.isBlank()
        ) {
            return false
        }

        if (
            title.isNotBlank() &&
            block ==
            title
        ) {
            return true
        }

        if (
            original.isNotBlank() &&
            block ==
            original
        ) {
            return true
        }

        if (
            title.isNotBlank() &&
            (
                block.contains(title) ||
                    title.contains(block)
                )
        ) {
            return true
        }

        if (
            original.isNotBlank() &&
            (
                block.contains(original) ||
                    original.contains(block)
                )
        ) {
            return true
        }

        return false
    }

    // ============================================================
    // HOST
    // ============================================================

    private fun extractSupportedLinks(
        element: Element
    ): List<String> {

        return element
            .select(
                "a[href], iframe[src]"
            )
            .mapNotNull { item ->

                val raw =
                    item.attr("href")
                        .ifBlank {
                            item.attr("src")
                        }

                val url =
                    fixUrl(
                        raw
                    )

                if (
                    url.isBlank()
                ) {

                    null

                } else if (
                    supportedHosts.any { host ->
                        url.contains(
                            host,
                            ignoreCase = true
                        )
                    }
                ) {

                    url

                } else {

                    null
                }
            }
            .distinct()
    }

    // ============================================================
    // CARTELLA UPROT
    // ============================================================

    private suspend fun parseUprotFolder(
        folderUrl: String,
        fallbackSeason: Int
    ): List<ProviderEpisode> {

        Log.d(
            TAG,
            "Parsing cartella Uprot: $folderUrl"
        )

        return try {

            val document =
                app.get(
                    folderUrl,
                    headers = headers
                ).document

            val temp =
                mutableListOf<ProviderEpisode>()

            document
                .select("table tr")
                .forEachIndexed { index, row ->

                    val fileName =
                        row.selectFirst("td")
                            ?.text()
                            ?.trim()
                            .orEmpty()

                    if (
                        fileName.isBlank()
                    ) {
                        return@forEachIndexed
                    }

                    val anchor =
                        row.selectFirst(
                            "a[href*='/msfi/'], " +
                                "a[href*='/mse/'], " +
                                "a[href*='/msf/']"
                        )
                            ?: row.selectFirst(
                                "a[href]"
                            )
                            ?: return@forEachIndexed

                    val url =
                        fixUrl(
                            anchor.attr(
                                "abs:href"
                            )
                                .ifBlank {
                                    anchor.attr("href")
                                }
                        )

                    if (
                        url.isBlank()
                    ) {
                        return@forEachIndexed
                    }

                    val parsed =
                        parseEpisodeNumber(
                            fileName
                        )

                    val episode =
                        parsed?.episode
                            ?: (index + 1)

                    val season =
                        parsed?.season
                            ?: fallbackSeason

                    temp.add(
                        ProviderEpisode(
                            source =
                                "CB01",

                            season =
                                season,

                            episode =
                                episode,

                            part =
                                parsed?.part,

                            /*
                             * Sistemato più avanti
                             * dopo l'ordinamento.
                             */
                            absoluteEpisode =
                                0,

                            title =
                                cleanEpisodeTitle(
                                    fileName
                                        .replace(
                                            ".mp4",
                                            "",
                                            ignoreCase = true
                                        )
                                        .replace(
                                            ".mkv",
                                            "",
                                            ignoreCase = true
                                        )
                                        .replace(
                                            ".avi",
                                            "",
                                            ignoreCase = true
                                        )
                                        .replace(
                                            "_",
                                            " "
                                        ),
                                    null
                                ),

                            urls =
                                listOf(
                                    url
                                )
                        )
                    )
                }

            temp
                .sortedWith(
                    compareBy<ProviderEpisode> {
                        it.season ?: 1
                    }
                        .thenBy {
                            it.episode ?: 0
                        }
                        .thenBy {
                            it.part ?: 0
                        }
                )
                .mapIndexed { index, episode ->

                    episode.copy(
                        absoluteEpisode =
                            index + 1
                    )
                }

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Errore cartella Uprot: ${e.message}"
            )

            emptyList()
        }
    }

    // ============================================================
    // PARSING BLOCCO
    // ============================================================

    private suspend fun parseEpisodeBlock(
        media: UniversalMedia,
        wrap: Element,
        fallbackSeason: Int
    ): List<ProviderEpisode> {

        val result =
            mutableListOf<ProviderEpisode>()

        /*
         * Prima cartella /msfld/.
         */
        val folder =
            wrap
                .selectFirst(
                    ".sp-body a[href*='/msfld/'], " +
                        "a[href*='/msfld/']"
                )
                ?.let {
                    fixUrl(
                        it.attr("abs:href")
                            .ifBlank {
                                it.attr("href")
                            }
                    )
                }

        if (
            !folder.isNullOrBlank()
        ) {

            return parseUprotFolder(
                folder,
                fallbackSeason
            )
        }

        wrap
            .select(".sp-body *")
            .forEach rowLoop@{ row ->

                val rowText =
                    row.text()
                        .trim()

                if (
                    rowText.isBlank() ||
                    rowText.contains(
                        "[riduci]",
                        ignoreCase = true
                    )
                ) {
                    return@rowLoop
                }

                /*
                 * Evitiamo la riga "tutta la serie".
                 * Se contiene /msfld/ l'avremmo
                 * già intercettata sopra.
                 */
                if (
                    rowText.contains(
                        Regex(
                            """(?i)TUTTA LA SERIE|TUTTI GLI EPISODI|INTERA STAGIONE|STAGIONE COMPLETA"""
                        )
                    )
                ) {
                    return@rowLoop
                }

                val parsed =
                    parseEpisodeNumber(
                        rowText
                    )
                        ?: return@rowLoop

                val links =
                    extractSupportedLinks(
                        row
                    )

                if (
                    links.isEmpty()
                ) {
                    return@rowLoop
                }

                result.add(
                    ProviderEpisode(
                        source =
                            "CB01",

                        /*
                         * Se la riga contiene la sua
                         * stagione reale usiamo quella.
                         * Altrimenti il blocco.
                         */
                        season =
                            parsed.season
                                ?: fallbackSeason,

                        episode =
                            parsed.episode,

                        part =
                            parsed.part,

                        absoluteEpisode =
                            0,

                        title =
                            cleanEpisodeTitle(
                                rowText,
                                media.title
                            ),

                        urls =
                            links
                    )
                )
            }

        return result
    }

    // ============================================================
    // INVENTARIO COMPLETO
    // ============================================================

    private suspend fun buildProviderEpisodes(
        media: UniversalMedia,
        document: Document
    ): List<ProviderEpisode> {

        val collected =
            mutableListOf<ProviderEpisode>()

        val wraps =
            document.select(
                "div.sp-wrap, div.bb-spoiler"
            )

        if (
            wraps.isNotEmpty()
        ) {

            /*
             * Determiniamo se siamo davanti a
             * una pagina aggregata.
             */
            val blockTitles =
                wraps.map { wrap ->

                    wrap.selectFirst(
                        ".sp-head"
                    )
                        ?.text()
                        ?.trim()
                        .orEmpty()
                }

            val nonGenericBlocks =
                blockTitles
                    .filter {
                        it.isNotBlank() &&
                            !isGenericSeasonTitle(
                                it
                            )
                    }

            val matchingIndices =
                blockTitles
                    .mapIndexedNotNull { index, title ->

                        if (
                            blockMatchesMedia(
                                media,
                                title
                            )
                        ) {
                            index
                        } else {
                            null
                        }
                    }

            /*
             * Se ci sono nomi di serie veri
             * e almeno uno corrisponde,
             * usiamo SOLO quel blocco.
             */
            val indicesToUse =
                when {

                    matchingIndices.isNotEmpty() ->
                        matchingIndices

                    nonGenericBlocks.isNotEmpty() -> {

                        /*
                         * Pagina aggregata ma nessun blocco
                         * corrisponde alla serie TMDB:
                         * meglio non restituire episodi sbagliati.
                         */
                        Log.d(
                            TAG,
                            "CB01 pagina aggregata: " +
                                "nessun blocco compatibile con ${media.title}"
                        )

                        emptyList()
                    }

                    else ->
                        wraps.indices.toList()
                }

            indicesToUse
                .forEachIndexed { cloudIndex, wrapIndex ->

                    val wrap =
                        wraps[
                            wrapIndex
                        ]

                    val heading =
                        blockTitles
                            .getOrNull(
                                wrapIndex
                            )
                            .orEmpty()

                    /*
                     * Stagione:
                     *
                     * - blocco "Stagione 2" -> 2
                     * - pagina aggregata con nome serie -> 1
                     */
                    val seasonFromHeading =
                        Regex(
                            """(?i)(?:stagione|season)\s*(\d+)"""
                        )
                            .find(
                                heading
                            )
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()

                    val fallbackSeason =
                        seasonFromHeading
                            ?: if (
                                matchingIndices.isNotEmpty()
                            ) {
                                1
                            } else {
                                cloudIndex + 1
                            }

                    val blockEpisodes =
                        parseEpisodeBlock(
                            media,
                            wrap,
                            fallbackSeason
                        )

                    collected.addAll(
                        blockEpisodes
                    )
                }
        }

        /*
         * Struttura alternativa season-list.
         */
        document
            .select(
                "div.season-list div.season"
            )
            .forEachIndexed { seasonIndex, block ->

                val seasonTitle =
                    block.selectFirst(
                        "h3"
                    )
                        ?.text()
                        ?.trim()
                        .orEmpty()

                val seasonNumber =
                    Regex(
                        """\d+"""
                    )
                        .find(
                            seasonTitle
                        )
                        ?.value
                        ?.toIntOrNull()
                        ?: (seasonIndex + 1)

                block
                    .select(
                        "ul.episode-list li"
                    )
                    .forEachIndexed episodeLoop@{ epIndex, row ->

                        val text =
                            row.text()
                                .trim()

                        val parsed =
                            parseEpisodeNumber(
                                text
                            )

                        val episode =
                            parsed?.episode
                                ?: (epIndex + 1)

                        val links =
                            extractSupportedLinks(
                                row
                            )

                        if (
                            links.isEmpty()
                        ) {
                            return@episodeLoop
                        }

                        collected.add(
                            ProviderEpisode(
                                source =
                                    "CB01",

                                season =
                                    parsed?.season
                                        ?: seasonNumber,

                                episode =
                                    episode,

                                part =
                                    parsed?.part,

                                absoluteEpisode =
                                    0,

                                title =
                                    cleanEpisodeTitle(
                                        text,
                                        media.title
                                    ),

                                urls =
                                    links
                            )
                        )
                    }
            }

        /*
         * IMPORTANTE:
         *
         * Non facciamo distinct solo su
         * season + episode.
         *
         * 1x01.1 e 1x01.2 devono sopravvivere.
         */
        val merged =
            collected
                .groupBy {
                    Triple(
                        it.season,
                        it.episode,
                        it.part
                    )
                }
                .values
                .map { group ->

                    val first =
                        group.first()

                    first.copy(
                        urls =
                            group
                                .flatMap {
                                    it.urls
                                }
                                .distinct(),

                        title =
                            group
                                .mapNotNull {
                                    it.title
                                }
                                .firstOrNull {
                                    it.isNotBlank()
                                }
                                ?: first.title
                    )
                }
                .sortedWith(
                    compareBy<ProviderEpisode> {
                        it.season ?: 1
                    }
                        .thenBy {
                            it.episode ?: 0
                        }
                        .thenBy {
                            it.part ?: 0
                        }
                )
                .mapIndexed { index, episode ->

                    episode.copy(
                        absoluteEpisode =
                            index + 1
                    )
                }

        Log.d(
            TAG,
            "CB01 ProviderEpisodes = ${merged.size}"
        )

        merged
            .take(40)
            .forEach {

                Log.d(
                    TAG,
                    "CB01 MAP " +
                        "S${it.season}" +
                        "E${it.episode}" +
                        (
                            it.part
                                ?.let { part ->
                                    ".$part"
                                }
                                ?: ""
                            ) +
                        " ABS=${it.absoluteEpisode} " +
                        "TITLE=${it.title}"
                )
            }

        return merged
    }

    // ============================================================
    // INVENTARIO UNIVERSAL
    // ============================================================

    override suspend fun getEpisodeInventory(
        media: UniversalMedia
    ): List<ProviderEpisode> {

        if (
            media.isMovie
        ) {
            return emptyList()
        }

        Log.d(
            TAG,
            "Richiesto inventario CB01: ${media.title}"
        )

        val selected =
            try {

                selectBestCandidate(
                    media
                )

            } catch (
                e: Exception
            ) {

                Log.e(
                    TAG,
                    "Errore inventory CB01: ${e.message}"
                )

                null
            }
                ?: return emptyList()

        return buildProviderEpisodes(
            media,
            selected.second
        )
    }

    // ============================================================
    // STAYONLINE
    // ============================================================

    private suspend fun bypassStayOnline(
        link: String
    ): String? {

        return try {

            val cleanUrl =
                link.substringBefore("?")

            val linkId =
                cleanUrl
                    .removeSuffix("/")
                    .split("/")
                    .lastOrNull {
                        it.isNotBlank()
                    }
                    ?: return null

            val ajaxEndpoint =
                if (
                    link.contains(
                        "/e/"
                    )
                ) {

                    "https://stayonline.pro/ajax/linkEmbedView.php"

                } else {

                    "https://stayonline.pro/ajax/linkView.php"
                }

            val requestHeaders =
                mapOf(
                    "Origin" to
                        "https://stayonline.pro",

                    "Referer" to
                        link,

                    "User-Agent" to
                        USER_AGENT,

                    "X-Requested-With" to
                        "XMLHttpRequest",

                    "Accept" to
                        "application/json, text/javascript, */*; q=0.01",

                    "Content-Type" to
                        "application/x-www-form-urlencoded; charset=UTF-8"
                )

            val pageResponse =
                app.get(
                    link,
                    headers =
                        requestHeaders
                )

            val response =
                app.post(
                    ajaxEndpoint,
                    headers =
                        requestHeaders,
                    cookies =
                        pageResponse.cookies,
                    data =
                        mapOf(
                            "id" to
                                linkId,

                            "ref" to
                                ""
                        )
                ).text

            val json =
                JSONObject(
                    response
                )

            if (
                json.optString(
                    "status"
                ) ==
                "success"
            ) {

                var realUrl =
                    json
                        .getJSONObject(
                            "data"
                        )
                        .getString(
                            "value"
                        )

                if (
                    realUrl.contains(
                        "m1xdrop.net/f/"
                    ) ||
                    realUrl.contains(
                        "mixdrop.co/f/"
                    )
                ) {

                    val id =
                        realUrl
                            .removeSuffix("/")
                            .substringAfterLast("/")

                    realUrl =
                        "https://mixdrop.top/e/$id"
                }

                realUrl

            } else {

                null
            }

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "StayOnline errore: ${e.message}"
            )

            null
        }
    }

    // ============================================================
    // CARICAMENTO PLAYER
    // ============================================================

    private suspend fun loadEpisodeLinks(
        episode: ProviderEpisode,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {

        var linksFound =
            0

        val countedCallback:
            (ExtractorLink) -> Unit = {

            linksFound++

            callback(it)
        }

        val urls =
            episode.urls
                .sortedBy {
                    it.contains(
                        "stayonline.pro",
                        ignoreCase = true
                    )
                }

        urls.forEach { originalUrl ->

            try {

                var playerUrl =
                    originalUrl

                var playerReferer =
                    referer

                if (
                    playerUrl.contains(
                        "stayonline.pro",
                        ignoreCase = true
                    )
                ) {

                    val bypassed =
                        bypassStayOnline(
                            playerUrl
                        )

                    if (
                        bypassed.isNullOrBlank()
                    ) {

                        return@forEach
                    }

                    playerReferer =
                        playerUrl

                    playerUrl =
                        fixUrl(
                            bypassed
                        )
                }

                Log.d(
                    TAG,
                    "CB01 extractor = $playerUrl"
                )

                /*
                 * Non chiamiamo direttamente com.cb.Uprot:
                 * Universal usa gli extractor registrati
                 * nel proprio plugin.
                 */
                loadExtractor(
                    playerUrl,
                    playerReferer,
                    subtitleCallback,
                    countedCallback
                )

                if (
                    linksFound >
                    0
                ) {
                    return linksFound
                }

            } catch (
                e: Exception
            ) {

                Log.e(
                    TAG,
                    "CB01 extractor errore: ${e.message}",
                    e
                )
            }
        }

        return linksFound
    }

    // ============================================================
    // FILM
    // ============================================================

    private suspend fun loadMovieLinks(
        pageUrl: String,
        document: Document,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {

        val links =
            document
                .select(
                    "table a[href], " +
                        "a.buttona_stream[href], " +
                        ".stream-link[href], " +
                        "iframe[src]"
                )
                .mapNotNull { element ->

                    val raw =
                        element
                            .attr("href")
                            .ifBlank {
                                element.attr(
                                    "src"
                                )
                            }

                    val url =
                        fixUrl(
                            raw
                        )

                    if (
                        supportedHosts.any {
                            host ->

                            url.contains(
                                host,
                                ignoreCase = true
                            )
                        }
                    ) {

                        url

                    } else {

                        null
                    }
                }
                .distinct()

        val fakeEpisode =
            ProviderEpisode(
                source =
                    "CB01",

                season =
                    null,

                episode =
                    null,

                part =
                    null,

                absoluteEpisode =
                    1,

                title =
                    null,

                urls =
                    links
            )

        return loadEpisodeLinks(
            fakeEpisode,
            pageUrl,
            subtitleCallback,
            callback
        )
    }

    // ============================================================
    // LOAD LINKS UNIVERSAL
    // ============================================================

    override suspend fun loadLinks(
        media: UniversalMedia,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {

        Log.d(
            TAG,
            "======================================"
        )

        Log.d(
            TAG,
            "UNIVERSAL → CB01"
        )

        Log.d(
            TAG,
            "Titolo = ${media.title}"
        )

        Log.d(
            TAG,
            "Originale = ${media.originalTitle}"
        )

        Log.d(
            TAG,
            "Anno = ${media.year}"
        )

        Log.d(
            TAG,
            "IMDb = ${media.imdbId}"
        )

        if (
            !media.isMovie
        ) {

            Log.d(
                TAG,
                "TMDB S${media.season}E${media.episode} " +
                    "ABS=${media.absoluteEpisode}"
            )
        }

        val selected =
            try {

                selectBestCandidate(
                    media
                )

            } catch (
                e: Exception
            ) {

                Log.e(
                    TAG,
                    "Errore ricerca CB01: ${e.message}",
                    e
                )

                null
            }
                ?: return 0

        val candidate =
            selected.first

        val document =
            selected.second

        if (
            media.isMovie
        ) {

            return loadMovieLinks(
                candidate.url,
                document,
                subtitleCallback,
                callback
            )
        }

        val providerEpisodes =
            buildProviderEpisodes(
                media,
                document
            )

        if (
            providerEpisodes.isEmpty()
        ) {

            Log.d(
                TAG,
                "CB01 nessun episodio compatibile"
            )

            return 0
        }

        val episode =
            EpisodeMapper.findBest(
                media,
                providerEpisodes
            )
                ?: run {

                    Log.d(
                        TAG,
                        "CB01 nessun mapping per " +
                            "S${media.season}E${media.episode}"
                    )

                    return 0
                }

        Log.d(
            TAG,
            "CB01 MAPPATO: " +
                "TMDB S${media.season}E${media.episode} " +
                "→ " +
                "CB01 S${episode.season}" +
                "E${episode.episode}" +
                (
                    episode.part
                        ?.let {
                            ".$it"
                        }
                        ?: ""
                    )
        )

        return loadEpisodeLinks(
            episode,
            candidate.url,
            subtitleCallback,
            callback
        )
    }
}
