package com.universal.sources

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.universal.models.UniversalMedia
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.Normalizer

class Altadefinizione01Source : SourceAdapter {

    override val name =
        "Altadefinizione01"

    /*
     * Altadefinizione01 non richiede CAPTCHA.
     *
     * Quindi Universal lo proverà PRIMA
     * di OnlineSerieTV e CB01.
     */
    override val requiresInteraction =
        false

    override val priority =
        10

    companion object {

        private const val TAG =
            "UNIVERSAL_AD01"

        private const val MAIN_URL =
            "https://altadefinizione-01.fun"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"
    }

    private val headers =
        mapOf(
            "User-Agent" to USER_AGENT
        )

    // ============================================================
    // MODELLO INTERNO RISULTATO
    // ============================================================

    private data class Candidate(
        val title: String,
        val url: String,
        val isTv: Boolean,
        val score: Int
    )

    // ============================================================
    // URL
    // ============================================================

    private fun normalizeUrl(
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
    // NORMALIZZAZIONE TITOLO
    // ============================================================

    private fun normalizeTitle(
        value: String?
    ): String {

        if (
            value.isNullOrBlank()
        ) {
            return ""
        }

        val normalized =
            Normalizer.normalize(
                value,
                Normalizer.Form.NFD
            )

        return normalized
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
                Regex(
                    "\\s+"
                ),
                " "
            )
            .trim()
    }

    // ============================================================
    // SCORE TITOLO
    // ============================================================

    private fun calculateTitleScore(
        media: UniversalMedia,
        candidateTitle: String
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

        /*
         * Titolo italiano esatto.
         */
        if (
            title.isNotBlank() &&
            candidate == title
        ) {

            score +=
                100
        }

        /*
         * Titolo originale esatto.
         */
        if (
            original.isNotBlank() &&
            candidate == original
        ) {

            score +=
                90
        }

        /*
         * Uno contiene l'altro.
         *
         * Utile per:
         *
         * Titolo
         * Titolo Streaming
         * Titolo Serie TV
         */
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
         * Matching parole.
         */
        val titleWords =
            title
                .split(" ")
                .filter {
                    it.length >= 3
                }

        if (
            titleWords.isNotEmpty()
        ) {

            val matches =
                titleWords.count {
                    candidate.contains(
                        it
                    )
                }

            score +=
                matches * 5
        }

        return score
    }

    // ============================================================
    // CARD
    // ============================================================

    private fun parseCandidate(
        element: Element,
        media: UniversalMedia
    ): Candidate? {

        val anchor =
            element.selectFirst(
                ".cover.boxcaption h2 a, " +
                    "h3 a, " +
                    ".boxcaption h2 a"
            )
                ?: return null

        val title =
            anchor.text()
                .trim()

        val url =
            normalizeUrl(
                anchor.attr(
                    "href"
                )
            )

        if (
            title.isBlank() ||
            url.isBlank()
        ) {

            return null
        }

        val isTv =
            element.selectFirst(
                ".se_num"
            ) != null ||
                element.selectFirst(
                    ".ml-cat a[href*='/serie-tv/']"
                ) != null ||
                url.contains(
                    "/serie-tv/",
                    ignoreCase = true
                )

        /*
         * Un film non deve preferire
         * una pagina serie e viceversa.
         */
        var score =
            calculateTitleScore(
                media,
                title
            )

        if (
            media.isMovie &&
            !isTv
        ) {

            score +=
                30
        }

        if (
            !media.isMovie &&
            isTv
        ) {

            score +=
                30
        }

        if (
            media.isMovie &&
            isTv
        ) {

            score -=
                50
        }

        if (
            !media.isMovie &&
            !isTv
        ) {

            score -=
                50
        }

        return Candidate(
            title =
                title,

            url =
                url,

            isTv =
                isTv,

            score =
                score
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

        queries.forEach { query ->

            val encoded =
                URLEncoder.encode(
                    query,
                    "UTF-8"
                )

            /*
             * Stessa ricerca del provider originale.
             *
             * Limitiamo normalmente a 3 pagine:
             * se troviamo un candidato molto forte
             * possiamo fermarci prima.
             */
            for (
                page in 1..3
            ) {

                val url =
                    if (
                        page == 1
                    ) {

                        "$MAIN_URL/index.php" +
                            "?do=search" +
                            "&subaction=search" +
                            "&titleonly=3" +
                            "&story=$encoded" +
                            "&full_search=0"

                    } else {

                        val resultFrom =
                            (page - 1) *
                                50 +
                                1

                        "$MAIN_URL/index.php" +
                            "?do=search" +
                            "&subaction=search" +
                            "&titleonly=3" +
                            "&full_search=0" +
                            "&search_start=$page" +
                            "&result_from=$resultFrom" +
                            "&story=$encoded"
                    }

                Log.d(
                    TAG,
                    "SEARCH [$query] PAGE $page = $url"
                )

                val response =
                    try {

                        app.get(
                            url,
                            headers =
                                headers
                        )

                    } catch (
                        e: Exception
                    ) {

                        Log.e(
                            TAG,
                            "Errore ricerca: ${e.message}"
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
                    document
                        .select(
                            "#dle-content " +
                                ".boxgrid.caption"
                        )
                        .mapNotNull {
                            parseCandidate(
                                it,
                                media
                            )
                        }

                results.addAll(
                    pageResults
                )

                /*
                 * Se abbiamo già un risultato
                 * praticamente esatto,
                 * inutile fare altre pagine.
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

                if (
                    page == 1 &&
                    document.selectFirst(
                        "div.page_nav"
                    ) == null
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
    // ANNO PAGINA
    // ============================================================

    private fun extractYear(
        document: Document
    ): Int? {

        return document
            .select(
                "p.meta_dd:has(b.icon-clock)"
            )
            .text()
            .let {

                Regex(
                    """(19|20)\d{2}"""
                )
                    .find(it)
                    ?.value
                    ?.toIntOrNull()
            }
    }

    // ============================================================
    // IMDB VIDXGO
    // ============================================================

    private fun findVidxGoImdb(
        document: Document
    ): String? {

        document
            .select(
                "script"
            )
            .forEach { script ->

                val content =
                    script.data()
                        .ifBlank {
                            script.html()
                        }

                val match =
                    Regex(
                        """var\s+imdb\s*=\s*['"]tt(\d+)['"]"""
                    )
                        .find(
                            content
                        )

                if (
                    match != null
                ) {

                    return match
                        .groupValues[
                            1
                        ]
                }
            }

        return null
    }

    // ============================================================
    // CONTROLLO CANDIDATO
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
                "Nessun candidato AD01"
            )

            return null
        }

        Log.d(
            TAG,
            "Candidati trovati = ${candidates.size}"
        )

        candidates
            .take(
                10
            )
            .forEach {

                Log.d(
                    TAG,
                    "CANDIDATO score=${it.score} " +
                        "tv=${it.isTv} " +
                        "title=${it.title} " +
                        "url=${it.url}"
                )
            }

        /*
         * Controlliamo i primi risultati
         * per usare anche l'anno.
         */
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
                        extractYear(
                            document
                        )

                    if (
                        media.year != null &&
                        candidateYear != null
                    ) {

                        /*
                         * Nei film l'anno è molto utile.
                         *
                         * Per le serie TMDB dà l'anno
                         * della prima trasmissione.
                         */
                        if (
                            media.year ==
                            candidateYear
                        ) {

                            score +=
                                30

                        } else {

                            score -=
                                10
                        }
                    }

                    /*
                     * Se abbiamo IMDb,
                     * è il matching più forte.
                     */
                    val pageImdbNumeric =
                        findVidxGoImdb(
                            document
                        )

                    val pageImdb =
                        pageImdbNumeric
                            ?.let {
                                "tt$it"
                            }

                    if (
                        !media.imdbId.isNullOrBlank() &&
                        !pageImdb.isNullOrBlank()
                    ) {

                        if (
                            media.imdbId.equals(
                                pageImdb,
                                ignoreCase = true
                            )
                        ) {

                            score +=
                                200

                        } else {

                            /*
                             * IMDb diverso:
                             * probabilmente non è
                             * il contenuto corretto.
                             */
                            score -=
                                100
                        }
                    }

                    Log.d(
                        TAG,
                        "CHECK ${candidate.title} " +
                            "score=$score " +
                            "year=$candidateYear " +
                            "imdb=$pageImdb"
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
                        "Errore candidato ${candidate.url}: ${e.message}"
                    )
                }
            }

        /*
         * Una soglia minima evita
         * risultati palesemente sbagliati.
         */
        if (
            bestScore <
            40
        ) {

            Log.d(
                TAG,
                "Miglior candidato troppo debole: $bestScore"
            )

            return null
        }

        Log.d(
            TAG,
            "MIGLIOR CANDIDATO = " +
                "${best?.first?.title} " +
                "score=$bestScore"
        )

        return best
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

        var linksFound =
            0

        val countedCallback:
            (ExtractorLink) -> Unit = {

            linksFound++

            callback(it)
        }

        /*
         * ========================================================
         * GUARDAHD
         * ========================================================
         */

        val guardahd =
            document.selectFirst(
                "iframe[src*='guardahd.stream']"
            )

        if (
            guardahd != null
        ) {

            val iframeUrl =
                normalizeUrl(
                    guardahd.attr(
                        "src"
                    )
                )

            if (
                iframeUrl.isNotBlank()
            ) {

                Log.d(
                    TAG,
                    "MOVIE GUARDAHD = $iframeUrl"
                )

                try {

                    val iframeDocument =
                        app.get(
                            iframeUrl,
                            headers =
                                mapOf(
                                    "User-Agent" to
                                        USER_AGENT,

                                    "Referer" to
                                        pageUrl
                                )
                        ).document

                    iframeDocument
                        .select(
                            "ul._player-mirrors " +
                                "li[data-link]"
                        )
                        .filterNot {

                            it.hasClass(
                                "fullhd"
                            ) ||
                                it.text()
                                    .contains(
                                        "4K",
                                        ignoreCase =
                                            true
                                    )
                        }
                        .forEach { mirror ->

                            var link =
                                mirror
                                    .attr(
                                        "data-link"
                                    )
                                    .trim()

                            if (
                                link.isBlank()
                            ) {

                                return@forEach
                            }

                            if (
                                link.startsWith(
                                    "//"
                                )
                            ) {

                                link =
                                    "https:$link"
                            }

                            Log.d(
                                TAG,
                                "MOVIE SERVER = $link"
                            )

                            try {

                                loadExtractor(
                                    link,
                                    iframeUrl,
                                    subtitleCallback,
                                    countedCallback
                                )

                            } catch (
                                e: Exception
                            ) {

                                Log.e(
                                    TAG,
                                    "Extractor film $link: ${e.message}"
                                )
                            }
                        }

                } catch (
                    e: Exception
                ) {

                    Log.e(
                        TAG,
                        "Errore GuardaHD: ${e.message}"
                    )
                }
            }
        }

        /*
         * ========================================================
         * VIDXGO FILM
         * ========================================================
         */

        if (
            document.selectFirst(
                "iframe#vidxgo-player-film"
            ) != null
        ) {

            val imdb =
                findVidxGoImdb(
                    document
                )

            if (
                !imdb.isNullOrBlank()
            ) {

                val vidxUrl =
                    "https://v.vidxgo.co/$imdb"

                Log.d(
                    TAG,
                    "VIDXGO FILM = $vidxUrl"
                )

                try {

                    loadExtractor(
                        vidxUrl,
                        "$MAIN_URL/",
                        subtitleCallback,
                        countedCallback
                    )

                } catch (
                    e: Exception
                ) {

                    Log.e(
                        TAG,
                        "Errore VidxGo film: ${e.message}"
                    )
                }
            }
        }

        return linksFound
    }

    // ============================================================
    // SERIE
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

        val season =
            media.season
                ?: return 0

        val episode =
            media.episode
                ?: return 0

        var linksFound =
            0

        val countedCallback:
            (ExtractorLink) -> Unit = {

            linksFound++

            callback(it)
        }

        /*
         * ========================================================
         * STRUTTURA CLASSICA
         * ========================================================
         */

        val pane =
            document.selectFirst(
                "#season-$season"
            )

        val episodeAnchor =
            pane
                ?.select(
                    "ul > li > " +
                        "a[allowfullscreen]" +
                        "[data-link]"
                )
                ?.firstOrNull {

                    val num =
                        it.attr(
                            "data-num"
                        )
                            .substringAfter(
                                'x'
                            )
                            .toIntOrNull()
                            ?: it.text()
                                .trim()
                                .toIntOrNull()

                    num ==
                        episode
                }

        if (
            episodeAnchor != null
        ) {

            Log.d(
                TAG,
                "EPISODIO CLASSICO TROVATO " +
                    "S${season}E${episode}"
            )

            val mirrors =
                episodeAnchor
                    .parent()
                    ?.select(
                        ".mirrors a[data-link]"
                    )
                    ?: emptyList()

            mirrors
                .filterNot {

                    it.text()
                        .contains(
                            "4K",
                            ignoreCase =
                                true
                        )
                }
                .forEach { mirror ->

                    var link =
                        mirror
                            .attr(
                                "data-link"
                            )
                            .trim()

                    if (
                        link.isBlank()
                    ) {

                        return@forEach
                    }

                    if (
                        link.startsWith(
                            "//"
                        )
                    ) {

                        link =
                            "https:$link"
                    }

                    Log.d(
                        TAG,
                        "TV MIRROR = $link"
                    )

                    try {

                        loadExtractor(
                            link,
                            showUrl,
                            subtitleCallback,
                            countedCallback
                        )

                    } catch (
                        e: Exception
                    ) {

                        Log.e(
                            TAG,
                            "Errore TV mirror: ${e.message}"
                        )
                    }
                }
        }

        /*
         * ========================================================
         * VIDXGO
         * ========================================================
         */

        if (
            document.selectFirst(
                "iframe#vidxgo-player"
            ) != null
        ) {

            val imdb =
                findVidxGoImdb(
                    document
                )

            if (
                !imdb.isNullOrBlank()
            ) {

                val vidxUrl =
                    "https://v.vidxgo.co/t/" +
                        "$imdb/" +
                        "$season/" +
                        "$episode"

                Log.d(
                    TAG,
                    "VIDXGO TV = $vidxUrl"
                )

                try {

                    loadExtractor(
                        vidxUrl,
                        "$MAIN_URL/",
                        subtitleCallback,
                        countedCallback
                    )

                } catch (
                    e: Exception
                ) {

                    Log.e(
                        TAG,
                        "Errore VidxGo TV: ${e.message}"
                    )
                }
            }
        }

        return linksFound
    }

    // ============================================================
    // LOAD LINKS UNIVERSAL
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
            "UNIVERSAL → ALTАDEFINIZIONE01"
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

        Log.d(
            TAG,
            "TMDB = ${media.tmdbId}"
        )

        Log.d(
            TAG,
            "Movie = ${media.isMovie}"
        )

        if (
            !media.isMovie
        ) {

            Log.d(
                TAG,
                "S${media.season}E${media.episode}"
            )
        }

        /*
         * 1.
         * Cerca il contenuto.
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
                    "Errore ricerca candidato: ${e.message}",
                    e
                )

                null
            }
                ?: return 0

        val candidate =
            selected.first

        val document =
            selected.second

        Log.d(
            TAG,
            "SELEZIONATO = ${candidate.title}"
        )

        Log.d(
            TAG,
            "URL = ${candidate.url}"
        )

        /*
         * 2.
         * Film oppure episodio.
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
            "ALTАDEFINIZIONE01 LINKS = $links"
        )

        return links
    }
}
