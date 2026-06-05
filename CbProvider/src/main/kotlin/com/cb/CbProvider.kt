package com.cb

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import org.json.JSONObject
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class CbProvider : MainAPI() {
    override var mainUrl = "https://cb01uno.bar"
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

    private fun fixTitle(title: String, isMovie: Boolean): String {
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


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("CB01", "Caricamento main page: ${request.data} pagina $page")
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url, headers = commonHeaders).document

        val items = document.select("div.card, div.post-video, article.post, div.mp-post, article")
            .mapNotNull { parseElement(it, request.data.contains("serietv")) }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    ///////////////////////
    // SEARCH ////////////
    /////////////////////
    override suspend fun search(query: String): List<SearchResponse> {
    val results = mutableListOf<SearchResponse>()
    
    // Lista dei due endpoint di ricerca separati (Film e Serie TV)
    val searchUrls = listOf(
        "$mainUrl/?s=$query",
        "$mainUrl/serietv/?s=$query"
    )

    for (baseUrl in searchUrls) {
        var currentUrl: String? = baseUrl
        var pageCount = 1
        
        // Limite di salvaguardia per evitare loop infiniti (es. max 5 pagine per sorgente)
        while (currentUrl != null && pageCount <= 5) {
            val response = app.get(currentUrl).text
            val document = Jsoup.parse(response, currentUrl)
            
            // Entrambi i file usano la classe degli articoli standard di WP o i box card
            // Il selettore .card-content o .mp-post intercetta perfettamente i blocchi
            val blocks = document.select(".card-content, .mp-post")
            if (blocks.isEmpty()) break

            blocks.forEach { el ->
                // Passiamo l'URL corrente per capire se siamo nel motore di ricerca delle serie TV
                parseElement(el, currentUrl.contains("/serietv/"))?.let { 
                    results.add(it) 
                }
            }

            // Paginazione robusta per questo specifico tema di CB01
            // Cerca il link che contiene la freccia avanti, "Next", "Avanti" o l'elemento successivo dopo la pagina corrente
            val nextAnchor = document.selectFirst(".pagination a.next, .navigation a.next, .nav-links a.next, a:contains(Successivo), a:contains(Next)")
            
            currentUrl = if (nextAnchor != null) {
                nextAnchor.attr("abs:href")
            } else {
                // Sotto-fallback manuale se il pulsante "Next" non ha classi specifiche ma si trova nella struttura numerica:
                // Se siamo a pagina 1 e sappiamo che ci sono altre pagine, possiamo calcolare l'URL della pagina 2
                val hasPagination = document.selectFirst(".pagination, .navigation, .nav-links") != null
                if (hasPagination && currentUrl == baseUrl) {
                    if (baseUrl.contains("/serietv/")) {
                        "$mainUrl/serietv/page/2/?s=$query"
                    } else {
                        "$mainUrl/page/2/?s=$query"
                    }
                } else if (hasPagination && currentUrl.contains("/page/")) {
                    // Estrae il numero di pagina corrente e lo incrementa
                    val pageRegex = "page/(\\d+)".toRegex()
                    val match = pageRegex.find(currentUrl)
                    if (match != null) {
                        val nextPageNum = match.groupValues[1].toInt() + 1
                        currentUrl.replace("page/${nextPageNum - 1}", "page/$nextPageNum")
                    } else null
                } else null
            }
            
            pageCount++
        }
    }

    // Ritorna i risultati rimuovendo eventuali duplicati per URL
    return results.distinctBy { it.url }
}

private fun parseElement(element: Element, isTvSeriesSearch: Boolean): SearchResponse? {
    // Cerchiamo il tag del titolo all'interno del blocco (.card-title a o semplicemente h3 a / h2 a)
    val titleElement = element.selectFirst(".card-title a, h3 a, h2 a") ?: return null
    val href = titleElement.attr("abs:href")
    val rawTitle = titleElement.text()

    if (href.isEmpty()) return null

    // Verifica accurata basata sia sul contesto della ricerca che sulla struttura dell'URL trovato
    val isSeries = isTvSeriesSearch || 
                   href.contains("/serietv/") || 
                   href.contains("/serie/") ||
                   rawTitle.contains("Stagion", ignoreCase = true) || 
                   rawTitle.contains("Serie TV", ignoreCase = true)

    // Estrazione del poster (gestisce sia src normale che lazy-load da te descritto in precedenza)
    val imgElement = element.selectFirst("img")
    val posterUrl = imgElement?.attr("data-lazyloaded")?.takeIf { it.isNotEmpty() }
        ?: imgElement?.attr("data-src")?.takeIf { it.isNotEmpty() }
        ?: imgElement?.attr("abs:src")

    val cleanTitle = fixTitle(rawTitle)

    return if (isSeries) {
        newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    } else {
        newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }
}

    // ---------------------------------------------------------
    //  PARSER UPROT FOLDER (msfld) → Estrae episodi veri
    // ---------------------------------------------------------
    private suspend fun parseUprotFolder(url: String, season: Int): List<Episode> {
        Log.d("CB01", "Parsing Uprot folder: $url")

        val doc = app.get(url).document
        val eps = mutableListOf<Episode>()

        doc.select("a[href]").forEachIndexed { index, a ->
            val link = a.attr("href")

            if (supportedHosts.any { host -> link.contains(host) }) {
                eps.add(
                    newEpisode(link) {
                        this.season = season
                        this.episode = index + 1
                        this.name = "${season}x${String.format("%02d", index + 1)}"
                    }
                )
            }
        }

        return eps
    }

    // ---------------------------------------------------------
    //  LOAD()
    // ---------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        Log.d("CB01", "Caricamento pagina: $url")
        val document = app.get(url, headers = commonHeaders).document
        val isSeries = url.contains("/serietv/") || url.contains("/serie/")

        val title = fixTitle(document.selectFirst("meta[property=\"og:title\"]")?.attr("content")
            ?: document.selectFirst("h1")?.text() ?: "", !isSeries)

        val poster = document.selectFirst("meta[property=\"og:image\"]")?.attr("content")

        val plot = document.selectFirst("meta[property=\"og:description\"]")?.attr("content")
            ?: document.select("div.ignore-css p, .entry-content p").firstOrNull { it.text().length > 50 }?.text()

        val episodes = mutableListOf<Episode>()

        // ---------------------------------------------------------
        //  FILM
        // ---------------------------------------------------------
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

        // ---------------------------------------------------------
        //  SERIE TV
        // ---------------------------------------------------------
        Log.d("CB01", "Rilevata SERIE TV")
        val seasonsData = mutableListOf<SeasonData>()

        document.select("div.sp-wrap, div.bb-spoiler").forEachIndexed { index, wrap ->
            val seasonHead = wrap.selectFirst(".sp-head")?.text().orEmpty()
            val currentSeason = Regex("\\d+").find(seasonHead)?.value?.toIntOrNull() ?: (index + 1)

            val cleanSeasonName = seasonHead
            .replace(Regex("(?i)ITA|HD|COMPLETA|TUTTA LA SERIE|TUTTI GLI EPISODI"), "")
            .trim()
            
            seasonsData.add(SeasonData(currentSeason, cleanSeasonName.ifBlank { "Stagione $currentSeason" }))


            wrap.select(".sp-body *").forEach { row ->
                val anchors = row.select("a[href]")
                if (anchors.isEmpty()) return@forEach

                val rowText = row.text().trim()

                // ---------------------------------------------------------
                //  CASO SPECIALE: TUTTA LA SERIE → UPROT FOLDER
                // ---------------------------------------------------------
                if (rowText.contains(Regex("(?i)TUTTA LA SERIE|TUTTI GLI EPISODI|INTERA STAGIONE|STAGIONE COMPLETA"))) {
                    val folderLink = anchors.first().attr("href")

                    if (folderLink.contains("uprot.net/msfld")) {
                        val realEpisodes = parseUprotFolder(folderLink, currentSeason)

                        // Assicuriamo che ogni episodio abbia stagione corretta
                        realEpisodes.forEachIndexed { index, ep ->
                            ep.season = currentSeason
                            ep.episode = index + 1
                            ep.name = "${currentSeason}x${String.format("%02d", index + 1)}"
                        }
                         episodes.addAll(realEpisodes)
                    }

                    return@forEach
                }

                // ---------------------------------------------------------
                //  EPISODI NORMALI
                // ---------------------------------------------------------
                val epMatch = Regex("(\\d+)\\s*[x×]\\s*(\\d+)").find(rowText)
                val fallbackMatch = Regex("(?i)(?:Episodio\\s*)?(\\d+)").find(rowText)

                if (epMatch == null && fallbackMatch == null) return@forEach

                val sNum = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: currentSeason
                val eNum = epMatch?.groupValues?.get(2)?.toIntOrNull()
                    ?: fallbackMatch?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@forEach

                val linksForEpisode = anchors.map { it.attr("href") }.filter { link ->
                    supportedHosts.any { host -> link.contains(host) }
                }

                if (linksForEpisode.isNotEmpty()) {
                    episodes.add(
                        newEpisode(linksForEpisode.joinToString("###")) {
                            this.name = "${sNum}x${String.format("%02d", eNum)}"
                            this.season = sNum
                            this.episode = eNum
                        }
                    )
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            addSeasonNames(seasonsData)
        }
    }

    // ---------------------------------------------------------
    //  LOAD LINKS
    // ---------------------------------------------------------
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
                    var bypassed = bypassStayOnline(cleanLink)

                    if (!bypassed.isNullOrBlank()) {
                        if (!bypassed.startsWith("http")) {
                            bypassed = "https://" + bypassed.removePrefix("//")
                        }

                        if (bypassed.contains("uprot")) {
                            val uprotExtractor = Uprot()
                            uprotExtractor.getUrl(bypassed, referer = cleanLink, subtitleCallback, callback)
                        } else {
                            loadExtractor(bypassed, cleanLink, subtitleCallback, callback)
                        }
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
