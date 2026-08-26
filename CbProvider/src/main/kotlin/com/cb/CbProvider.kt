package com.cb

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class CbProvider : MainAPI() {
    override var mainUrl = "https://cb01uno.blog"
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

       val items = document
            .select("div.card, div.post-video, article.post, div.mp-post, article")
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

               val blocks =
                document.select(
                    "article, div.card, div.post-video, .result-item, .post, .mp-post, .entry, .card-content"
                )
                if (blocks.isEmpty()) break

                blocks.forEach { el ->
                    parseElement(el, currentUrl!!.contains("/serietv/"))?.let {
                        results.add(it)
                    }
                }

               val nextAnchor =
    document.selectFirst(
        ".pagination a.next, .navigation a.next, .nav-links a.next, " +
            "a:contains(Successivo), a:contains(Next)"
    )

currentUrl =
    if (nextAnchor != null) {

        nextAnchor.attr("abs:href")

    } else {

        val hasPagination =
            document.selectFirst(
                ".pagination, .navigation, .nav-links"
            ) != null

        if (hasPagination && currentUrl == baseUrl) {

            if (baseUrl.contains("/serietv/")) {
                "$mainUrl/serietv/page/2/?s=$query"
            } else {
                "$mainUrl/page/2/?s=$query"
            }

        } else if (
            hasPagination &&
            currentUrl!!.contains("/page/")
        ) {

            val pageRegex =
                "page/(\\d+)".toRegex()

            val match =
                pageRegex.find(currentUrl!!)

            if (match != null) {

                val nextPageNum =
                    match.groupValues[1]
                        .toInt() + 1

                currentUrl!!.replace(
                    "page/${nextPageNum - 1}",
                    "page/$nextPageNum"
                )

            } else {
                null
            }

        } else {
            null
        }
    }

        pageCount++
            }
        }

        return results.distinctBy { it.url }
    }

       private suspend fun parseUprotFolder(
        url: String,
        cloudSeason: Int
    ): List<Episode> {
    
        Log.d(
            "CB01:UprotFolder",
            "Parsing Uprot folder: $url - blocco CloudStream: $cloudSeason"
        )
    
        data class FolderEpisode(
            val originalSeason: Int,
            val originalEpisode: Int,
            val fileName: String,
            val url: String
        )
    
        return try {
    
            val response =
                app.get(
                    url,
                    headers = commonHeaders
                ).text
    
            val doc =
                Jsoup.parse(
                    response,
                    url
                )
    
            val rows =
                doc.select(
                    "table tr"
                )
    
            if (rows.isEmpty()) {
    
                Log.d(
                    "CB01:UprotFolder",
                    "Nessuna riga trovata nella cartella"
                )
    
                return emptyList()
            }
    
            val parsedEpisodes =
                rows.mapIndexedNotNull { index, row ->
    
                    val fileName =
                        row.selectFirst("td")
                            ?.text()
                            ?.trim()
                            .orEmpty()
    
                    if (fileName.isBlank()) {
                        return@mapIndexedNotNull null
                    }
    
                    val linkElement =
                        row.selectFirst(
                            "a[href*='/msfi/'], " +
                                "a[href*='/mse/'], " +
                                "a[href*='/msf/']"
                        )
                            ?: row.selectFirst(
                                "a[href]"
                            )
    
                    val watchUrl =
                        linkElement
                            ?.attr("abs:href")
                            ?.ifBlank {
                                linkElement.attr("href")
                            }
                            .orEmpty()
    
                    if (watchUrl.isBlank()) {
                        return@mapIndexedNotNull null
                    }
    
                    val seasonEpisodeMatch =
                        Regex(
                            """(?i)S(\d{1,2})E(\d{1,3})"""
                        )
                            .find(
                                fileName
                            )
    
                    val originalSeason =
                        seasonEpisodeMatch
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                            ?: 1
    
                    val originalEpisode =
                        seasonEpisodeMatch
                            ?.groupValues
                            ?.getOrNull(2)
                            ?.toIntOrNull()
    
                            ?: Regex(
                                """(?i)(?:episodio|episode|ep|e)\s*0*(\d{1,3})"""
                            )
                                .find(fileName)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.toIntOrNull()
    
                            ?: (index + 1)
    
                    Log.d(
                        "CB01:UprotFolder",
                        "File Uprot → " +
                            "S${originalSeason}E${originalEpisode} | " +
                            fileName
                    )
    
                    FolderEpisode(
                        originalSeason =
                            originalSeason,
    
                        originalEpisode =
                            originalEpisode,
    
                        fileName =
                            fileName,
    
                        url =
                            watchUrl
                    )
                }
    
            val sortedEpisodes =
                parsedEpisodes
                    .distinctBy {
                        Triple(
                            it.originalSeason,
                            it.originalEpisode,
                            it.url
                        )
                    }
                    .sortedWith(
                        compareBy<FolderEpisode> {
                            it.originalSeason
                        }
                            .thenBy {
                                it.originalEpisode
                            }
                    )
    
            /*
             * IMPORTANTE:
             *
             * Il blocco CB01 continua ad essere una singola
             * "stagione CloudStream", perché alcune pagine CB01
             * raccolgono serie differenti nello stesso articolo.
             *
             * Però NON perdiamo più la numerazione originale:
             * la conserviamo nel nome.
             */
            sortedEpisodes.mapIndexed { index, item ->
    
                val cloudEpisodeNumber =
                    index + 1
    
                val cleanFileName =
                    item.fileName
                        .replace(
                            Regex(
                                """(?i)\.(mp4|mkv|avi)$"""
                            ),
                            ""
                        )
                        .replace("_", " ")
                        .replace(".", " ")
                        .replace(
                            Regex("\\s+"),
                            " "
                        )
                        .trim()
    
                val displayName =
                    "S%02dE%02d - %s".format(
                        item.originalSeason,
                        item.originalEpisode,
                        cleanFileName
                    )
    
                Log.d(
                    "CB01:UprotFolder",
                    "CloudStream → " +
                        "blocco S${cloudSeason} " +
                        "E${cloudEpisodeNumber} | " +
                        "originale S${item.originalSeason}" +
                        "E${item.originalEpisode}"
                )
    
                newEpisode(
                    item.url
                ) {
    
                    this.season =
                        cloudSeason
    
                    this.episode =
                        cloudEpisodeNumber
    
                    this.name =
                        displayName
                }
            }
    
        } catch (e: Exception) {
    
            Log.e(
                "CB01:UprotFolder",
                "Errore parsing Uprot folder: ${e.message}",
                e
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
        // Struttura classica sp-wrap / bb-spoiler
document
    .select("div.sp-wrap, div.bb-spoiler")
    .forEachIndexed { index, wrap ->

        val seasonHead =
            wrap.selectFirst(".sp-head")
                ?.text()
                ?.trim()
                .orEmpty()

        if (seasonHead.isBlank()) {
            return@forEachIndexed
        }

        /*
         * Ogni blocco CB01 diventa una stagione CloudStream.
         *
         * Esempio:
         *
         * 1 = Scooby-Doo! Dove sei tu?
         * 2 = Speciale Scooby
         * 3 = The Scooby-Doo Show
         * 4 = Scooby-Doo and Scrappy-Doo
         * ecc.
         */
        val currentSeason = index + 1

        val seasonNameClean =
            seasonHead
                .replace(
                    Regex(
                        """\s*-\s*ITA\s*$""",
                        RegexOption.IGNORE_CASE
                    ),
                    ""
                )
                .replace(
                    Regex(
                        """\s*-\s*HD\s*$""",
                        RegexOption.IGNORE_CASE
                    ),
                    ""
                )
                .trim()

        Log.d(
            "CB01",
            "Blocco CB01 → stagione CloudStream $currentSeason: $seasonNameClean"
        )

        seasonsData.add(
            SeasonData(
                currentSeason,
                seasonNameClean
            )
        )

        /*
         * Prima cerchiamo direttamente una cartella Uprot /msfld/
         * dentro tutto lo sp-body.
         */
        val directUprotFolder =
            wrap.selectFirst(
                ".sp-body a[href*='/msfld/']"
            )
                ?.let { anchor ->
                    anchor.attr("abs:href")
                        .ifBlank {
                            anchor.attr("href")
                        }
                }

        if (!directUprotFolder.isNullOrBlank()) {

            Log.d(
                "CB01",
                "Cartella Uprot trovata per stagione CloudStream " +
                    "$currentSeason: $directUprotFolder"
            )

            val folderEpisodes =
                parseUprotFolder(
                    directUprotFolder,
                    currentSeason
                )

            Log.d(
                "CB01",
                "Episodi Uprot estratti: ${folderEpisodes.size}"
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

            /*
             * Se la cartella /msfld/ è stata trovata,
             * non serve riprocessare le righe normali
             * dello stesso blocco.
             */
            return@forEachIndexed
        }

        /*
         * Se NON c'è /msfld/, utilizziamo
         * il parsing classico degli episodi.
         */
        /*
 * ============================================================
 * EPISODI PRESENTI DIRETTAMENTE NELLA PAGINA CB01
 * ============================================================
 *
 * NON iteriamo più ".sp-body *":
 * un elemento padre può contenere 10 episodi e quindi
 * faceva finire tutti i mirror dentro la stessa puntata.
 */

        val body =
            wrap.selectFirst(
                ".sp-body"
            )
        
        if (body != null) {
        
            data class InlineEpisode(
                val episode: Int,
                val links: MutableList<String>
            )
        
            val grouped =
                linkedMapOf<Int, InlineEpisode>()
        
            /*
             * Analizziamo ciascun link separatamente.
             */
            body.select(
                "a[href]"
            )
                .forEach { anchor ->
        
                    val link =
                        anchor.attr(
                            "abs:href"
                        )
                            .ifBlank {
                                anchor.attr(
                                    "href"
                                )
                            }
                            .trim()
        
                    if (link.isBlank()) {
                        return@forEach
                    }
        
                    if (
                        supportedHosts.none { host ->
        
                            link.contains(
                                host,
                                ignoreCase = true
                            )
                        }
                    ) {
                        return@forEach
                    }
        
                    /*
                     * Risaliamo dal link fino a trovare
                     * il contenitore più piccolo che contiene
                     * la numerazione dell'episodio.
                     */
                    var current:
                        Element? =
                        anchor
        
                    var episodeNumber:
                        Int? =
                        null
        
                    var episodeText =
                        ""
        
                    while (
                        current != null &&
                        current != body
                    ) {
        
                        val text =
                            current.text()
                                .trim()
        
                        /*
                         * Supporta:
                         *
                         * 1x01
                         * 1×01
                         * 1x01.1
                         * 1x01.2
                         * Episodio 1
                         */
                        val xMatch =
                            Regex(
                                """(?i)(\d+)\s*[x×]\s*0*(\d+)(?:\.\d+)?"""
                            )
                                .find(
                                    text
                                )
        
                        val episodeMatch =
                            Regex(
                                """(?i)(?:episodio|episode|ep)\s*0*(\d+)"""
                            )
                                .find(
                                    text
                                )
        
                        val found =
                            xMatch
                                ?.groupValues
                                ?.getOrNull(2)
                                ?.toIntOrNull()
        
                                ?: episodeMatch
                                    ?.groupValues
                                    ?.getOrNull(1)
                                    ?.toIntOrNull()
        
                        if (found != null) {
        
                            episodeNumber =
                                found
        
                            episodeText =
                                text
        
                            break
                        }
        
                        current =
                            current.parent()
                    }
        
                    if (episodeNumber == null) {
        
                        Log.d(
                            "CB01:SERIES",
                            "Link senza episodio riconoscibile: $link"
                        )
        
                        return@forEach
                    }
        
                    Log.d(
                        "CB01:SERIES",
                        "INLINE → " +
                            "S$currentSeason " +
                            "E$episodeNumber | " +
                            "$episodeText | $link"
                    )
        
                    val entry =
                        grouped.getOrPut(
                            episodeNumber!!
                        ) {
                            InlineEpisode(
                                episode =
                                    episodeNumber!!,
        
                                links =
                                    mutableListOf()
                            )
                        }
        
                    if (
                        link !in
                        entry.links
                    ) {
                        entry.links.add(
                            link
                        )
                    }
                }
        
            grouped
                .values
                .sortedBy {
                    it.episode
                }
                .forEach { item ->
        
                    if (
                        item.links.isEmpty()
                    ) {
                        return@forEach
                    }
        
                    val linksData =
                        item.links
                            .distinct()
                            .joinToString(
                                "###"
                            )
        
                    val alreadyExists =
                        episodes.any {
                            it.season ==
                                currentSeason &&
                                it.episode ==
                                item.episode
                        }
        
                    if (!alreadyExists) {
        
                        Log.d(
                            "CB01:SERIES",
                            "CREO EPISODIO " +
                                "S$currentSeason" +
                                "E${item.episode} " +
                                "mirror=${item.links.size}"
                        )
        
                        episodes.add(
                            newEpisode(
                                linksData
                            ) {
        
                                this.name =
                                    "%02dx%02d".format(
                                        currentSeason,
                                        item.episode
                                    )
        
                                this.season =
                                    currentSeason
        
                                this.episode =
                                    item.episode
                            }
                        )
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

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {

    Log.d(
        "CB01",
        "loadLinks() chiamato con data: $data"
    )

    val allLinks =
        data.split("###")
            .map { it.trim() }
            .filter {
                it.isNotBlank() &&
                it.startsWith("http")
            }
            .sortedBy {
                it.contains(
                    "stayonline.pro",
                    ignoreCase = true
                )
            }

    allLinks.forEach { cleanLink ->

        try {

            when {

                cleanLink.contains(
                    "stayonline.pro",
                    ignoreCase = true
                ) -> {

                    Log.d(
                        "CB01",
                        "StayOnline rilevato: $cleanLink"
                    )

                    var bypassed =
                        bypassStayOnline(cleanLink)

                    if (!bypassed.isNullOrBlank()) {

                        if (!bypassed.startsWith("http")) {
                            bypassed =
                                "https://" +
                                    bypassed.removePrefix("//")
                        }

                        Log.d(
                            "CB01",
                            "StayOnline sbloccato: $bypassed"
                        )

                        if (
                            bypassed.contains(
                                "uprot",
                                ignoreCase = true
                            )
                        ) {

                            Log.d(
                                "CB01",
                                "StayOnline → Uprot"
                            )

                            Uprot().getUrl(
                                bypassed,
                                cleanLink,
                                subtitleCallback,
                                callback
                            )

                        } else {

                            loadExtractor(
                                bypassed,
                                cleanLink,
                                subtitleCallback,
                                callback
                            )
                        }
                    }
                }

                cleanLink.contains(
                    "uprot",
                    ignoreCase = true
                ) -> {

                    Log.d(
                        "CB01",
                        "Uprot diretto: $cleanLink"
                    )

                    Uprot().getUrl(
                        cleanLink,
                        mainUrl,
                        subtitleCallback,
                        callback
                    )
                }

                else -> {

                    loadExtractor(
                        cleanLink,
                        mainUrl,
                        subtitleCallback,
                        callback
                    )
                }
            }

        } catch (e: Exception) {

            Log.e(
                "CB01",
                "Errore estrazione $cleanLink: ${e.message}"
            )
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
