package com.loonex

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfo
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLDecoder
import java.net.URI

class LoonexProvider : MainAPI() {

    override var mainUrl = "https://loonex.eu"
    override var name = "Loonex"
    override var lang = "it"
    override val hasMainPage = true
    override val hasQuickSearch = false

    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.Anime,
        TvType.TvSeries,
        TvType.Movie
    )

    private val headers = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36",
    "Accept" to "*/*",
    "Accept-Language" to "it-IT,it;q=0.6"
)

    private val tmdbApiKey = "e541cb159df14ce70fc51ab75703a1a2"
    
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbImageBase = "https://image.tmdb.org/t/p/w500"
    
    // Cache durante la vita del provider
    private val tmdbSeriesCache = mutableMapOf<String, Int?>()
    private val tmdbSeasonCache =
        mutableMapOf<Pair<Int, Int>, Map<Int, String>>()

    data class TmdbSearchResponse(
    val results: List<TmdbSearchResult>? = null
)

    data class TmdbSearchResult(
        val id: Int? = null,
        val name: String? = null,
        val original_name: String? = null,
        val first_air_date: String? = null
    )
    
    data class TmdbSeasonResponse(
        val episodes: List<TmdbEpisode>? = null
    )
    
    data class TmdbEpisode(
        @JsonProperty("episode_number")
        val episodeNumber: Int? = null,
    
        @JsonProperty("still_path")
        val stillPath: String? = null
    )

    override val mainPage = mainPageOf(
        "$mainUrl/cartoni/" to "Cartoni"
    )

    override suspend fun getMainPage(
    page: Int,
    request: MainPageRequest
): HomePageResponse {

    val url = if (page <= 1) {
        request.data
    } else {
        "${request.data}?page=$page"
    }

    val doc = app.get(
        url,
        headers = headers
    ).document

    val items = doc.select("""a[href*="?cartone="]""")
        .mapNotNull { a ->

            val title = a.selectFirst(".card-title-cine")
                ?.text()
                ?.trim()
                ?: return@mapNotNull null

            val href = a.attr("href")

            val poster = a.selectFirst("img.card-img-bg")
                ?.attr("src")
                ?.let(::fixUrl)

            newMovieSearchResponse(
                title,
                fixUrl(href),
                TvType.Cartoon
            ) {
                posterUrl = poster
            }
        }
        .distinctBy { it.url }

    return newHomePageResponse(
        HomePageList(
            name = request.name,
            list = items,
            isHorizontalImages = true
        ),
        hasNext = true
    )
}

    override suspend fun search(query: String): List<SearchResponse> {

        val url = "$mainUrl/cartoni/?search=${java.net.URLEncoder.encode(query, "UTF-8")}"

        val doc = app.get(
            url,
            headers = headers
        ).document

        return doc.select("""a[href*="?cartone="]""")
            .mapNotNull { a ->

                val title = a.selectFirst(".card-title-cine")
                    ?.text()
                    ?.trim()
                    ?: return@mapNotNull null

                val href = a.attr("href")

                val poster = a.selectFirst("img.card-img-bg")
                    ?.attr("src")
                    ?.let(::fixUrl)

                newMovieSearchResponse(
                    title,
                    fixUrl(href),
                    TvType.Cartoon
                ) {
                    posterUrl = poster
                }
            }
            .distinctBy { it.url }
    }

    // --- FUNZIONI DI SUPPORTO PER LA DECODIFICA DEL NUOVO PLAYER ---

    private fun rot13(input: String): String {
        return input.map { c ->
            when (c) {
                in 'a'..'z' -> if (c <= 'm') c + 13 else c - 13
                in 'A'..'Z' -> if (c <= 'M') c + 13 else c - 13
                else -> c
            }
        }.joinToString("")
    }

    private fun rc4Decode(encoded: ByteArray, keyStr: String): String {
        val key = keyStr.toByteArray()
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + key[i % key.size]) and 0xFF
            val temp = s[i]; s[i] = s[j]; s[j] = temp
        }
        var i = 0
        j = 0
        val out = ByteArray(encoded.size)
        for (y in encoded.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val temp = s[i]; s[i] = s[j]; s[j] = temp
            out[y] = (encoded[y].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
        }
        return String(out)
    }

    private fun lxBrowserUnpack(payload: String, baseKey: String): String? {
        val b64Str = payload.replace("-", "+").replace("_", "/")
        val pad = b64Str.length % 4
        val padded = if (pad > 0) b64Str + "=".repeat(4 - pad) else b64Str
        val bin = android.util.Base64.decode(padded, android.util.Base64.DEFAULT)

        val tryKeys = listOf(baseKey, "$baseKey:lx3")
        for (k in tryKeys) {
            try {
                val dec = rc4Decode(bin, k)
                if (dec.startsWith("LX2:") || dec.startsWith("LX3:")) {
                    return URLDecoder.decode(dec.substring(4), "UTF-8")
                }
            } catch (_: Exception) {}
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse {

    val doc = app.get(
        url,
        headers = headers
    ).document

    val html = doc.toString()

    val title = Regex(
        """"title"\s*:\s*"([^"]+)""""
    ).find(html)
        ?.groupValues
        ?.get(1)
        ?.replace("\\/", "/")
        ?: doc.selectFirst("h1,h2,.cartoon-title")
            ?.text()
            ?.trim()
        ?: "Loonex"

    val poster = Regex(
        """"image"\s*:\s*"([^"]+)""""
    ).find(html)
        ?.groupValues
        ?.get(1)
        ?.replace("\\/", "/")
        ?.let(::fixUrl)

        val plot = doc
            .selectFirst(".content-box-opaque .text-secondary[style*=\"line-height\"]")
            ?.ownText()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

         val trailerUrl = doc
            .selectFirst("iframe.poster-trailer-iframe[src]")
            ?.attr("src")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { src ->
                Regex("""embed/([A-Za-z0-9_-]{11})""")
                    .find(src)
                    ?.groupValues
                    ?.getOrNull(1)
            }
            ?.let { videoId ->
                "https://www.youtube.com/watch?v=$videoId"
            }
            
            val rawTrailerUrl = try {
                trailerUrl?.let { youtubeUrl ->
                    val service = NewPipe.getService(0)
                    val info = StreamInfo.getInfo(service, youtubeUrl)
            
                    info.videoStreams
                        .firstOrNull()
                        ?.content
                        ?.takeIf { it.isNotBlank() }
                }
            } catch (e: Exception) {
                null
            }


/*
 * =========================================================
 * FILM
 * =========================================================
 *
 * Loonex usa .quality-card per i film completi,
 * mentre le serie utilizzano .episode-row.
 */
val movieCard = doc.selectFirst(
    ".quality-card[data-ep-label]"
)

if (movieCard != null) {

    val movieUrl = movieCard
        .selectFirst("a.auto-watch-btn[href]")
        ?.attr("href")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    if (movieUrl != null) {

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            fixUrl(movieUrl)
        ) {
            posterUrl = poster
            this.plot = plot
        
            if (rawTrailerUrl != null) {
                trailers.add(
                    TrailerData(
                        extractorUrl = rawTrailerUrl,
                        referer = null,
                        raw = true
                    )
                )
            } else {
                trailerUrl?.let {
                    trailers.add(
                        TrailerData(
                            extractorUrl = it,
                            referer = null,
                            raw = false
                        )
                    )
                }
            }
        }
    }
}

////////////////
// SERIE ///////
////////////////
        
    val episodes = mutableListOf<Episode>()
    val seasonsData = mutableListOf<SeasonData>()

    val seasonButtons = doc.select(
    """#season-tabs button[data-bs-target][data-season-name]"""
)

seasonButtons.forEachIndexed { tabIndex, button ->

    /*
     * Ogni TAB Loonex diventa una "stagione"
     * nel selettore Cloudstream.
     */
    val cloudSeason = tabIndex + 1

    val tabName = button
        .attr("data-season-name")
        .trim()
        .ifBlank {
            "Parte $cloudSeason"
        }

    val tmdbId = findTmdbSeries(tabName)

    val tmdbStillsBySeason =
    mutableMapOf<Int, Map<Int, String>>()
    

    val targetId = button
        .attr("data-bs-target")
        .trim()
        .removePrefix("#")

    if (targetId.isBlank()) {
        return@forEachIndexed
    }

    val tabContainer = doc.getElementById(targetId)
        ?: return@forEachIndexed

    /*
     * Il nome visualizzato nel selettore Cloudstream
     * è esattamente il nome del TAB Loonex.
     */
    seasonsData.add(
        SeasonData(
            cloudSeason,
            tabName
        )
    )

    /*
     * Prendiamo TUTTI gli episodi presenti
     * esclusivamente dentro questo tab.
     */
    val rows = tabContainer.select(".episode-row")

    rows.forEachIndexed episodeLoop@ { index, row ->

        val label = row
            .attr("data-ep-label")
            .trim()

        val playUrl = row
            .selectFirst("a.btn-play-sm[href]")
            ?.attr("href")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: return@episodeLoop

        /*
         * Recuperiamo la numerazione originale:
         *
         * 1x01
         * 2x04
         * 3x12
         *
         * Serve per il NOME, non per il
         * raggruppamento Cloudstream.
         */
        /*
 * Cerchiamo prima SxE nel label.
 *
 * Esempio:
 * "Episodio 1x05"
 *
 * Se non esiste, lo cerchiamo nell'URL:
 *
 * cucciolo_scooby_doo_1x05
 */
val xMatch =
    Regex(
        """(?i)(\d+)\s*[x×]\s*0*(\d+)"""
    ).find(label)
        ?: Regex(
            """(?i)(\d+)[x×]0*(\d+)"""
        ).find(playUrl)

val originalSeason = xMatch
    ?.groupValues
    ?.getOrNull(1)
    ?.toIntOrNull()
    ?: cloudSeason

val originalEpisode = xMatch
    ?.groupValues
    ?.getOrNull(2)
    ?.toIntOrNull()
    ?: Regex(
        """(?i)(?:episodio|episode|ep)\s*0*(\d+)"""
    ).find(label)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    ?: (index + 1)
    
      val episodeStill =
    if (
        tmdbId != null &&
        originalSeason != null &&
        originalEpisode != null
    ) {

        var seasonStills =
            tmdbStillsBySeason[originalSeason]

        if (seasonStills == null) {

            seasonStills = getTmdbSeasonStills(
                tmdbId,
                originalSeason
            )

            tmdbStillsBySeason[originalSeason] =
                seasonStills
        }

        seasonStills[originalEpisode]

    } else {
        null
    }
        /*
         * Cloudstream deve avere episodi progressivi
         * all'interno del TAB.
         *
         * Questo evita collisioni:
         *
         * 1x01
         * 2x01
         * 3x01
         *
         * non possono essere tutti episode = 1.
         */
        val cloudEpisode = index + 1

        val displayName =
            if (label.isNotBlank()) {
                label
            } else {
                "Episodio %02d".format(originalEpisode)
            }

        episodes.add(
        newEpisode(
        fixUrl(playUrl)
    ) {
        this.season = cloudSeason
        this.episode = cloudEpisode
        this.name = displayName

        /*
         * Prima scelta:
         * still vera TMDB.
         *
         * Fallback:
         * poster principale Loonex.
         */
        this.posterUrl = episodeStill ?: poster
    }
)
    }
}

    /*
     * Fallback per eventuali pagine Loonex
     * che non usano i tab delle stagioni.
     */
    if (episodes.isEmpty()) {

        doc.select(".episode-row")
            .forEachIndexed { index, row ->

                val label = row
                    .attr("data-ep-label")
                    .trim()

                val playUrl = row
                    .selectFirst("a.btn-play-sm")
                    ?.attr("href")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@forEachIndexed

                val numbers = Regex(
                    """(?i)(\d+)\s*[x×]\s*0*(\d+)"""
                ).find(label)

                val seasonNumber =
                    numbers
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: 1

                val episodeNumber =
                    numbers
                        ?.groupValues
                        ?.getOrNull(2)
                        ?.toIntOrNull()
                        ?: (index + 1)

                if (
                    seasonsData.none {
                        it.season == seasonNumber
                    }
                ) {
                    seasonsData.add(
                        SeasonData(
                            seasonNumber,
                            "Stagione $seasonNumber"
                        )
                    )
                }

                episodes.add(
                    newEpisode(
                        fixUrl(playUrl)
                    ) {
                        this.name = label.ifBlank {
                            "Episodio $episodeNumber"
                        }

                        this.season = seasonNumber
                        this.episode = episodeNumber
                    }
                )
            }
    }

    return newTvSeriesLoadResponse(
    title,
    url,
    TvType.Cartoon,
    episodes
) {
    posterUrl = poster
    this.plot = plot

    if (rawTrailerUrl != null) {
        trailers.add(
            TrailerData(
                extractorUrl = rawTrailerUrl,
                referer = null,
                raw = true
            )
        )
    } else {
        trailerUrl?.let {
            trailers.add(
                TrailerData(
                    extractorUrl = it,
                    referer = null,
                    raw = false
                )
            )
        }
    }

    addSeasonNames(seasonsData)
}
}
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
    
        /*
         * =========================================================
         * 1. DRIME
         * =========================================================
         *
         * I film Drime arrivano già a loadLinks() così:
         *
         * https://loonex.eu/guarda/?drim=HASH
         *
         * Quindi estraiamo l'hash direttamente da "data"
         * PRIMA di aprire/decodificare la pagina.
         */
    
        val drimeHash = Regex(
            """[?&]drim=([^&#]+)""",
            RegexOption.IGNORE_CASE
        ).find(data)
            ?.groupValues
            ?.getOrNull(1)
            ?.let {
                try {
                    URLDecoder.decode(it, "UTF-8")
                } catch (_: Exception) {
                    it
                }
            }
            ?.trim()
    
        if (!drimeHash.isNullOrBlank()) {
    
            val drimePageUrl = "$mainUrl/guarda/?drim=" +
                java.net.URLEncoder.encode(
                    drimeHash,
                    "UTF-8"
                )
    
            /*
             * Replica la richiesta effettuata dal player:
             *
             * POST /guarda/?drim=HASH
             *
             * action=drime_resolve
             * hash=HASH
             */
    
            val drimeResponse = app.post(
                drimePageUrl,
                headers = headers + mapOf(
                    "Content-Type" to
                        "application/x-www-form-urlencoded;charset=UTF-8",
                    "Accept" to "application/json, text/plain, */*",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                referer = drimePageUrl,
                data = mapOf(
                    "action" to "drime_resolve",
                    "hash" to drimeHash
                )
            )
    
            val drimeJson = drimeResponse.text
    
            val stream = Regex(
                """"stream"\s*:\s*"([^"]+)""""
            ).find(drimeJson)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\/", "/")
                ?.replace("\\u0026", "&")
                ?.replace("\\u003d", "=")
                ?.trim()
    
            if (stream.isNullOrBlank()) {
                return false
            }
    
            callback(
                newExtractorLink(
                    source = "Loonex Drime",
                    name = "Loonex Drime",
                    url = stream,
                    type = if (
                        stream.contains(".m3u8", true)
                    ) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                ) {
                    /*
                     * Il player Drime usa no-referrer.
                     */
                    this.headers = mapOf(
                        "User-Agent" to
                            (headers["User-Agent"] ?: "")
                    )
                }
            )
    
            return true
        }
    
        /*
         * =========================================================
         * 2. LOONEX NORMALE (Nuovo Sistema API + RC4)
         * =========================================================
         */
        val response = app.get(
            data,
            headers = headers,
            referer = "$mainUrl/"
        )
        val html = response.text

        // Recuperiamo l'ID dell'episodio corrente (necessario per la POST)
        val currentVideoId = Regex("""const\s+currentVideoId\s*=\s*(?:\(function\(\)\s*\{\s*return\s*)?["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""[?&]id=([^&]+)""").find(data)?.groupValues?.get(1)
            ?: return false

        // Cerchiamo il token di sessione _lxSessionCtx
        val dMatch = Regex("""var\s+_d\s*=\s*["']([^"']+)["']""").find(html)
        if (dMatch != null) {
            val dStr = dMatch.groupValues[1]
            val rot13Str = rot13(dStr)
            
            val decodedTokenKey = String(android.util.Base64.decode(rot13Str, android.util.Base64.DEFAULT))
            val parts = decodedTokenKey.split(":")
            
            if (parts.size >= 2) {
                val sessionToken = parts[0]
                val sessionKey = parts[1]

                // Simuliamo la chiamata ajax_handler.php o alla stessa URL
                val authResponse = app.post(
                    data,
                    headers = headers + mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    data = mapOf(
                        "action" to "guarda_play_auth",
                        "token" to sessionToken,
                        "video_id" to currentVideoId,
                        "raw_video_id" to currentVideoId,
                        "player_type" to "norm",
                        "srv" to "1"
                    ),
                    referer = data
                )

                val authJson = authResponse.text
                val payload = Regex(""""payload"\s*:\s*"([^"]+)"""").find(authJson)?.groupValues?.get(1)

                if (payload != null) {
                    val unpacked = lxBrowserUnpack(payload, sessionKey)
                    if (unpacked != null) {
                        val streamUrl = Regex(""""streamUrl"\s*:\s*"([^"]+)"""")
                            .find(unpacked)?.groupValues?.get(1)?.replace("\\/", "/")
                        
                        // Ignora i video esca
                        if (!streamUrl.isNullOrBlank() && !streamUrl.contains("start1.mp4")) {
                            callback(
                                newExtractorLink(
                                    source = "Loonex",
                                    name = "Loonex",
                                    url = streamUrl,
                                    type = if (streamUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "$mainUrl/"
                                }
                            )
                            return true
                        }
                    }
                }
            }
        }

        // =========================================================
        // FALLBACK: Vecchio metodo XOR (seleziona i video non aggiornati)
        // =========================================================
        val encoded = Regex("""var\s+encodedStr\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)
        val key = Regex("""var\s+decryptionKey\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)

        if (encoded != null && key != null) {
            val decoded = decryptLoonexUrl(encoded, key)
            if (decoded.isNotBlank() && !decoded.contains("start1.mp4")) {
                val videoUrl = encodeUrlPath(decoded)
                
                callback(
                    newExtractorLink(
                        source = "Loonex (Legacy)",
                        name = "Loonex (Legacy)",
                        url = videoUrl,
                        type = if (videoUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                    }
                )
                return true
            }
        }

        return false
    }

    private suspend fun findTmdbSeries(
    rawTitle: String
): Int? {

    tmdbSeriesCache[rawTitle]?.let {
        return it
    }

    /*
     * Estraiamo eventuale anno:
     *
     * Ben 10 (2005) Serie Completa
     *             ↓
     *            2005
     */
    val year = Regex(
        """\((19|20)\d{2}\)"""
    ).find(rawTitle)
        ?.value
        ?.removePrefix("(")
        ?.removeSuffix(")")
        ?.toIntOrNull()

    /*
     * Pulizia del nome Loonex.
     */
    val cleanTitle = rawTitle
        .replace(
            Regex("""\((19|20)\d{2}\)"""),
            ""
        )
        .replace(
            Regex("""(?i)\bserie\s+completa\b"""),
            ""
        )
        .trim()

    val encodedTitle = java.net.URLEncoder.encode(
        cleanTitle,
        "UTF-8"
    )

    val searchUrl = buildString {
        append("$tmdbApi/search/tv")
        append("?api_key=$tmdbApiKey")
        append("&query=$encodedTitle")
        append("&language=it-IT")

        if (year != null) {
            append("&first_air_date_year=$year")
        }
    }

    return try {

        val response = app.get(
            searchUrl,
            headers = headers
        )

        val json = response.parsedSafe<TmdbSearchResponse>()

        val result = json
            ?.results
            ?.firstOrNull()

        val id = result?.id

        tmdbSeriesCache[rawTitle] = id

        id

    } catch (_: Exception) {

        tmdbSeriesCache[rawTitle] = null
        null
    }
}

    private suspend fun getTmdbSeasonStills(
    tmdbId: Int,
    season: Int
): Map<Int, String> {

    val cacheKey = tmdbId to season

    tmdbSeasonCache[cacheKey]?.let {
        return it
    }

    val url =
        "$tmdbApi/tv/$tmdbId/season/$season" +
        "?api_key=$tmdbApiKey" +
        "&language=it-IT"

    return try {

        val response = app.get(
            url,
            headers = headers
        )

        val data = response
            .parsedSafe<TmdbSeasonResponse>()

        val result = data
            ?.episodes
            ?.mapNotNull { episode ->

                val number = episode.episodeNumber
                    ?: return@mapNotNull null

                val still = episode.stillPath
                    ?: return@mapNotNull null

                number to "$tmdbImageBase$still"
            }
            ?.toMap()
            ?: emptyMap()

        tmdbSeasonCache[cacheKey] = result

        result

    } catch (_: Exception) {

        tmdbSeasonCache[cacheKey] = emptyMap()
        emptyMap()
    }
}

    private fun decryptLoonexUrl(
        hex: String,
        key: String
    ): String {

        if (key.isBlank()) return ""

        val decoded = buildString {

            var i = 0

            while (i + 1 < hex.length) {

                val value = hex
                    .substring(i, i + 2)
                    .toIntOrNull(16)
                    ?: break

                val keyChar = key[
                    (i / 2) % key.length
                ].code

                append(
                    (value xor keyChar).toChar()
                )

                i += 2
            }
        }

        return try {
            URLDecoder.decode(
                decoded,
                "UTF-8"
            )
        } catch (_: Exception) {
            decoded
        }
    }

    private fun encodeUrlPath(url: String): String {
        return try {

            val uri = URI(url)

            URI(
                uri.scheme,
                uri.userInfo,
                uri.host,
                uri.port,
                uri.path,
                uri.query,
                uri.fragment
            ).toASCIIString()

        } catch (_: Exception) {
            url
                .replace(" ", "%20")
                .replace("[", "%5B")
                .replace("]", "%5D")
        }
    }

    private fun fixUrl(url: String): String {

        if (url.startsWith("http")) {
            return url
        }

        if (url.startsWith("//")) {
            return "https:$url"
        }

        return if (url.startsWith("/")) {
            "$mainUrl$url"
        } else {
            "$mainUrl/cartoni/$url"
        }
    }
}
