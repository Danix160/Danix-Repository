package com.altadefinizione01

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Altadefinizione01Provider : MainAPI() {

    override var mainUrl =
        "https://altadefinizione-01.fun"

    override var name =
        "Altadefinizione01"

    override var lang =
        "it"

    override val hasMainPage =
        true

    override val hasChromecastSupport =
        true

    override val supportedTypes =
        setOf(
            TvType.Movie,
            TvType.TvSeries
        )

    companion object {

        private const val TAG =
            "AD01_DEBUG"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"
    }

    override val mainPage =
        mainPageOf(
            "$mainUrl/" to
                "Home",

            "$mainUrl/cinema/" to
                "Film",

            "$mainUrl/serie-tv/" to
                "Serie TV"
        )

    private val headers =
        mapOf(
            "User-Agent" to USER_AGENT
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
                mainUrl.trimEnd('/') +
                    value

            value.isBlank() ->
                ""

            else ->
                "$mainUrl/$value"
        }
    }

    // ============================================================
    // CARD
    // ============================================================

    private fun parseCard(
        element: Element
    ): SearchResponse? {

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
                anchor.attr("href")
            )

        if (
            title.isBlank() ||
            url.isBlank()
        ) {
            return null
        }

        val img =
            element.selectFirst(
                "a > img, img"
            )

        val posterRaw =
            img?.attr("data-src")
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: img?.attr("src")
                .orEmpty()

        val poster =
            normalizeUrl(posterRaw)

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

        return if (isTv) {

            newTvSeriesSearchResponse(
                title,
                url,
                TvType.TvSeries
            ) {
                this.posterUrl =
                    poster.takeIf {
                        it.isNotBlank()
                    }
            }

        } else {

            newMovieSearchResponse(
                title,
                url,
                TvType.Movie
            ) {
                this.posterUrl =
                    poster.takeIf {
                        it.isNotBlank()
                    }
            }
        }
    }

    // ============================================================
    // HOME
    // ============================================================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse =
        withContext(
            Dispatchers.IO
        ) {

            val targetUrl =
                if (page <= 1) {

                    request.data

                } else {

                    when {

                        request.data
                            .trimEnd('/')
                            .endsWith(
                                "/cinema",
                                ignoreCase = true
                            ) ->

                            "$mainUrl/cinema/page/$page/"

                        request.data
                            .trimEnd('/')
                            .endsWith(
                                "/serie-tv",
                                ignoreCase = true
                            ) ->

                            "$mainUrl/serie-tv/page/$page/"

                        else ->
                            request.data
                    }
                }

            Log.d(
                TAG,
                "HOME = $targetUrl"
            )

            val document =
                app.get(
                    targetUrl,
                    headers = headers
                ).document

            val results =
                mutableListOf<SearchResponse>()

            /*
             * Slider homepage
             */
            document
                .select(
                    "#slider .boxgrid.caption, " +
                        "div.slider .boxgrid.caption"
                )
                .mapNotNull {
                    parseCard(it)
                }
                .forEach {
                    results.add(it)
                }

            /*
             * Ultimi inseriti / cinema / serie
             */
            document
                .select(
                    "#dle-content .boxgrid.caption, " +
                        "#son_eklenen_kapsul .boxgrid.caption"
                )
                .mapNotNull {
                    parseCard(it)
                }
                .forEach {
                    results.add(it)
                }

            newHomePageResponse(
                request.name,
                results.distinctBy {
                    it.url
                }
            )
        }

    // ============================================================
    // SEARCH
    // ============================================================

    override suspend fun search(
        query: String
    ): List<SearchResponse> =
        withContext(
            Dispatchers.IO
        ) {

            if (query.isBlank()) {
                return@withContext emptyList()
            }

            val results =
                mutableListOf<SearchResponse>()

            val encoded =
                URLEncoder.encode(
                    query,
                    "UTF-8"
                )

            for (page in 1..5) {

                val url =
                    if (page == 1) {

                        "$mainUrl/index.php" +
                            "?do=search" +
                            "&subaction=search" +
                            "&titleonly=3" +
                            "&story=$encoded" +
                            "&full_search=0"

                    } else {

                        val resultFrom =
                            (page - 1) * 50 + 1

                        "$mainUrl/index.php" +
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
                    "SEARCH PAGE $page = $url"
                )

                val response =
                    app.get(
                        url,
                        headers = headers
                    )

                if (
                    response.code != 200
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
                            parseCard(it)
                        }

                results.addAll(
                    pageResults
                )

                /*
                 * Se non esiste paginazione
                 * inutile continuare.
                 */
                if (
                    page == 1 &&
                    document.selectFirst(
                        "div.page_nav"
                    ) == null
                ) {
                    break
                }

                if (
                    pageResults.isEmpty()
                ) {
                    break
                }
            }

            results.distinctBy {
                it.url
            }
        }

    // ============================================================
    // TITLE
    // ============================================================

    private fun getTitle(
        document: org.jsoup.nodes.Document,
        isMovie: Boolean
    ): String {

        var title =
            document.selectFirst(
                "#single .data h1"
            )
                ?.text()
                ?.trim()
                ?: document.selectFirst(
                    "meta[property=og:title]"
                )
                    ?.attr("content")
                    ?.trim()
                ?: document.selectFirst(
                    "h1"
                )
                    ?.text()
                    ?.trim()
                ?: "Senza titolo"

        title =
            title
                .replace(
                    "Streaming HD - Altadefinizione01",
                    ""
                )
                .replace(
                    "Streaming Gratis - Serie TV - Altadefinizione01",
                    ""
                )
                .replace(
                    " - Serie TV",
                    ""
                )
                .trim()

        return title
    }

    // ============================================================
    // IMDB VIDxGO
    // ============================================================

    private fun findVidxGoImdb(
        document: org.jsoup.nodes.Document
    ): String? {

        document
            .select("script")
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
                        .find(content)

                if (match != null) {
                    return match
                        .groupValues[1]
                }
            }

        return null
    }

    // ============================================================
    // VIDXGOFILM
    // ============================================================
        private fun findVidxGoFilmUrl(
            document: org.jsoup.nodes.Document
        ): String? {
        
            val iframe =
                document.selectFirst(
                    "iframe#vidxgo-player-film"
                )
                    ?: return null
        
            val rawUrl =
                iframe.attr("src")
                    .ifBlank {
                        iframe.attr("data-src")
                    }
                    .trim()
        
            if (rawUrl.isBlank()) {
                Log.e(
                    TAG,
                    "iframe VidxGo trovato ma src vuoto: ${iframe.outerHtml()}"
                )
                return null
            }
        
            val finalUrl =
                when {
                    rawUrl.startsWith("//") ->
                        "https:$rawUrl"
        
                    rawUrl.startsWith("http") ->
                        rawUrl
        
                    rawUrl.startsWith("/") ->
                        mainUrl.trimEnd('/') + rawUrl
        
                    else ->
                        rawUrl
                }
        
            Log.d(
                TAG,
                "VIDXGO FILM IFRAME SRC = $finalUrl"
            )
        
            return finalUrl
        }
    // ============================================================
    // LOAD
    // ============================================================

    override suspend fun load(
        url: String
    ): LoadResponse =
        withContext(
            Dispatchers.IO
        ) {

            Log.d(
                TAG,
                "LOAD = $url"
            )

            val document =
                app.get(
                    url,
                    headers = headers
                ).document

            val hasTvStructure =
                document.selectFirst(
                    "#tt_holder"
                ) != null

            val hasVidxTv =
                document.selectFirst(
                    "iframe#vidxgo-player"
                ) != null

            val isTv =
                url.contains(
                    "/serie-tv/",
                    ignoreCase = true
                ) ||
                hasTvStructure ||
                hasVidxTv

            val title =
                getTitle(
                    document,
                    !isTv
                )

            val poster =
                document.selectFirst(
                    "meta[property=og:image]"
                )
                    ?.attr("content")
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: document
                        .selectFirst(
                            ".fix img"
                        )
                        ?.let {
                            it.attr("data-src")
                                .ifBlank {
                                    it.attr("src")
                                }
                        }
                        ?.let {
                            normalizeUrl(it)
                        }

            val plot =
                document.selectFirst(
                    ".sbox .entry-content p"
                )
                    ?.ownText()
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: document.selectFirst(
                        "meta[property=og:description]"
                    )
                        ?.attr("content")

            val year =
                document
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

            val duration =
                document
                    .select(
                        "p.meta_dd:has(b.icon-time)"
                    )
                    .text()
                    .replace(
                        Regex("[^0-9]"),
                        ""
                    )
                    .toIntOrNull()

                    val imdbNumeric =
                        findVidxGoImdb(document)
                    
                    val imdbId =
                        imdbNumeric?.let { "tt$it" }
                    
                    Log.d(
                        TAG,
                        "IMDB LOAD = $imdbId"
                    )

            // ====================================================
            // MOVIE
            // ====================================================

            if (!isTv) {

                return@withContext newMovieLoadResponse(
                    title,
                    url,
                    TvType.Movie,
                    "MOVIE|$url"
                ) {

                    this.posterUrl =
                        poster

                    this.plot =
                        plot

                    this.year =
                        year
                    
                    imdbId?.let {
                    this.addImdbId(it)
                    }

                    if (
                        duration != null &&
                        duration > 0
                    ) {
                        this.duration =
                            duration
                    }
                }
            }

            // ====================================================
            // TV SERIES
            // ====================================================

            val episodes =
                mutableListOf<Episode>()

            /*
             * STRUTTURA CLASSICA DEL SITO
             *
             * #tt_holder
             * season-X
             * data-num="1x3"
             */
            document
                .select(
                    "#tt_holder " +
                        ".tt_season ul li " +
                        "a[data-toggle=tab]"
                )
                .forEach { seasonAnchor ->

                    val paneId =
                        seasonAnchor
                            .attr("href")
                            .removePrefix("#")

                    val seasonNumber =
                        seasonAnchor
                            .text()
                            .trim()
                            .toIntOrNull()
                            ?: return@forEach

                    val pane =
                        document.selectFirst(
                            "#$paneId"
                        )
                            ?: return@forEach

                    pane
                        .select(
                            "ul > li > " +
                                "a[allowfullscreen][data-link]"
                        )
                        .forEach { ep ->

                            val episodeNumber =
                                ep.attr(
                                    "data-num"
                                )
                                    .substringAfter(
                                        'x'
                                    )
                                    .toIntOrNull()
                                    ?: ep.text()
                                        .trim()
                                        .toIntOrNull()
                                    ?: return@forEach

                            val rawTitle =
                                ep.attr(
                                    "data-title"
                                )
                                    .trim()

                            val episodeTitle =
                                rawTitle
                                    .substringBefore(
                                        ":"
                                    )
                                    .trim()
                                    .takeIf {
                                        it.isNotBlank()
                                    }
                                    ?: "Episodio $episodeNumber"

                            val overview =
                                if (
                                    rawTitle.contains(
                                        ":"
                                    )
                                ) {
                                    rawTitle
                                        .substringAfter(
                                            ":"
                                        )
                                        .trim()
                                        .takeIf {
                                            it.isNotBlank()
                                        }
                                } else {
                                    null
                                }

                            episodes.add(
                                newEpisode(
                                    "TV|$url|" +
                                        "$seasonNumber|" +
                                        "$episodeNumber"
                                ) {

                                    this.name =
                                        episodeTitle

                                    this.season =
                                        seasonNumber

                                    this.episode =
                                        episodeNumber

                                    this.description =
                                        overview

                                    this.posterUrl =
                                        poster
                                }
                            )
                        }
                }

            // ====================================================
            // VIDxGO FALLBACK
            // ====================================================

            if (
                episodes.isEmpty() &&
                hasVidxTv
            ) {

                val imdbId =
                    findVidxGoImdb(
                        document
                    )

                Log.d(
                    TAG,
                    "VIDX IMDB = $imdbId"
                )

                if (
                    !imdbId.isNullOrBlank()
                ) {

                    try {

                        val referer =
                            "$mainUrl/"

                        val vidxDocument =
                            app.get(
                                "https://v.vidxgo.co/$imdbId",
                                headers =
                                    mapOf(
                                        "User-Agent" to
                                            USER_AGENT,

                                        "Referer" to
                                            referer,

                                        "sec-fetch-dest" to
                                            "iframe"
                                    )
                            ).document

                        val seasons =
                            vidxDocument
                                .select(
                                    ".ep-season-tab"
                                )
                                .mapNotNull {
                                    it.attr(
                                        "data-season"
                                    )
                                        .toIntOrNull()
                                }
                                .distinct()
                                .sorted()

                        if (
                            seasons.isNotEmpty()
                        ) {

                            seasons.forEach {
                                    seasonNumber ->

                                try {

                                    val jsonResponse =
                                        app.get(
                                            "https://v.vidxgo.co/seasons.php" +
                                                "?imdb=$imdbId" +
                                                "&season=$seasonNumber",
                                            headers =
                                                mapOf(
                                                    "User-Agent" to
                                                        USER_AGENT,

                                                    "Referer" to
                                                        referer,

                                                    "sec-fetch-dest" to
                                                        "empty"
                                                )
                                        )

                                    val json =
                                        JSONObject(
                                            jsonResponse.text
                                        )

                                    if (
                                        json.optInt(
                                            "ok"
                                        ) == 1
                                    ) {

                                        val array =
                                            json.getJSONArray(
                                                "episodes"
                                            )

                                        for (
                                            i in
                                            0 until
                                                array.length()
                                        ) {

                                            val ep =
                                                array
                                                    .getJSONObject(
                                                        i
                                                    )

                                            val episodeNumber =
                                                ep.optInt(
                                                    "number"
                                                )

                                            if (
                                                episodeNumber <= 0
                                            ) {
                                                continue
                                            }

                                            episodes.add(
                                                newEpisode(
                                                    "VIDX|" +
                                                        "$imdbId|" +
                                                        "$seasonNumber|" +
                                                        "$episodeNumber|" +
                                                        "$url"
                                                ) {

                                                    this.season =
                                                        seasonNumber

                                                    this.episode =
                                                        episodeNumber

                                                    this.name =
                                                        ep.optString(
                                                            "name"
                                                        )
                                                            .takeIf {
                                                                it.isNotBlank()
                                                            }
                                                            ?: "Episodio $episodeNumber"

                                                    this.description =
                                                        ep.optString(
                                                            "overview"
                                                        )
                                                            .takeIf {
                                                                it.isNotBlank()
                                                            }

                                                    this.posterUrl =
                                                        ep.optString(
                                                            "still"
                                                        )
                                                            .takeIf {
                                                                it.isNotBlank()
                                                            }
                                                }
                                            )
                                        }
                                    }

                                } catch (
                                    e: Exception
                                ) {

                                    Log.e(
                                        TAG,
                                        "VIDX stagione " +
                                            "$seasonNumber: " +
                                            "${e.message}"
                                    )
                                }
                            }

                        } else {

                            /*
                             * Alcune pagine restituiscono
                             * direttamente episodesList.
                             */
                            vidxDocument
                                .select(
                                    "#episodesList " +
                                        "a.ep-item"
                                )
                                .forEach { ep ->

                                    val href =
                                        ep.attr(
                                            "href"
                                        )

                                    val parts =
                                        href
                                            .trim('/')
                                            .split("/")

                                    if (
                                        parts.size < 3
                                    ) {
                                        return@forEach
                                    }

                                    val seasonNumber =
                                        parts[
                                            parts.size - 2
                                        ]
                                            .toIntOrNull()
                                            ?: return@forEach

                                    val episodeNumber =
                                        parts.last()
                                            .toIntOrNull()
                                            ?: return@forEach

                                    val epName =
                                        ep.selectFirst(
                                            ".ep-name"
                                        )
                                            ?.text()
                                            ?.trim()

                                    val epPlot =
                                        ep.selectFirst(
                                            ".ep-plot"
                                        )
                                            ?.text()
                                            ?.trim()

                                    val epPoster =
                                        ep.selectFirst(
                                            "img.ep-thumb"
                                        )
                                            ?.attr(
                                                "src"
                                            )

                                    episodes.add(
                                        newEpisode(
                                            "VIDX|" +
                                                "$imdbId|" +
                                                "$seasonNumber|" +
                                                "$episodeNumber|" +
                                                "$url"
                                        ) {

                                            this.season =
                                                seasonNumber

                                            this.episode =
                                                episodeNumber

                                            this.name =
                                                epName
                                                    ?.takeIf {
                                                        it.isNotBlank()
                                                    }
                                                    ?: "Episodio $episodeNumber"

                                            this.description =
                                                epPlot

                                            this.posterUrl =
                                                epPoster
                                        }
                                    )
                                }
                        }

                    } catch (
                        e: Exception
                    ) {

                        Log.e(
                            TAG,
                            "Errore VidxGo: " +
                                "${e.message}",
                            e
                        )
                    }
                }
            }

            episodes.sortWith(
                compareBy<Episode> {
                    it.season ?: 0
                }.thenBy {
                    it.episode ?: 0
                }
            )

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {

                this.posterUrl =
                    poster

                this.plot =
                    plot

                this.year =
                    year
               
                imdbId?.let {
                this.addImdbId(it)
                }
            }
        }

    // ============================================================
    // LOAD LINKS
    // ============================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback:
            (SubtitleFile) -> Unit,
        callback:
            (ExtractorLink) -> Unit
    ): Boolean {

        Log.d(
            TAG,
            "LOADLINKS = $data"
        )

        val cleanData =
            data
                .removePrefix("$mainUrl/")
                .removePrefix(mainUrl)
        
        Log.d(
            TAG,
            "LOADLINKS CLEAN = $cleanData"
)

val parts =
    cleanData.split("|")

        if (
            parts.isEmpty()
        ) {
            return false
        }

        when (
            parts[0]
        ) {

            // ====================================================
            // FILM
            // ====================================================

            "MOVIE" -> {

                val pageUrl =
                    parts.getOrNull(1)
                        ?: return false

                val document =
                    app.get(
                        pageUrl,
                        headers = headers
                    ).document

                /*
                 * DEBUG COMPLETO SCRIPT / IFRAME VIDXGO
                 *
                 * Serve a capire quale JavaScript della pagina
                 * valorizza dinamicamente iframe#vidxgo-player-film.
                 */
                Log.d(
                    TAG,
                    "========== SCRIPT AD01 MOVIE =========="
                )

                document
                    .select("script")
                    .forEachIndexed { index, script ->

                        val src =
                            script.attr("src")
                                .trim()

                        val content =
                            script.data()
                                .ifBlank {
                                    script.html()
                                }
                                .trim()

                        Log.d(
                            TAG,
                            "SCRIPT [$index] SRC = $src"
                        )

                        if (content.isNotBlank()) {

                            Log.d(
                                TAG,
                                "SCRIPT [$index] INLINE = ${
                                    content
                                        .replace("\n", " ")
                                        .take(5000)
                                }"
                            )
                        }
                    }

                Log.d(
                    TAG,
                    "========== FINE SCRIPT AD01 MOVIE =========="
                )

                val debugVidxIframe =
                    document.selectFirst(
                        "iframe#vidxgo-player-film"
                    )

                Log.d(
                    TAG,
                    "VIDX IFRAME = ${debugVidxIframe?.outerHtml()}"
                )

                Log.d(
                    TAG,
                    "VIDX IFRAME ATTR = ${debugVidxIframe?.attributes()}"
                )

                /*
                 * GUARDAHD
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

                    Log.d(
                        TAG,
                        "GUARDAHD = $iframeUrl"
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
                                            ignoreCase = true
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

                                loadExtractor(
                                    link,
                                    iframeUrl,
                                    subtitleCallback,
                                    callback
                                )
                            }

                    } catch (
                        e: Exception
                    ) {

                        Log.e(
                            TAG,
                            "Errore Guardahd: " +
                                "${e.message}"
                        )
                    }
                }
                
                                /*
                 * VIDXGO FILM
                 *
                 * Usiamo prima l'URL realmente presente
                 * nell'iframe della pagina.
                 */
                val vidxIframe =
                    document.selectFirst(
                        "iframe#vidxgo-player-film"
                    )
                
                if (vidxIframe != null) {
                
                    Log.d(
                        TAG,
                        "VIDXGO FILM IFRAME HTML = ${vidxIframe.outerHtml()}"
                    )
                
                    val realVidxUrl =
                        findVidxGoFilmUrl(
                            document
                        )
                
                    if (!realVidxUrl.isNullOrBlank()) {
                
                        Log.d(
                            TAG,
                            "VIDXGO FILM REALE = $realVidxUrl"
                        )
                
                        loadExtractor(
                            realVidxUrl,
                            pageUrl,
                            subtitleCallback,
                            callback
                        )
                
                    } else {
                
                        /*
                         * Fallback al vecchio sistema.
                         */
                        val imdb =
                            findVidxGoImdb(
                                document
                            )
                
                        if (!imdb.isNullOrBlank()) {
                
                            val fallbackUrl =
                                "https://v.vidxgo.co/$imdb"
                
                            Log.d(
                                TAG,
                                "VIDXGO FILM FALLBACK = $fallbackUrl"
                            )
                
                            loadExtractor(
                                fallbackUrl,
                                pageUrl,
                                subtitleCallback,
                                callback
                            )
                        }
                    }
                }
            }

            // ====================================================
            // SERIE CLASSICA
            // ====================================================

            "TV" -> {

                if (
                    parts.size < 4
                ) {
                    return false
                }

                val showUrl =
                    parts[1]

                val season =
                    parts[2]
                        .toIntOrNull()
                        ?: return false

                val episode =
                    parts[3]
                        .toIntOrNull()
                        ?: return false

                val document =
                    app.get(
                        showUrl,
                        headers = headers
                    ).document

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

                val mirrors =
                    episodeAnchor
                        ?.parent()
                        ?.select(
                            ".mirrors a[data-link]"
                        )
                        ?: emptyList()

                mirrors
                    .filterNot {
                        it.text()
                            .contains(
                                "4K",
                                ignoreCase = true
                            )
                    }
                    .forEach { mirror ->

                        var link =
                            mirror.attr(
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

                        loadExtractor(
                            link,
                            showUrl,
                            subtitleCallback,
                            callback
                        )
                    }

                /*
                 * Aggiungiamo anche VidxGo
                 * se disponibile.
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

                        Log.d(
                            TAG,
                            "VIDXGO TV REFERER = $showUrl"
                        )

                        loadExtractor(
                            vidxUrl,
                            showUrl,
                            subtitleCallback,
                            callback
                        )
                    }
                }
            }

            // ====================================================
            // SERIE SOLO VIDX
            // ====================================================

            "VIDX" -> {

                if (
                    parts.size < 4
                ) {
                    return false
                }

                val imdb =
                    parts[1]

                val season =
                    parts[2]

                val episode =
                    parts[3]

                /*
                 * URL originale della pagina Altadefinizione01.
                 *
                 * I nuovi episodi VIDX lo contengono in parts[4].
                 * Manteniamo il fallback al mainUrl per episodi
                 * eventualmente già presenti nella cache.
                 */
                val showUrl =
                    parts.getOrNull(4)
                        ?.takeIf {
                            it.startsWith(
                                "http",
                                ignoreCase = true
                            )
                        }
                        ?: "$mainUrl/"

                val vidxUrl =
                    "https://v.vidxgo.co/t/" +
                        "$imdb/" +
                        "$season/" +
                        "$episode"

                Log.d(
                    TAG,
                    "VIDX DIRECT = $vidxUrl"
                )

                Log.d(
                    TAG,
                    "VIDX SERIE REFERER = $showUrl"
                )

                loadExtractor(
                    vidxUrl,
                    showUrl,
                    subtitleCallback,
                    callback
                )
            }
        }

        return true
    }
}
