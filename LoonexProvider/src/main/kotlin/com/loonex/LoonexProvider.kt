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
        val key = keyStr.toByteArray(Charsets.UTF_8)
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val temp = s[i]
            s[i] = s[j]
            s[j] = temp
        }
        var i = 0
        j = 0
        val out = CharArray(encoded.size)
        for (y in encoded.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val temp = s[i]
            s[i] = s[j]
            s[j] = temp
            
            // IL FIX ERA QUI: "and 0xFF" impedisce l'estensione del segno in negativo
            val byteVal = encoded[y].toInt() and 0xFF
            val cipherByte = s[(s[i] + s[j]) and 0xFF]
            out[y] = (byteVal xor cipherByte).toChar()
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
        val doc = app.get(url, headers = headers).document
        val html = doc.toString()

        val title = Regex(""""title"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: doc.selectFirst("h1,h2,.cartoon-title")?.text()?.trim() ?: "Loonex"

        val poster = Regex(""""image"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.replace("\\/", "/")?.let(::fixUrl)

        val plot = doc.selectFirst(".content-box-opaque .text-secondary[style*=\"line-height\"]")?.ownText()?.trim()?.takeIf { it.isNotBlank() }

        val trailerUrl = doc.selectFirst("iframe.poster-trailer-iframe[src]")?.attr("src")?.trim()?.takeIf { it.isNotBlank() }
            ?.let { src -> Regex("""embed/([A-Za-z0-9_-]{11})""").find(src)?.groupValues?.getOrNull(1) }
            ?.let { videoId -> "https://www.youtube.com/watch?v=$videoId" }
            
        val rawTrailerUrl = try {
            trailerUrl?.let { youtubeUrl ->
                val service = NewPipe.getService(0)
                val info = StreamInfo.getInfo(service, youtubeUrl)
                info.videoStreams.firstOrNull()?.content?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) { null }

        val hasSeasonTabs = doc.select(
            """#season-tabs button[data-bs-target][data-season-name]"""
        ).isNotEmpty()
        
        val movieCard = if (!hasSeasonTabs) {
            doc.selectFirst(".quality-card[data-ep-label]")
        } else {
            null
        }

if (movieCard != null) {
    val rawMovieUrl = movieCard
        .selectFirst("a.auto-watch-btn[href]")
        ?.attr("href")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    // start1.mp4 è lo stream civetta del nuovo player.
    // Non deve essere passato direttamente a loadLinks().
    val movieUrl = rawMovieUrl?.takeUnless {
        it.contains("start1.mp4", ignoreCase = true)
    }

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
        val episodes = mutableListOf<Episode>()
        val seasonsData = mutableListOf<SeasonData>()

        val seasonButtons = doc.select("""#season-tabs button[data-bs-target][data-season-name]""")
        seasonButtons.forEachIndexed { tabIndex, button ->
            val cloudSeason = tabIndex + 1
            val tabName = button.attr("data-season-name").trim().ifBlank { "Parte $cloudSeason" }
            val tmdbId = findTmdbSeries(tabName)
            val tmdbStillsBySeason = mutableMapOf<Int, Map<Int, String>>()
            val targetId = button.attr("data-bs-target").trim().removePrefix("#")
            
            if (targetId.isBlank()) return@forEachIndexed
            val tabContainer = doc.getElementById(targetId) ?: return@forEachIndexed

            seasonsData.add(SeasonData(cloudSeason, tabName))

            val rows = tabContainer.select(".episode-row")

            rows.forEachIndexed episodeLoop@ { index, row ->
                val label = row.attr("data-ep-label").trim()

                val playButton = row.selectFirst("a.btn-play-sm")

                println("LOONEX_ROW: label=$label")
                println("LOONEX_ROW: href=${playButton?.attr("href")}")
                println("LOONEX_ROW: data-v=${playButton?.attr("data-v")}")
                println("LOONEX_ROW: data-stream=${playButton?.attr("data-stream")}")
                println("LOONEX_ROW: data-chk=${row.attr("data-chk")}")
                println("LOONEX_ROW: outerHtml=${row.outerHtml()}")

                if (index == 0 && cloudSeason == 1) {
                println("LOONEX_ROW: ===== RAW FIRST EPISODE =====")
                println(row.outerHtml())
                println("LOONEX_ROW: ===== END RAW FIRST EPISODE =====")
            }
            
                val rawPlayUrl = row
                    .selectFirst("a.btn-play-sm[href]")
                    ?.attr("href")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@episodeLoop
            
                val playUrl = fixUrl(rawPlayUrl)
            
                println("LOONEX_DEBUG: EPISODE label=$label")
                println("LOONEX_DEBUG: EPISODE rawPlayUrl=$rawPlayUrl")
                println("LOONEX_DEBUG: EPISODE finalPlayUrl=$playUrl")
            
                val xMatch =
                    Regex("""(?i)(\d+)\s*[x×]\s*0*(\d+)""").find(label)
                        ?: Regex("""(?i)(\d+)[x×]0*(\d+)""").find(playUrl)
            
                val originalSeason =
                    xMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: cloudSeason
            
                val originalEpisode =
                    xMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                        ?: Regex("""(?i)(?:episodio|episode|ep)\s*0*(\d+)""")
                            .find(label)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                        ?: Regex("""^0*(\d+)""")
                            .find(label)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                        ?: (index + 1)
            
                val episodeStill = if (tmdbId != null) {
                    var seasonStills = tmdbStillsBySeason[originalSeason]
            
                    if (seasonStills == null) {
                        seasonStills = getTmdbSeasonStills(
                            tmdbId,
                            originalSeason
                        )
                        tmdbStillsBySeason[originalSeason] = seasonStills
                    }
            
                    seasonStills[originalEpisode]
                } else {
                    null
                }
            
                val cloudEpisode = index + 1
            
                val displayName = if (label.isNotBlank()) {
                    label
                } else {
                    "Episodio %02d".format(originalEpisode)
                }
            
                episodes.add(
                    newEpisode(playUrl) {
                        this.season = cloudSeason
                        this.episode = cloudEpisode
                        this.name = displayName
                        this.posterUrl = episodeStill ?: poster
                    }
                )
            }
        }

        if (episodes.isEmpty()) {
            doc.select(".episode-row").forEachIndexed { index, row ->
                val label = row.attr("data-ep-label").trim()
                val playUrl = row.selectFirst("a.btn-play-sm")?.attr("href")?.trim()?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
                val numbers = Regex("""(?i)(\d+)\s*[x×]\s*0*(\d+)""").find(label)
                val seasonNumber = numbers?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                val episodeNumber = numbers?.groupValues?.getOrNull(2)?.toIntOrNull() ?: (index + 1)

                if (seasonsData.none { it.season == seasonNumber }) {
                    seasonsData.add(SeasonData(seasonNumber, "Stagione $seasonNumber"))
                }

                episodes.add(newEpisode(fixUrl(playUrl)) {
                    this.name = label.ifBlank { "Episodio $episodeNumber" }
                    this.season = seasonNumber
                    this.episode = episodeNumber
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            posterUrl = poster
            this.plot = plot
            if (rawTrailerUrl != null) {
                trailers.add(TrailerData(extractorUrl = rawTrailerUrl, referer = null, raw = true))
            } else {
                trailerUrl?.let { trailers.add(TrailerData(extractorUrl = it, referer = null, raw = false)) }
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
    
        println("LOONEX_DEBUG: ===== LOADLINKS START =====")
        println("LOONEX_DEBUG: rawData=${data.take(100)}")
    
        // =========================================================
        // 1. DRIME
        // =========================================================
        val drimeHash = Regex("""[?&]drim=([^&#]+)""", RegexOption.IGNORE_CASE).find(data)
            ?.groupValues?.getOrNull(1)?.let {
                try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
            }?.trim()
    
        if (!drimeHash.isNullOrBlank()) {
            val drimePageUrl = "$mainUrl/guarda/?drim=" + java.net.URLEncoder.encode(drimeHash, "UTF-8")
            
            val drimeResponse = app.post(
                drimePageUrl,
                headers = headers + mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                    "Accept" to "application/json, text/plain, */*",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                referer = drimePageUrl,
                data = mapOf("action" to "drime_resolve", "hash" to drimeHash)
            )
            
            val drimeJson = drimeResponse.okhttpResponse.body?.string() ?: ""
            val stream = Regex(""""stream"\s*:\s*"([^"]+)"""").find(drimeJson)
                ?.groupValues?.getOrNull(1)?.replace("\\/", "/")
                ?.replace("\\u0026", "&")?.replace("\\u003d", "=")?.trim()
    
            if (!stream.isNullOrBlank()) {
                callback(
                    newExtractorLink(
                        source = "Loonex Drime",
                        name = "Loonex Drime",
                        url = stream,
                        type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.headers = mapOf("User-Agent" to (headers["User-Agent"] ?: ""))
                    }
                )
                return true
            }
        }
    
        // =========================================================
        // 2. LOONEX NORMALE (Nuovo Sistema API + RC4)
        // =========================================================
        
        // Eseguiamo la richiesta normale, mantenendo i cookie/Cloudflare interceptor
        val response = app.get(data, headers = headers, referer = "$mainUrl/")
        // Estraiamo il body grezzo direttamente da OkHttp per bypassare i limiti di 5MB e le alterazioni di Jsoup
        val html = response.okhttpResponse.body?.string() ?: ""

        // =========================================================
        // B. Player autorizzato Loonex
        // Replica di guardaResolveAuthorizedStream()
        // =========================================================
        
        println("LOONEX_DEBUG: ===== LOADLINKS START =====")
        println("LOONEX_DEBUG: episodeUrl=$data")
        println("LOONEX_DEBUG: htmlSize=${html.length}")
        
        val currentVideoId = Regex(
            """const\s+currentVideoId\s*=\s*(?:\(function\(\)\s*\{\s*return\s*)?["']([^"']+)["']"""
        ).find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""[?&]id=([^&]+)""")
                .find(data)?.groupValues?.getOrNull(1)
        
        val currentVideoIdSanitized = Regex(
            """const\s+currentVideoIdSanitized\s*=\s*["']([^"']+)["']"""
        ).find(html)?.groupValues?.getOrNull(1)
            ?: currentVideoId
        
        println("LOONEX_DEBUG: videoId=$currentVideoId")
        println("LOONEX_DEBUG: sanitized=$currentVideoIdSanitized")
        
        if (!currentVideoId.isNullOrBlank()) {
        
            /*
             * Il player NON usa var _d per l'autorizzazione.
             *
             * _lxSessionCtx() esegue:
             *
             * ROT13(_authCtx)
             * -> Base64
             * -> "token:chiave"
             */
        
            val authCtx = Regex(
                """var\s+_authCtx\s*=\s*["']([^"']+)["']"""
            ).find(html)?.groupValues?.getOrNull(1)

            println(
            "LOONEX_DEBUG: authCtxFound=${!authCtx.isNullOrBlank()} length=${authCtx?.length ?: 0}"
        )
        
            if (!authCtx.isNullOrBlank()) {
                try {
        
                    val rot13Ctx = rot13(authCtx)
        
                    val decodedCtx = String(
                        android.util.Base64.decode(
                            rot13Ctx,
                            android.util.Base64.DEFAULT
                        ),
                        Charsets.UTF_8
                    )
        
                    val ctxParts = decodedCtx.split(":", limit = 2)

                    println("LOONEX_DEBUG: decodedCtxLength=${decodedCtx.length}")
                    println("LOONEX_DEBUG: ctxParts=${ctxParts.size}")
                    
                    if (ctxParts.size == 2) {
                        println("LOONEX_DEBUG: sessionTokenLength=${ctxParts[0].length}")
                        println("LOONEX_DEBUG: sessionKeyLength=${ctxParts[1].length}")
                    }
        
                    if (ctxParts.size == 2) {
        
                        val sessionToken = ctxParts[0]
                        val sessionKey = ctxParts[1]
        
                        /*
                         * Il JS usa:
                         *
                         * window.location.pathname +
                         * window.location.search
                         *
                         * quindi dobbiamo fare POST alla stessa
                         * pagina episodio.
                         */
        
                        val authHeaders = headers + mapOf(
                            "Accept" to "application/json, text/plain, */*",
                            "Content-Type" to
                                "application/x-www-form-urlencoded;charset=UTF-8",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Origin" to mainUrl
                        )

                        println("LOONEX_DEBUG: POST target=$data")
                        println("LOONEX_DEBUG: action=guarda_play_auth")
                        println("LOONEX_DEBUG: player_type=norm srv=1")
        
                        val authResponse = app.post(
                            data,
                            headers = authHeaders,
                            referer = data,
                            data = mapOf(
                                "action" to "guarda_play_auth",
                                "token" to sessionToken,
                                "video_id" to
                                    (currentVideoIdSanitized ?: currentVideoId),
                                "raw_video_id" to currentVideoId,
                                "player_type" to "norm",
                                "srv" to "1"
                            )
                        )
        
                        val authJson =
                            authResponse.okhttpResponse.body
                                ?.string()
                                .orEmpty()

                                println("LOONEX_DEBUG: authHttpCode=${authResponse.code}")
                                println("LOONEX_DEBUG: authJsonLength=${authJson.length}")
                                
                                // Per adesso stampiamo la risposta: dovrebbe essere piccola.
                                // Non stampiamo token/key.
                                println("LOONEX_DEBUG: authJson=$authJson")
        
                        /*
                         * Il server normalmente restituisce:
                         *
                         * {
                         *   "ok": true,
                         *   "payload": "..."
                         * }
                         */
        
                        val payload = Regex(
                            """"payload"\s*:\s*"([^"]+)""""
                        ).find(authJson)
                            ?.groupValues
                            ?.getOrNull(1)

                            println(
                            "LOONEX_DEBUG: payloadFound=${!payload.isNullOrBlank()} length=${payload?.length ?: 0}"
                        )
        
                        var finalStream: String? = null
        
                        // -------------------------------
                        // Risposta cifrata LX2/LX3
                        // -------------------------------
        
                        if (!payload.isNullOrBlank()) {
        
                            val unpacked = lxBrowserUnpack(
                                payload,
                                sessionKey
                            )
                            
                            println(
                                "LOONEX_DEBUG: unpackSuccess=${!unpacked.isNullOrBlank()} length=${unpacked?.length ?: 0}"
                            )
                            
                            if (!unpacked.isNullOrBlank()) {
                                println("LOONEX_DEBUG: unpacked=$unpacked")
        
                                /*
                                 * Il JS fa JSON.parse(dec)
                                 */
        
                                val parsedOk = Regex(
                                    """"ok"\s*:\s*true"""
                                ).containsMatchIn(unpacked)
        
                                if (parsedOk) {
        
                                    finalStream = Regex(
                                        """"streamUrl"\s*:\s*"([^"]+)""""
                                    ).find(unpacked)
                                        ?.groupValues
                                        ?.getOrNull(1)
                                        ?.replace("\\/", "/")
                                        ?.replace("\\u0026", "&")
                                        ?.replace("\\u003d", "=")
                                }
                            }
                        }
        
                        // -------------------------------
                        // Eventuale risposta non cifrata
                        // -------------------------------
                        println("LOONEX_DEBUG: finalStream=$finalStream")
                        
                        if (finalStream.isNullOrBlank()) {
        
                            finalStream = Regex(
                                """"streamUrl"\s*:\s*"([^"]+)""""
                            ).find(authJson)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.replace("\\/", "/")
                                ?.replace("\\u0026", "&")
                                ?.replace("\\u003d", "=")
                        }
        
                        // -------------------------------
                        // Elimina stream civetta
                        // -------------------------------
        
                        if (
                            !finalStream.isNullOrBlank() &&
                            !finalStream.contains(
                                "start1.mp4",
                                ignoreCase = true
                            )
                        ) {
        
                            callback(
                                newExtractorLink(
                                    source = "Loonex",
                                    name = "Loonex",
                                    url = finalStream,
                                    type = if (
                                        finalStream.contains(
                                            ".m3u8",
                                            ignoreCase = true
                                        )
                                    ) {
                                        ExtractorLinkType.M3U8
                                    } else {
                                        ExtractorLinkType.VIDEO
                                    }
                                ) {
        
                                    /*
                                     * videoserver.loonex.eu
                                     * richiede il contesto Loonex.
                                     */
        
                                    this.referer = data
        
                                    this.headers = mapOf(
                                        "User-Agent" to
                                            (headers["User-Agent"] ?: ""),
                                        "Referer" to data,
                                        "Origin" to mainUrl
                                    )
                                }
                            )
                        println("LOONEX_DEBUG: SUCCESS -> $finalStream")
                println("LOONEX_DEBUG: ===== LOADLINKS END =====")
                            return true
                        }
                    }
        
                } catch (e: Exception) {
                    println("LOONEX_DEBUG: EXCEPTION=${e.javaClass.simpleName}: ${e.message}")
                    e.printStackTrace()
                }
            }
        }

        // =========================================================
        // 3. FALLBACK: Vecchio metodo XOR
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

    private suspend fun findTmdbSeries(rawTitle: String): Int? {
        tmdbSeriesCache[rawTitle]?.let { return it }
        val year = Regex("""\((19|20)\d{2}\)""").find(rawTitle)?.value?.removePrefix("(")?.removeSuffix(")")?.toIntOrNull()
        val cleanTitle = rawTitle.replace(Regex("""\((19|20)\d{2}\)"""), "").replace(Regex("""(?i)\bserie\s+completa\b"""), "").trim()
        val encodedTitle = java.net.URLEncoder.encode(cleanTitle, "UTF-8")

        val searchUrl = buildString {
            append("$tmdbApi/search/tv")
            append("?api_key=$tmdbApiKey")
            append("&query=$encodedTitle")
            append("&language=it-IT")
            if (year != null) append("&first_air_date_year=$year")
        }

        return try {
            val response = app.get(searchUrl, headers = headers)
            val json = response.parsedSafe<TmdbSearchResponse>()
            val id = json?.results?.firstOrNull()?.id
            tmdbSeriesCache[rawTitle] = id
            id
        } catch (_: Exception) {
            tmdbSeriesCache[rawTitle] = null
            null
        }
    }

    private suspend fun getTmdbSeasonStills(tmdbId: Int, season: Int): Map<Int, String> {
        val cacheKey = tmdbId to season
        tmdbSeasonCache[cacheKey]?.let { return it }
        val url = "$tmdbApi/tv/$tmdbId/season/$season?api_key=$tmdbApiKey&language=it-IT"

        return try {
            val response = app.get(url, headers = headers)
            val data = response.parsedSafe<TmdbSeasonResponse>()
            val result = data?.episodes?.mapNotNull { episode ->
                val number = episode.episodeNumber ?: return@mapNotNull null
                val still = episode.stillPath ?: return@mapNotNull null
                number to "$tmdbImageBase$still"
            }?.toMap() ?: emptyMap()
            tmdbSeasonCache[cacheKey] = result
            result
        } catch (_: Exception) {
            tmdbSeasonCache[cacheKey] = emptyMap()
            emptyMap()
        }
    }

    private fun decryptLoonexUrl(hex: String, key: String): String {
        if (key.isBlank()) return ""
        val decoded = buildString {
            var i = 0
            while (i + 1 < hex.length) {
                val value = hex.substring(i, i + 2).toIntOrNull(16) ?: break
                val keyChar = key[(i / 2) % key.length].code
                append((value xor keyChar).toChar())
                i += 2
            }
        }
        return try { URLDecoder.decode(decoded, "UTF-8") } catch (_: Exception) { decoded }
    }

    private fun encodeUrlPath(url: String): String {
        return try {
            val uri = URI(url)
            URI(uri.scheme, uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment).toASCIIString()
        } catch (_: Exception) {
            url.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D")
        }
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return if (url.startsWith("/")) "$mainUrl$url" else "$mainUrl/cartoni/$url"
    }
}
