package com.universal.sources

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.universal.models.UniversalMedia
import com.universal.models.ProviderEpisode
import com.universal.utils.EpisodeMapper
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.Normalizer

class OnlineSerieTvSource : SourceAdapter {

    override val name =
        "OnlineSerieTV"

    /*
     * Può richiedere Uprot CAPTCHA.
     *
     * Universal lo proverà SOLO
     * se Altadefinizione01 non ha trovato nulla.
     */
    override val requiresInteraction =
        true

    override val priority =
        20

    companion object {

        private const val TAG =
            "UNIVERSAL_OSTV"

        private const val MAIN_URL =
            "https://onlineserietv.mom"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/139.0.0.0 Mobile Safari/537.36"
    }

    private val headers =
        mapOf(
            "User-Agent" to
                USER_AGENT,

            "Referer" to
                "$MAIN_URL/"
        )

    // ============================================================
    // MODEL
    // ============================================================

    private data class Candidate(
        val title: String,
        val url: String,
        val isTv: Boolean,
        val year: Int? = null,
        val score: Int
    )

    // ============================================================
    // URL
    // ============================================================

    private fun fixUrl(
        url: String
    ): String {

        val value =
            url.trim()

        return when {

            value.startsWith(
                "http",
                ignoreCase = true
            ) ->
                value

            value.startsWith("//") ->
                "https:$value"

            value.startsWith("/") ->
                MAIN_URL.trimEnd('/') +
                    value

            value.isBlank() ->
                ""

            else ->
                "$MAIN_URL/$value"
        }
    }

    // ============================================================
    // TITLE CLEANING
    // ============================================================

    private fun fixApostrophes(
        title: String
    ): String {

        return title
            .replace(
                "\\bl\\s+uomo"
                    .toRegex(
                        RegexOption.IGNORE_CASE
                    ),
                "l'uomo"
            )
            .replace(
                "\\bl\\s+amore"
                    .toRegex(
                        RegexOption.IGNORE_CASE
                    ),
                "l'amore"
            )
            .replace(
                "\\bl\\s+ombra"
                    .toRegex(
                        RegexOption.IGNORE_CASE
                    ),
                "l'ombra"
            )
            .replace(
                "\\bd\\s+amore"
                    .toRegex(
                        RegexOption.IGNORE_CASE
                    ),
                "d'amore"
            )
            .replace(
                "\\bd\\s+oro"
                    .toRegex(
                        RegexOption.IGNORE_CASE
                    ),
                "d'oro"
            )
            .replace(
                "\\bd\\s+acciaio"
                    .toRegex(
                        RegexOption.IGNORE_CASE
                    ),
                "d'acciaio"
            )
    }

    private fun fixSpecialCases(
        title: String
    ): String {

        return title
            .replace(
                "(?i)pokemon".toRegex(),
                "Pokémon"
            )
            .replace(
                "(?i)pokèmon".toRegex(),
                "Pokémon"
            )
            .replace(
                "(?i)poke mon".toRegex(),
                "Pokémon"
            )
    }

    private fun cleanTitle(
        title: String
    ): String {

        var cleaned =
            title
                .replace(
                    "(?i)\\bSUB\\s*[- ]?\\s*ITA\\b"
                        .toRegex(),
                    ""
                )
                .replace(
                    "(?i)\\bSUBITA\\b"
                        .toRegex(),
                    ""
                )
                .replace(
                    "(?i)\\bSUB-ITA\\b"
                        .toRegex(),
                    ""
                )
                .replace(
                    "(?i)\\bSUB IT\\b"
                        .toRegex(),
                    ""
                )
                .replace(
                    "(?i)\\bSUB-IT\\b"
                        .toRegex(),
                    ""
                )
                .replace(
                    " in streaming - OnlineSerieTv",
                    ""
                )
                .replace(
                    "(?i)\\b(ITA|STAGIONE \\d+|STAGIONE)\\b"
                        .toRegex(),
                    ""
                )
                .replace(
                    "(?i)serie animata"
                        .toRegex(),
                    ""
                )
                .replace(
                    """\s*[\( \[\-]?\s*(19|20)\d{2}\s*[\)\] \-]?\s*"""
                        .toRegex(),
                    " "
                )
                .replace(
                    Regex("\\s+"),
                    " "
                )
                .trim()

        cleaned =
            fixApostrophes(
                cleaned
            )

        cleaned =
            fixSpecialCases(
                cleaned
            )

        return cleaned
            .trim()
    }

    private fun normalizeTitle(
        value: String?
    ): String {

        if (
            value.isNullOrBlank()
        ) {
            return ""
        }

        val cleaned =
            cleanTitle(
                value
            )

        return Normalizer
            .normalize(
                cleaned,
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

    // ============================================================
    // SCORE
    // ============================================================

    private fun calculateScore(
        media: UniversalMedia,
        candidateTitle: String,
        candidateIsMovie: Boolean
    ): Int {

        val candidate =
            normalizeTitle(
                candidateTitle
            )

        val title =
            normalizeTitle(
                media.title
            )

        val original =
            normalizeTitle(
                media.originalTitle
            )

        if (
            candidate.isBlank()
        ) {
            return 0
        }

        var score =
            0

        if (
            candidate ==
            title
        ) {
            score +=
                100
        }

        if (
            original.isNotBlank() &&
            candidate ==
            original
        ) {
            score +=
                90
        }

        if (
            title.isNotBlank() &&
            (
                candidate.contains(
                    title
                ) ||
                    title.contains(
                        candidate
                    )
                )
        ) {
            score +=
                40
        }

        if (
            original.isNotBlank() &&
            (
                candidate.contains(
                    original
                ) ||
                    original.contains(
                        candidate
                    )
                )
        ) {
            score +=
                35
        }

        /*
         * Tipo corretto.
         */
        if (
            media.isMovie ==
            candidateIsMovie
        ) {

            score +=
                30

        } else {

            score -=
                50
        }

        /*
         * Matching per parole.
         */
        val words =
            title
                .split(" ")
                .filter {
                    it.length >=
                        3
                }

        score +=
            words.count {
                candidate.contains(
                    it
                )
            } * 5

        return score
    }

    // ============================================================
    // SEARCH RESULT
    // ============================================================

    private fun parseCandidate(
        element: Element,
        media: UniversalMedia
    ): Candidate? {

        val titleElement =
            element.selectFirst(
                "h2"
            )
                ?: element.selectFirst(
                    ".uagb-post__title a"
                )
                ?: return null

        val rawTitle =
            titleElement.text()
                .trim()

        val title =
            cleanTitle(
                rawTitle
            )

        val link =
            element.selectFirst(
                ".imagen a"
            )
                ?: element.selectFirst(
                    ".uagb-post__title a"
                )
                ?: element.selectFirst(
                    "a[href]"
                )
                ?: return null

        val url =
            fixUrl(
                link.attr(
                    "href"
                )
            )

        if (
            title.isBlank() ||
            url.isBlank()
        ) {
            return null
        }

        val isMovie =
            url.contains(
                "/film/",
                ignoreCase = true
            ) ||
                url.contains(
                    "/movies/",
                    ignoreCase = true
                )

        val score =
            calculateScore(
                media,
                title,
                isMovie
            )

        return Candidate(
            title = title,
            url = url,
            isTv = !isMovie,
            score = score
        )
    }

    // ============================================================
    // SEARCH
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

        val results =
            mutableListOf<Candidate>()

        for (
            query in queries
        ) {

            val encoded =
                URLEncoder.encode(
                    query,
                    "UTF-8"
                )

            /*
             * Provider originale:
             * fino a 5 pagine.
             *
             * Universal si ferma prima
             * se trova un match molto forte.
             */
            for (
                page in 1..5
            ) {

                val searchUrl =
                    if (
                        page ==
                        1
                    ) {

                        "$MAIN_URL/?s=$encoded"

                    } else {

                        "$MAIN_URL/page/$page/?s=$encoded"
                    }

                Log.d(
                    TAG,
                    "SEARCH [$query] PAGE $page"
                )

                val response =
                    try {

                        app.get(
                            searchUrl,
                            headers =
                                headers
                        )

                    } catch (
                        e: Exception
                    ) {

                        Log.e(
                            TAG,
                            "SEARCH errore: ${e.message}"
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

                val pageResults =
                    mutableListOf<Candidate>()

                document
                    .select(
                        ".movie"
                    )
                    .mapNotNull {
                        parseCandidate(
                            it,
                            media
                        )
                    }
                    .forEach {
                        pageResults.add(
                            it
                        )
                    }

                document
                    .select(
                        ".uagb-post__inner-wrap"
                    )
                    .mapNotNull {
                        parseCandidate(
                            it,
                            media
                        )
                    }
                    .forEach {
                        pageResults.add(
                            it
                        )
                    }

                results.addAll(
                    pageResults
                )

                /*
                 * Match praticamente esatto.
                 */
                if (
                    results.any {
                        it.score >=
                            120
                    }
                ) {

                    break
                }

                if (
                    pageResults.isEmpty()
                ) {

                    break
                }
            }
        }

        return results
            .distinctBy {
                it.url
            }
            .sortedByDescending {
                it.score
            }
    }

    // ============================================================
    // YEAR
    // ============================================================

    private fun findYear(
        document: Document
    ): Int? {

        val text =
            document
                .select(
                    "span:contains(Anno:), " +
                        "span:contains(Anno)"
                )
                .text()

        return Regex(
            """(19|20)\d{2}"""
        )
            .find(
                text
            )
            ?.value
            ?.toIntOrNull()
    }

    // ============================================================
    // BEST RESULT
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
                "Nessun risultato OSTV"
            )

            return null
        }

        var best:
            Pair<Candidate, Document>? =
            null

        var bestScore =
            Int.MIN_VALUE

        candidates
            .take(
                6
            )
            .forEach { candidate ->

                try {

                    val document =
                        app.get(
                            candidate.url,
                            headers =
                                headers
                        ).document

                    var score =
                        candidate.score

                    val candidateYear =
                        findYear(
                            document
                        )

                    if (
                        media.year != null &&
                        candidateYear != null
                    ) {
                    
                        val yearDifference =
                            kotlin.math.abs(
                                media.year - candidateYear
                            )
                    
                        if (yearDifference <= 1) {
                    
                            score += 120
                    
                        } else {
                    
                            /*
                             * Serie con stesso titolo ma anno incompatibile:
                             * è molto probabilmente un'altra serie.
                             */
                            if (!media.isMovie) {
                    
                                Log.d(
                                    TAG,
                                    "SCARTATO per anno incompatibile: " +
                                        "${candidate.title} " +
                                        "$candidateYear != ${media.year}"
                                )
                    
                                return@forEach
                            }
                    
                            // Per i film restiamo più permissivi.
                            score -= 150
                        }
                    }

                    Log.d(
                        TAG,
                        "CANDIDATO score=$score " +
                            "title=${candidate.title} " +
                            "year=$candidateYear"
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
                        "Errore candidato: ${e.message}"
                    )
                }
            }

        if (
            bestScore <
            20
        ) {

            Log.d(
                TAG,
                "Match OSTV troppo debole: $bestScore"
            )

            return null
        }

        Log.d(
            TAG,
            "OSTV SELEZIONATO = ${best?.first?.title} score=$bestScore"
        )

        return best
    }

    // ============================================================
    // PARSING EPISODE
    // ============================================================
            private data class ParsedEpisodeNumber(
                val season: Int?,
                val episode: Int,
                val part: Int? = null
            )
            
            private fun parseProviderEpisodeNumber(
                text: String
            ): ParsedEpisodeNumber? {
            
                val value =
                    text.lowercase()
            
                val match =
                    Regex(
                        """(\d{1,2})x(\d{1,3})(?:\.(\d+))?"""
                    )
                        .find(value)
                        ?: return null
            
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
                    season = season,
                    episode = episode,
                    part = part
                )
            }
            
            private fun cleanProviderEpisodeTitle(
                text: String,
                mediaTitle: String? = null
            ): String {
            
                var result =
                    text
            
                /*
                 * Toglie numerazione:
                 * 01x01
                 * 1x01.1
                 * ecc.
                 */
                result =
                    result.replace(
                        Regex(
                            """\b\d{1,2}x\d{1,3}(?:\.\d+)?\b""",
                            RegexOption.IGNORE_CASE
                        ),
                        " "
                    )
            
                /*
                 * Toglie parole dei pulsanti/server.
                 */
                result =
                    result.replace(
                        Regex(
                            """\b(?:MaxStream|Uprot|Scarica|Download|Guarda|Play|Mirror|Server)\b""",
                            RegexOption.IGNORE_CASE
                        ),
                        " "
                    )
            
                /*
                 * Se la riga contiene anche il nome della serie,
                 * proviamo a rimuoverlo.
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
            
                return result
                    .replace(
                        Regex("""\s+"""),
                        " "
                    )
                    .trim()
            }

            private fun buildProviderEpisodes(
                document: Document,
                mediaTitle: String? = null
            ): List<ProviderEpisode> {
            
                val result =
                    mutableListOf<ProviderEpisode>()
            
                val rows =
                    document
                        .select("table tr")
                        .toList()
            
                var absolute =
                    0
            
                rows.forEach { row ->
            
                    val links =
                        row
                            .select("a[href]")
                            .mapNotNull { element ->
            
                                val href =
                                    element
                                        .attr("href")
                                        .trim()
            
                                if (href.isBlank()) {
                                    null
                                } else {
                                    fixUrl(href)
                                }
                            }
                            .filter {
                                it.isNotBlank() &&
                                    (
                                        it.contains(
                                            "uprot.net",
                                            ignoreCase = true
                                        ) ||
                                        it.contains(
                                            "maxstream.video",
                                            ignoreCase = true
                                        )
                                    )
                            }
                            .distinct()
            
                    if (links.isEmpty()) {
                        return@forEach
                    }
            
                    val rawText =
                        row.text()
                            .trim()
            
                    val parsed =
                        parseProviderEpisodeNumber(
                            rawText
                        )
            
                    /*
                     * Fallback per righe tipo:
                     * Episodio 5
                     */
                    val fallbackEpisode =
                        if (parsed == null) {
                            parseEpisodeNumber(
                                rawText
                            )
                        } else {
                            null
                        }
            
                    if (
                        parsed == null &&
                        fallbackEpisode == null
                    ) {
                        return@forEach
                    }
            
                    absolute++
            
                    val cleanedTitle =
                        cleanProviderEpisodeTitle(
                            rawText,
                            mediaTitle
                        )
            
                    result.add(
                        ProviderEpisode(
                            source =
                                "OnlineSerieTV",
            
                            season =
                                parsed?.season,
            
                            episode =
                                parsed?.episode
                                    ?: fallbackEpisode,
            
                            part =
                                parsed?.part,
            
                            absoluteEpisode =
                                absolute,
            
                            title =
                                cleanedTitle,
            
                            urls =
                                links
                        )
                    )
                }
            
                Log.d(
                    TAG,
                    "OSTV ProviderEpisodes = ${result.size}"
                )
            
                result
                    .take(30)
                    .forEach {
            
                        Log.d(
                            TAG,
                            "OSTV MAP " +
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
            
                return result
            }

        override suspend fun getEpisodeInventory(
            media: UniversalMedia
        ): List<ProviderEpisode> {
        
            if (media.isMovie) {
                return emptyList()
            }
        
            Log.d(
                TAG,
                "Richiesto inventario episodi OSTV: ${media.title}"
            )
        
            val selected =
                try {
        
                    selectBestCandidate(
                        media
                    )
        
                } catch (e: Exception) {
        
                    Log.e(
                        TAG,
                        "Errore inventario OSTV: ${e.message}"
                    )
        
                    null
                }
                    ?: return emptyList()
        
            val document =
                selected.second
        
            return buildProviderEpisodes(
                document,
                media.title
            )
        }
            

    private fun parseSeasonAndEpisode(
        text: String
    ): Pair<Int, Int>? {

        val match =
            Regex(
                """(\d{1,2})x(\d{1,3})"""
            )
                .find(
                    text.lowercase()
                )
                ?: return null

        val season =
            match
                .groupValues[
                    1
                ]
                .toIntOrNull()
                ?: return null

        val episode =
            match
                .groupValues[
                    2
                ]
                .toIntOrNull()
                ?: return null

        return season to
            episode
    }

    private fun parseEpisodeNumber(
        text: String
    ): Int? {

        val value =
            text.lowercase()

        Regex(
            """(\d{1,2})x(\d{1,3})"""
        )
            .find(
                value
            )
            ?.let {

                return it
                    .groupValues[
                        2
                    ]
                    .toIntOrNull()
            }

        Regex(
            """(?:episodio|ep\.?|episode|capitolo|parte)\s*(\d{1,3})"""
        )
            .find(
                value
            )
            ?.let {

                return it
                    .groupValues[
                        1
                    ]
                    .toIntOrNull()
            }

        return null
    }

    // ============================================================
    // FILM
    // ============================================================

    private suspend fun loadMovieLinks(
        pageUrl: String,
        document: Document,
        subtitleCallback:
            (SubtitleFile) -> Unit,
        callback:
            (ExtractorLink) -> Unit
    ): Int {

        val playerUrls =
            mutableSetOf<String>()

        /*
         * Link normali.
         */
        document
            .select(
                "a[href]"
            )
            .forEach { element ->

                val href =
                    element.attr(
                        "href"
                    )
                        .trim()

                if (
                    href.isBlank()
                ) {
                    return@forEach
                }

                val fixed =
                    fixUrl(
                        href
                    )

                if (
                    fixed.contains(
                        "uprot.net",
                        ignoreCase = true
                    ) ||
                    fixed.contains(
                        "maxstream.video",
                        ignoreCase = true
                    )
                ) {

                    playerUrls.add(
                        fixed
                    )
                }
            }

        /*
         * iframe.
         */
        document
            .select(
                "iframe[src]"
            )
            .forEach { iframe ->

                val src =
                    iframe.attr(
                        "src"
                    )
                        .trim()

                if (
                    src.isBlank()
                ) {
                    return@forEach
                }

                val fixed =
                    fixUrl(
                        src
                    )

                if (
                    fixed.contains(
                        "uprot.net",
                        ignoreCase = true
                    ) ||
                    fixed.contains(
                        "maxstream.video",
                        ignoreCase = true
                    )
                ) {

                    playerUrls.add(
                        fixed
                    )
                }
            }

        /*
         * attributi data-*.
         */
        document
            .select(
                "[data-link], " +
                    "[data-url], " +
                    "[data-src]"
            )
            .forEach { element ->

                listOf(
                    element.attr(
                        "data-link"
                    ),
                    element.attr(
                        "data-url"
                    ),
                    element.attr(
                        "data-src"
                    )
                )
                    .forEach { value ->

                        if (
                            value.isBlank()
                        ) {

                            return@forEach
                        }

                        val fixed =
                            fixUrl(
                                value
                            )

                        if (
                            fixed.contains(
                                "uprot.net",
                                ignoreCase = true
                            ) ||
                            fixed.contains(
                                "maxstream.video",
                                ignoreCase = true
                            )
                        ) {

                            playerUrls.add(
                                fixed
                            )
                        }
                    }
            }
            
            Log.d(
            TAG,
            "OSTV FILM URL = $pageUrl"
        )
        
        Log.d(
            TAG,
            "OSTV FILM TITLE = ${document.title()}"
        )
        
        Log.d(
            TAG,
            "OSTV FILM HTML LENGTH = ${document.html().length}"
        )

        Log.d(
            TAG,
            "OSTV FILM player = ${playerUrls.size}"
        )
        
        playerUrls.forEach {
        Log.d(
            TAG,
            "OSTV FILM PLAYER = $it"
        )
    }

        var linksFound =
            0

        val countedCallback:
            (ExtractorLink) -> Unit = {

            linksFound++

            callback(it)
        }

        for (
            playerUrl in playerUrls
        ) {

            try {

                Log.d(
                    TAG,
                    "FILM extractor = $playerUrl"
                )

                loadExtractor(
                    playerUrl,
                    pageUrl,
                    subtitleCallback,
                    countedCallback
                )

            } catch (
                e: Exception
            ) {

                Log.e(
                    TAG,
                    "FILM extractor errore: ${e.message}"
                )
            }
        }

        return linksFound
    }

    // ============================================================
    // SERIE TV
    // ============================================================

    private suspend fun loadTvLinks(
        media: UniversalMedia,
        showUrl: String,
        document: Document,
        subtitleCallback:
            (SubtitleFile) -> Unit,
        callback:
            (ExtractorLink) -> Unit
    ): Int {
    
       val providerEpisodes =
            buildProviderEpisodes(
                document,
                media.title
            )
    
        if (providerEpisodes.isEmpty()) {
    
            Log.d(
                TAG,
                "OSTV: nessun episodio indicizzato"
            )
    
            return 0
        }
    
        val selected =
            EpisodeMapper.findBest(
                media,
                providerEpisodes
            )
                ?: run {
    
                    Log.d(
                        TAG,
                        "OSTV: nessuna corrispondenza per " +
                            "TMDB S${media.season}E${media.episode} " +
                            "ABS=${media.absoluteEpisode}"
                    )
    
                    return 0
                }
    
        Log.d(
            TAG,
            "OSTV EPISODIO MAPPATO: " +
                "TMDB S${media.season}E${media.episode} " +
                "ABS=${media.absoluteEpisode} " +
                "→ " +
                "Provider S${selected.season}E${selected.episode} " +
                "ABS=${selected.absoluteEpisode}"
        )
    
        if (selected.urls.isEmpty()) {
    
            Log.d(
                TAG,
                "OSTV episodio mappato senza player"
            )
    
            return 0
        }
    
        var linksFound =
            0
    
        val countedCallback:
            (ExtractorLink) -> Unit = {
    
            linksFound++
    
            callback(it)
        }
    
        /*
         * Proviamo i player dell'episodio scelto.
         *
         * Appena uno produce un video ci fermiamo,
         * evitando CAPTCHA/interazioni aggiuntive.
         */
        for (playerUrl in selected.urls) {
    
            try {
    
                Log.d(
                    TAG,
                    "OSTV MAPPED extractor = $playerUrl"
                )
    
                loadExtractor(
                    playerUrl,
                    showUrl,
                    subtitleCallback,
                    countedCallback
                )
    
                if (linksFound > 0) {
                    break
                }
    
            } catch (e: Exception) {
    
                Log.e(
                    TAG,
                    "OSTV mapped extractor errore: ${e.message}",
                    e
                )
            }
        }
    
        return linksFound
    }
    // ============================================================
    // UNIVERSAL ENTRY
    // ============================================================

    override suspend fun loadLinks(
        media: UniversalMedia,
        subtitleCallback:
            (SubtitleFile) -> Unit,
        callback:
            (ExtractorLink) -> Unit
    ): Int {

        Log.d(
            TAG,
            "======================================"
        )

        Log.d(
            TAG,
            "UNIVERSAL → ONLINESERIETV"
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
                "S${media.season}E${media.episode} " +
                    "absolute=${media.absoluteEpisode}"
            )
        }

        /*
         * Trova la pagina corretta.
         */
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
                    "Errore ricerca OSTV: ${e.message}",
                    e
                )

                null
            }
                ?: return 0

        val candidate =
            selected.first

        val document =
            selected.second

        /*
         * Film oppure serie.
         */
        val links =
            if (
                media.isMovie
            ) {

                loadMovieLinks(
                    pageUrl =
                        candidate.url,

                    document =
                        document,

                    subtitleCallback =
                        subtitleCallback,

                    callback =
                        callback
                )

            } else {

                loadTvLinks(
                    media =
                        media,

                    showUrl =
                        candidate.url,

                    document =
                        document,

                    subtitleCallback =
                        subtitleCallback,

                    callback =
                        callback
                )
            }

        Log.d(
            TAG,
            "ONLINESERIETV LINKS = $links"
        )

        return links
    }
}
