package com.cb

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class CbProvider : MainAPI() {
    override var mainUrl = "https://cb01uno.bond"
    override var name = "CB01"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    private val supportedHosts = listOf(
        "voe", "mixdrop", "streamtape", "fastream", "filemoon",
        "wolfstream", "streamwish", "maxstream", "lulustream",
        "uprot", "stayonline", "swzz", "supervideo", "vidmoly", "maxsa"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Film Recenti",
        "$mainUrl/serietv/" to "Serie TV Recenti"
    )

    private fun fixTitle(title: String, isMovie: Boolean = false): String {
        var t = title
        val removeList = listOf(
            "streaming", "[HD]", "film gratis by cb01 official",
            "serie tv gratis by cb01 official", "completa", "ITA", "HD",
            "Stagione", "stagione", "Serie", "Episodio", "(", ")"
        )
        removeList.forEach { bad -> t = t.replace(bad, "", ignoreCase = true) }
        t = t.replace("""\s*[-/]\s*$""".toRegex(), "")
        return t.trim()
    }

    private fun parseElement(element: Element, isTvSeriesSearch: Boolean): SearchResponse? {
        val titleElement = element.selectFirst(".card-title a, h3 a, h2 a, .post-title a, a[title]") ?: return null
        val href = titleElement.attr("abs:href").ifBlank { titleElement.attr("href") }
        if (href.contains("/tag/") || href.contains("/category/") || href.length < 15) return null

        val rawTitle = titleElement.text()
        val isSeries = isTvSeriesSearch ||
                href.contains("/serietv/") ||
                href.contains("/serie/") ||
                rawTitle.contains("Stagion", ignoreCase = true) ||
                rawTitle.contains("Serie TV", ignoreCase = true)

        val title = fixTitle(rawTitle, !isSeries)

        val imgElement = element.selectFirst("img")
        val posterUrl = imgElement?.attr("data-lazyloaded")?.takeIf { it.isNotEmpty() }
            ?: imgElement?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: imgElement?.attr("abs:src")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("CB01", "Caricamento main page: ${request.data} pagina $page")
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url, headers = commonHeaders).document

        val items = document.select("div.card, div.post-video, article.post, div.mp-post, article")
            .mapNotNull { parseElement(it, request.data.contains("serietv")) }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("CB01", "Ricerca multipagina unificata: $query")
        val results = mutableListOf<SearchResponse>()

        val searchUrls = listOf(
            "$mainUrl/?s=$query",
            "$mainUrl/serietv/?s=$query"
        )

        for (baseUrl in searchUrls) {
            var currentUrl: String? = baseUrl
            var pageCount = 1

            while (currentUrl != null && pageCount <= 5) {
                Log.d("CB01", "Scansione Search URL: $currentUrl")
                val response = app.get(currentUrl, headers = commonHeaders).text
                val document = Jsoup.parse(response, currentUrl)

                val blocks = document.select("article, div.card, div.post-video, .result-item, .post, .mp-post, .entry, .card-content")
                if (blocks.isEmpty()) break

                blocks.forEach { el ->
                    parseElement(el, currentUrl!!.contains("/serietv/"))?.let {
                        results.add(it)
                    }
                }

                val nextAnchor = document.selectFirst(".pagination a.next, .navigation a.next, .nav-links a.next, a:contains(Successivo), a:contains(Next)")

                currentUrl = if (nextAnchor != null) {
                    nextAnchor.attr("abs:href")
                } else {
                    val hasPagination = document.selectFirst(".pagination, .navigation, .nav-links") != null
                    if (hasPagination && currentUrl == baseUrl) {
                        if (baseUrl.contains("/serietv/")) {
                            "$mainUrl/serietv/page/2/?s=$query"
                        } else {
                            "$mainUrl/page/2/?s=$query"
                        }
                    } else if (hasPagination && currentUrl!!.contains("/page/")) {
                        val pageRegex = "page/(\\d+)".toRegex()
                        val match = pageRegex.find(currentUrl!!)
                        if (match != null) {
                            val nextPageNum = match.groupValues[1].toInt() + 1
                            currentUrl!!.replace("page/${nextPageNum - 1}", "page/$nextPageNum")
                        } else null
                    } else null
                }

                pageCount++
            }
        }

        return results.distinctBy { it.url }
    }

    private suspend fun parseUprotFolder(
    url: String,
    requestedSeason: Int
): List<Episode> {

    Log.d(
        "CB01",
        "Parsing Uprot folder: $url - stagione richiesta: $requestedSeason"
    )

    return try {

        val response = app.get(
            url,
            headers = commonHeaders
        ).text

        val doc = Jsoup.parse(response, url)

        val rows = doc.select("table tr")

        if (rows.isEmpty()) {
            Log.d("CB01:UprotFolder", "Nessuna riga trovata nella cartella")
            return emptyList()
        }

        // Prima controlliamo se la cartella contiene più stagioni.
        val seasonsInFolder =
            rows.mapNotNull { row ->

                val fileName =
                    row.selectFirst("td")
                        ?.text()
                        ?.trim()
                        .orEmpty()

                Regex(
                    """(?i)S(\d{1,2})E\d{1,3}"""
                )
                    .find(fileName)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            }
                .distinct()

        val hasMultipleSeasons =
            seasonsInFolder.size > 1

        Log.d(
            "CB01:UprotFolder",
            "Stagioni presenti nella cartella = $seasonsInFolder"
        )

        val episodes = mutableListOf<Episode>()

        rows.forEachIndexed { index, row ->

            val fileName =
                row.selectFirst("td")
                    ?.text()
                    ?.trim()
                    .orEmpty()

            if (fileName.isBlank()) {
                return@forEachIndexed
            }

            val watchUrl =
                row.selectFirst(
                    "a[href*='/msfi/'], a[href*='/mse/'], a[href*='/msf/']"
                )
                    ?.attr("abs:href")
                    ?.ifBlank {
                        row.selectFirst(
                            "a[href*='/msfi/'], a[href*='/mse/'], a[href*='/msf/']"
                        )
                            ?.attr("href")
                            .orEmpty()
                    }
                    .orEmpty()

            if (watchUrl.isBlank()) {
                return@forEachIndexed
            }

            /*
             * Formati che vogliamo riconoscere:
             *
             * S01E01
             * S1E1
             * S02E105
             */
            val seasonEpisodeMatch =
                Regex(
                    """(?i)S(\d{1,2})E(\d{1,3})"""
                ).find(fileName)

            val detectedSeason: Int?
            val episodeNumber: Int

            if (seasonEpisodeMatch != null) {

                detectedSeason =
                    seasonEpisodeMatch
                        .groupValues
                        .getOrNull(1)
                        ?.toIntOrNull()

                episodeNumber =
                    seasonEpisodeMatch
                        .groupValues
                        .getOrNull(2)
                        ?.toIntOrNull()
                        ?: (index + 1)

                /*
                 * Se nella stessa cartella ci sono S01, S02, S03...
                 * mostriamo soltanto gli episodi della stagione selezionata.
                 */
                if (
                    hasMultipleSeasons &&
                    detectedSeason != null &&
                    detectedSeason != requestedSeason
                ) {
                    return@forEachIndexed
                }

            } else {

                detectedSeason = null

                /*
                 * Fallback per nomi meno standard.
                 *
                 * Esempi:
                 * 01.mkv
                 * Episodio 02
                 * E03
                 */
                episodeNumber =
                    Regex(
                        """(?i)(?:episodio|episode|ep|e)\s*0*(\d{1,3})"""
                    )
                        .find(fileName)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

                        ?: Regex(
                            """(?:^|[.\s_-])0*(\d{1,3})(?:[.\s_-]|$)"""
                        )
                            .find(fileName)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()

                        ?: (index + 1)
            }

            val cleanName =
                fileName
                    .replace(".mp4", "", ignoreCase = true)
                    .replace(".mkv", "", ignoreCase = true)
                    .replace(".avi", "", ignoreCase = true)
                    .replace("_", " ")
                    .replace(".", " ")
                    .trim()

            val finalSeason =
                detectedSeason ?: requestedSeason

            Log.d(
                "CB01:UprotFolder",
                "EP trovato → S${finalSeason}E${episodeNumber} | $watchUrl"
            )

            episodes.add(
                newEpisode(watchUrl) {

                    this.season = finalSeason
                    this.episode = episodeNumber

                    this.name =
                        cleanName.ifBlank {
                            "S${String.format("%02d", finalSeason)}E${
                                String.format("%02d", episodeNumber)
                            }"
                        }
                }
            )
        }

        episodes
            .distinctBy {
                Triple(
                    it.season,
                    it.episode,
                    it.data
                )
            }
            .sortedWith(
                compareBy<Episode> {
                    it.season ?: requestedSeason
                }.thenBy {
                    it.episode ?: 0
                }
            )

    } catch (e: Exception) {

        Log.e(
            "CB01:UprotFolder",
            "Errore parsing Uprot folder: ${e.message}"
        )

        emptyList()
    }
}
    override suspend fun load(url: String): LoadResponse {
        Log.d("CB01", "Caricamento pagina: $url")
        val document = app.get(url, headers = commonHeaders).document
        val isSeries = url.contains("/serietv/") || url.contains("/serie/")

        val title = fixTitle(
            document.selectFirst("meta[property=\"og:title\"]")?.attr("content")
                ?: document.selectFirst("h1")?.text() ?: "",
            !isSeries
        )

        val poster = document.selectFirst("meta[property=\"og:image\"]")?.attr("content")

        val plot = document.selectFirst("meta[property=\"og:description\"]")?.attr("content")
            ?: document.select("div.ignore-css p, .entry-content p").firstOrNull { it.text().length > 50 }?.text()

        val episodes = mutableListOf<Episode>()

        // ============================
        //          FILM
        // ============================
        if (!isSeries) {
            Log.d("CB01", "Rilevato FILM")
            val links = document.select("table a, a.buttona_stream, .stream-link, iframe")
                .map { it.attr("href").ifBlank { it.attr("src") } }
                .filter { link -> supportedHosts.any { link.contains(it) } }

            if (links.isNotEmpty()) {
                episodes.add(
                    newEpisode(links.joinToString("###")) {
                        this.name = "Film - Streaming"
                    }
                )
            }

            return newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: "") {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        // ============================
        //          SERIE TV
        // ============================
        Log.d("CB01", "Rilevata SERIE TV")
        val seasonsData = mutableListOf<SeasonData>()

        // Struttura classica sp-wrap / bb-spoiler
        document.select("div.sp-wrap, div.bb-spoiler").forEachIndexed { index, wrap ->
            val seasonHead = wrap.selectFirst(".sp-head")?.text().orEmpty()
            val currentSeason = Regex("\\d+").find(seasonHead)?.value?.toIntOrNull() ?: (index + 1)

            val seasonNameClean = seasonHead
                .replace("- ITA", "", ignoreCase = true)
                .replace("- HD", "", ignoreCase = true)
                .trim()

            seasonsData.add(SeasonData(currentSeason, seasonNameClean))

            wrap.select(".sp-body *").forEach { row ->
                val anchors = row.select("a[href]")
                if (anchors.isEmpty()) return@forEach

                val rowText = row.text().trim()
                if (rowText.isBlank() || rowText.contains("[riduci]", ignoreCase = true)) return@forEach

                if (
                    rowText.contains(
                        Regex(
                            "(?i)TUTTA LA SERIE|TUTTI GLI EPISODI|INTERA STAGIONE|STAGIONE COMPLETA"
                        )
                    )
                ) {
                
                    Log.d(
                        "CB01",
                        "Possibile cartella stagione completa trovata: $rowText"
                    )
                
                    // Cerchiamo prima una vera cartella Uprot /msfld/
                    val uprotFolder =
                        anchors
                            .map { anchor ->
                                anchor.attr("abs:href")
                                    .ifBlank { anchor.attr("href") }
                            }
                            .firstOrNull { link ->
                                link.contains(
                                    "/msfld/",
                                    ignoreCase = true
                                )
                            }
                
                    if (!uprotFolder.isNullOrBlank()) {
                
                        Log.d(
                            "CB01",
                            "Cartella Uprot trovata: $uprotFolder"
                        )
                
                        val folderEpisodes =
                            parseUprotFolder(
                                uprotFolder,
                                currentSeason
                            )
                
                        Log.d(
                            "CB01",
                            "Episodi estratti dalla cartella Uprot: ${folderEpisodes.size}"
                        )
                
                        folderEpisodes.forEach { episode ->
                
                            val alreadyExists =
                                episodes.any {
                                    it.season == episode.season &&
                                    it.episode == episode.episode
                                }
                
                            if (!alreadyExists) {
                                episodes.add(episode)
                            }
                        }
                
                        return@forEach
                    }
                
                    /*
                     * Se NON è una cartella /msfld/, manteniamo
                     * il comportamento precedente come fallback.
                     */
                    val folderLinks =
                        anchors
                            .map { anchor ->
                                anchor.attr("abs:href")
                                    .ifBlank { anchor.attr("href") }
                            }
                            .filter { link ->
                                supportedHosts.any { host ->
                                    link.contains(
                                        host,
                                        ignoreCase = true
                                    )
                                }
                            }
                
                    if (folderLinks.isNotEmpty()) {
                
                        val linksData =
                            folderLinks.joinToString("###")
                
                        if (episodes.none { it.data == linksData }) {
                
                            episodes.add(
                                newEpisode(linksData) {
                                    this.name = "Stagione Completa"
                                    this.season = currentSeason
                                    this.episode = 1
                                }
                            )
                        }
                    }
                
                    return@forEach
                }

                val epMatch = Regex("(\\d+)\\s*[x×\\u00D7]\\s*(\\d+)").find(rowText)
                val fallbackMatch = Regex("(?i)(?:Episodio\\s*)?(\\d+)").find(rowText)

                if (epMatch == null && fallbackMatch == null) return@forEach

                val sNum = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: currentSeason
                val eNum = epMatch?.groupValues?.get(2)?.toIntOrNull()
                    ?: fallbackMatch?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@forEach

                val baseEpName = "${sNum}x${String.format("%02d", eNum)}"

                val linksForEpisode = anchors.map { it.attr("href") }.filter { link ->
                    supportedHosts.any { host -> link.contains(host) }
                }

                if (linksForEpisode.isNotEmpty()) {
                    val linksData = linksForEpisode.joinToString("###")
                    val isDuplicate = episodes.any { it.season == sNum && it.episode == eNum }

                    if (!isDuplicate) {
                        episodes.add(
                            newEpisode(linksData) {
                                this.name = baseEpName
                                this.season = sNum
                                this.episode = eNum
                            }
                        )
                    }
                }
            }
        }

        // ============================
        //  SERIE TV — STRUTTURA season-list
        // ============================
        val seasonBlocks = document.select("div.season-list div.season")
        if (seasonBlocks.isNotEmpty()) {
            Log.d("CB01", "Rilevata struttura season-list → parsing alternativo")
            seasonBlocks.forEach { seasonBlock ->
                val seasonTitle = seasonBlock.selectFirst("h3")?.text()?.trim().orEmpty()
                val seasonNum = Regex("(\\d+)").find(seasonTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                seasonsData.add(SeasonData(seasonNum, seasonTitle))

                val episodeItems = seasonBlock.select("ul.episode-list li a[href]")
                episodeItems.forEachIndexed { index, ep ->
                    val epUrl = ep.attr("href")
                    val epName = ep.text().trim()
                    val epNum = Regex("(\\d+)").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)

                    if (supportedHosts.any { host -> epUrl.contains(host) }) {
                        episodes.add(
                            newEpisode(epUrl) {
                                this.name = "S${seasonNum}E${epNum}"
                                this.season = seasonNum
                                this.episode = epNum
                                this.data = epUrl
                            }
                        )
                    }
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            addSeasonNames(seasonsData)
        }
    }

    private suspend fun resolveMaxstreamAdvanced(url: String): String? {
        return try {
            val doc = app.get(url).document
            val html = doc.html()

            val b64Base = Regex("""decodedBaseUrl\s*=\s*atob\(["']([^"']+)["']\)""")
                .find(html)?.groupValues?.getOrNull(1)

            val b64Val = Regex("""decodedEncryptedVal\s*=\s*atob\(["']([^"']+)["']\)""")
                .find(html)?.groupValues?.getOrNull(1)

            if (!b64Base.isNullOrBlank() && !b64Val.isNullOrBlank()) {
                val decodedBase = String(Base64.decode(b64Base, Base64.DEFAULT))
                val decodedVal = String(Base64.decode(b64Val, Base64.DEFAULT))
                val finalUrl = decodedBase + decodedVal
                if (finalUrl.isNotBlank()) return finalUrl
            }

            null
        } catch (e: Exception) {
            Log.e("CB01:MaxstreamAdvanced", "Errore nella decodifica avanzata Maxstream: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("CB01", "loadLinks() chiamato con data: $data")

        val allLinks = data.split("###")
            .map { it.trim() }
            .filter { it.isNotBlank() && it.startsWith("http") }
            .sortedBy { it.contains("stayonline.pro") }

        allLinks.forEach { cleanLink ->
            try {
                if (cleanLink.contains("stayonline.pro")) {
                    Log.d("CB01", "StayOnline rilevato → bypass in corso per $cleanLink")
                    var bypassed = bypassStayOnline(cleanLink)

                    if (!bypassed.isNullOrBlank()) {
                        if (!bypassed.startsWith("http")) {
                            bypassed = "https://" + bypassed.removePrefix("//")
                        }
                        Log.d("CB01", "StayOnline sbloccato! Link reale estratto: $bypassed")

                        if (bypassed.contains("uprot")) {
                            Log.d("CB01", "Il link sbloccato è un host Uprot. Avvio decodifica avanzata Maxstream...")
                            val advanced = resolveMaxstreamAdvanced(bypassed)

                            if (!advanced.isNullOrBlank()) {
                                Log.d("CB01", "Decodifica avanzata Maxstream riuscita: $advanced")
                                loadExtractor(advanced, cleanLink, subtitleCallback, callback)
                            } else {
                                Log.d("CB01", "Decodifica avanzata fallita, uso UprotExtractor")
                                val uprotExtractor = Uprot()
                                uprotExtractor.getUrl(bypassed, referer = cleanLink, subtitleCallback, callback)
                            }
                        } else {
                            loadExtractor(bypassed, cleanLink, subtitleCallback, callback)
                        }
                    }
                } else if (cleanLink.contains("uprot")) {
                    Log.d("CB01", "Link Uprot diretto rilevato: $cleanLink → provo decodifica avanzata Maxstream")
                    val advanced = resolveMaxstreamAdvanced(cleanLink)

                    if (!advanced.isNullOrBlank()) {
                        Log.d("CB01", "Decodifica avanzata Maxstream riuscita (direct): $advanced")
                        loadExtractor(advanced, cleanLink, subtitleCallback, callback)
                    } else {
                        Log.d("CB01", "Decodifica avanzata fallita (direct), uso UprotExtractor")
                        val uprotExtractor = Uprot()
                        uprotExtractor.getUrl(cleanLink, referer = cleanLink, subtitleCallback, callback)
                    }
                } else {
                    loadExtractor(cleanLink, cleanLink, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e("CB01", "Errore nell'estrazione del link $cleanLink: ${e.message}")
            }
        }
        return true
    }

    private suspend fun bypassStayOnline(link: String): String? {
        return try {
            val cleanUrl = link.substringBefore("?")
            val urlParts = cleanUrl.removeSuffix("/").split("/")
            val linkId = urlParts.lastOrNull { it.isNotBlank() } ?: return null

            val ajaxEndpoint = if (link.contains("/e/")) {
                "https://stayonline.pro/ajax/linkEmbedView.php"
            } else {
                "https://stayonline.pro/ajax/linkView.php"
            }

            val headers = mapOf(
                "Origin" to "https://stayonline.pro",
                "Referer" to link,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
            )

            val pageResponse = app.get(link, headers = headers)
            val cookies = pageResponse.cookies

            val response = app.post(
                ajaxEndpoint,
                headers = headers,
                cookies = cookies,
                data = mapOf("id" to linkId, "ref" to "")
            ).text

            val json = JSONObject(response)
            if (json.optString("status") == "success") {
                var realUrl = json.getJSONObject("data").getString("value")

                if (realUrl.contains("m1xdrop.net/f/") || realUrl.contains("mixdrop.co/f/")) {
                    val videoId = realUrl.removeSuffix("/").substringAfterLast("/")
                    realUrl = "https://mixdrop.top/e/$videoId"
                }
                return realUrl
            }
            null
        } catch (e: Exception) {
            Log.e("CB01:StayOnline", "Errore critico durante il bypass: ${e.message}")
            null
        }
    }
}
