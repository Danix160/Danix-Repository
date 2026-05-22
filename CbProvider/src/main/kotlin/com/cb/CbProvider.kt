package com.cb

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import org.json.JSONObject
import org.jsoup.nodes.Element
import org.jsoup.Jsoup

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
        
        // Rimuove eventuali trattini, barre o spazi residui appesi alla fine del titolo
        t = t.replace("""\s*[-/]\s*$""".toRegex(), "")
        return t.trim()
    }

    private fun parseElement(element: Element, isTvSeriesSearch: Boolean = false): SearchResponse? {
        val titleElement = element.selectFirst("h2 a, h3 a, .card-title a, .post-title a, a[title]") ?: return null
        val href = titleElement.attr("abs:href").ifBlank { titleElement.attr("href") }
        if (href.contains("/tag/") || href.contains("/category/") || href.length < 15) return null

        val rawTitle = titleElement.text()
        val isSeries = isTvSeriesSearch ||
                href.contains("/serietv/") ||
                href.contains("/serie/") ||
                rawTitle.contains("Stagion", ignoreCase = true) ||
                rawTitle.contains("Serie", ignoreCase = true) ||
                rawTitle.contains("Episodio", ignoreCase = true)

        val title = fixTitle(rawTitle, !isSeries)
        
        // Estrazione sicura usando URL assoluti per il lazy-loading delle immagini
        val posterUrl = element.selectFirst("img")?.let { img ->
            img.attr("abs:data-lazy-src").ifBlank {
                img.attr("abs:data-src").ifBlank { img.attr("abs:src") }
            }
        }

        return newMovieSearchResponse(title, href, if (isSeries) TvType.TvSeries else TvType.Movie) {
            this.posterUrl = posterUrl
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
        Log.d("CB01", "Ricerca: $query")
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl, headers = commonHeaders).document

        // Restringiamo il campo di ricerca per evitare i widget laterali di WordPress
        return document.select("div.search-results article, #main article, div.card, div.post-video, .result-item")
            .mapNotNull { parseElement(it) }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d("CB01", "Caricamento pagina: $url")
        val document = app.get(url, headers = commonHeaders).document
        val isSeries = url.contains("/serietv/") || url.contains("/serie/")

        // 1. Estrazione metadati corazzata tramite Open Graph (immune a variazioni del tema HTML)
        val title = fixTitle(document.selectFirst("meta[property=\"og:title\"]")?.attr("content") 
            ?: document.selectFirst("h1")?.text() ?: "", !isSeries)
        
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

        // Target accurato basato sul plugin 'bbspoiler' strutturato nell'HTML reale
        document.select("div.sp-wrap, div.bb-spoiler").forEachIndexed { index, wrap ->
            val seasonHead = wrap.selectFirst(".sp-head")?.text().orEmpty()
            
            // Determina l'indice di sicurezza della stagione dallo spoiler header
            val currentSeason = Regex("\\d+").find(seasonHead)?.value?.toIntOrNull() ?: (index + 1)

            val seasonNameClean = seasonHead
                .replace("- ITA", "", ignoreCase = true)
                .replace("- HD", "", ignoreCase = true)
                .trim()

            seasonsData.add(SeasonData(currentSeason, seasonNameClean))

            // Iterazione resiliente: leggiamo linearmente tutti i nodi interni all'elemento sp-body
            wrap.select(".sp-body *").forEach { row ->
                val anchors = row.select("a[href]")
                if (anchors.isEmpty()) return@forEach

                val rowText = row.text().trim()
                if (rowText.isBlank() || rowText.contains("[riduci]", ignoreCase = true)) return@forEach

                // Gestione dei link cumulativi "Stagione Completa"
                if (rowText.contains(Regex("(?i)TUTTA LA SERIE|TUTTI GLI EPISODI|INTERA STAGIONE|STAGIONE COMPLETA"))) {
                    val folderLinks = anchors.map { it.attr("href") }.filter { l -> supportedHosts.any { l.contains(it) } }
                    if (folderLinks.isNotEmpty()) {
                        val linksData = folderLinks.joinToString("###")
                        if (episodes.none { it.data == linksData }) {
                            episodes.add(newEpisode(linksData) {
                                this.name = "Stagione Completa"
                                this.season = currentSeason
                                this.episode = 1
                            })
                        }
                    }
                    return@forEach
                }

                // REGEX OTTIMIZZATA: Supporta "2x01", "2×01" (carattere speciale Unicode) e spazi variabili
                val epMatch = Regex("(\\d+)\\s*[x×\\u00D7]\\s*(\\d+)").find(rowText)
                val fallbackMatch = Regex("(?i)(?:Episodio\\s*)?(\\d+)").find(rowText)

                if (epMatch == null && fallbackMatch == null) return@forEach

                val sNum = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: currentSeason
                val eNum = epMatch?.groupValues?.get(2)?.toIntOrNull() 
                    ?: fallbackMatch?.groupValues?.get(1)?.toIntOrNull() 
                    ?: return@forEach

                val baseEpName = "${sNum}x${String.format("%02d", eNum)}"

                // Isola esclusivamente i link supportati validi di questa riga di testo
                val linksForEpisode = anchors.map { it.attr("href") }.filter { link ->
                    supportedHosts.any { host -> link.contains(host) }
                }

                if (linksForEpisode.isNotEmpty()) {
                    val linksData = linksForEpisode.joinToString("###")
                    
                    // Cruciale: previene la duplicazione dovuta all'annidamento ricorsivo di JSoup (*)
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
        Log.d("CB01", "loadLinks() chiamato con data: $data")
        
        // OTTIMIZZAZIONE USER EXPERIENCE: I link diretti nativi (senza stayonline) vanno in cima.
        // Riduce a zero i tempi di attesa per l'utente quando sono disponibili host veloci.
        val allLinks = data.split("###")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .sortedBy { it.contains("stayonline.pro") } 

        allLinks.forEach { cleanLink ->
            try {
                if (cleanLink.contains("stayonline.pro")) {
                    Log.d("CB01", "StayOnline rilevato → bypass in corso…")
                    val bypassed = bypassStayOnline(cleanLink)
                    if (bypassed != null) {
                        loadExtractor(bypassed, cleanLink, subtitleCallback, callback)
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
            // 1. Sanificazione e isolamento sicuro del LinkID dall'URL di partenza
            val cleanUrl = link.substringBefore("?")
            val urlParts = cleanUrl.removeSuffix("/").split("/")
            val linkId = urlParts.lastOrNull { it.isNotBlank() } ?: return null
            
            // 2. Routing dinamico della richiesta AJAX basato sul tipo di risorsa (Embed vs Standard)
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

            // 3. Persistenza dei Cookie: Memorizziamo la sessione generata dalla GET iniziale
            val pageResponse = app.get(link, headers = headers)
            val cookies = pageResponse.cookies

            // 4. Invio della POST autorizzata con i cookie corretti
            val response = app.post(
                ajaxEndpoint,
                headers = headers,
                cookies = cookies,
                data = mapOf("id" to linkId, "ref" to "")
            ).text

            val json = JSONObject(response)
            if (json.optString("status") == "success") {
                var realUrl = json.getJSONObject("data").getString("value")

                // Normalizzazione automatica dei flussi Mixdrop camuffati
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
