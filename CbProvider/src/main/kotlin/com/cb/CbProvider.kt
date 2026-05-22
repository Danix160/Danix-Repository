package com.cb

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element

class CbProvider : MainAPI() {
    override var mainUrl = "https://cb01uno.bar"
    override var name = "CB01"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
    )

    // Lista degli host supportati, stayonline è fondamentale per intercettare i link delle serie
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
        return if (isMovie) {
            title.replace(Regex("(?i)streaming|\\[HD]|film gratis by cb01 official|\\(\\d{4}\\)"), "").trim()
        } else {
            title.replace(Regex("(?i)streaming|serie tv gratis by cb01 official|stagione \\d+|completa|[-–] ITA|[-–] HD"), "").trim()
        }
    }

    private fun parseElement(element: Element, isTvSeriesSearch: Boolean = false): SearchResponse? {
        val titleElement = element.selectFirst("h2 a, h3 a, .card-title a, .post-title a, a[title]") ?: return null
        val href = titleElement.attr("href")
        if (href.contains("/tag/") || href.contains("/category/") || href.length < 15) return null
        
        val rawTitle = titleElement.text()
        val isSeries = isTvSeriesSearch || href.contains("/serietv/") || href.contains("/serie/") || 
                       rawTitle.contains(Regex("(?i)Stagion|Serie|Episodio"))

        val title = fixTitle(rawTitle, !isSeries)
        val posterUrl = element.selectFirst("img")?.let { img ->
            img.attr("data-lazy-src").ifBlank { 
                img.attr("data-src").ifBlank { img.attr("src") } 
            }
        }

        return newMovieSearchResponse(title, href, if (isSeries) TvType.TvSeries else TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url, headers = commonHeaders).document
        val items = document.select("div.card, div.post-video, article.post, div.mp-post").mapNotNull { 
            parseElement(it, request.data.contains("serietv")) 
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl, headers = commonHeaders).document
        return document.select("div.card, div.post-video, article, div.mp-post, .result-item").mapNotNull {
            parseElement(it)
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = commonHeaders).document
        val isSeries = url.contains("/serietv/") || url.contains("/serie/")
        val title = fixTitle(document.selectFirst("h1")?.text() ?: "", !isSeries)
        val poster = document.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        val plot = document.select("div.ignore-css p, .entry-content p").firstOrNull { it.text().length > 50 }?.text()

        val episodes = mutableListOf<Episode>()

        if (!isSeries) {
            val links = document.select("table a, a.buttona_stream, .stream-link, iframe")
                .map { it.attr("href").ifBlank { it.attr("src") } }
                .filter { link -> supportedHosts.any { link.contains(it) } }
            
            if (links.isNotEmpty()) {
                episodes.add(newEpisode(links.joinToString("###")) { this.name = "Film - Streaming" })
            }
        } else {
            // Gestione flessibile delle serie TV (Spoiler div.sp-wrap)
            document.select("div.sp-wrap").forEachIndexed { index, wrap ->
                val seasonTitle = wrap.selectFirst(".sp-head")?.text() ?: ""
                var currentSeason = index + 1
                val seasonMatch = "(?i)Stagione\\s*(\\d+)".toRegex().find(seasonTitle)
                if (seasonMatch != null) {
                    currentSeason = seasonMatch.groupValues[1].toIntOrNull() ?: currentSeason
                }

                wrap.select("p, li, tr").forEach { row ->
                    val rowLinks = row.select("a").map { it.attr("href") }
                        .filter { link -> supportedHosts.any { link.contains(it) } }
                    
                    if (rowLinks.isNotEmpty()) {
                        val rowText = row.text()
                        
                        if (rowText.contains(Regex("(?i)TUTTA LA SERIE|TUTTI GLI EPISODI|INTERA STAGIONE"))) {
                            // Caso cartelle cumulative (uprot folder / msfld)
                            episodes.add(newEpisode(rowLinks.joinToString("###")) {
                                this.name = rowText.substringBefore("–").trim().ifBlank { "Tutti gli Episodi (Lista)" }
                                this.season = currentSeason
                                this.episode = 1
                            })
                        } else {
                            // Caso elenco classico ad episodi singoli (passando da stayonline o uprot diretto)
                            val epName = if (rowText.contains("–")) rowText.substringBefore("–").trim() else "Episodio"
                            val epMatch = "(\\d+)x(\\d+)".toRegex().find(rowText)
                            val episodeNumber = epMatch?.groupValues?.get(2)?.toIntOrNull() ?: (episodes.size + 1)

                            episodes.add(newEpisode(rowLinks.joinToString("###")) {
                                this.name = epName
                                this.season = currentSeason
                                this.episode = episodeNumber
                            })
                        }
                    }
                }
            }
        }

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { this.posterUrl = poster; this.plot = plot }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: "") { this.posterUrl = poster; this.plot = plot }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Splitta i link concatenati e ordina dando massima priorità a stayonline, uprot e maxstream
        val allLinks = data.split("###").map { it.trim() }.sortedByDescending { 
            it.contains("stayonline") || it.contains("uprot") || it.contains("maxstream") 
        }

        allLinks.forEach { cleanLink ->
            try {
                if (cleanLink.contains("stayonline.pro")) {
                    // Risolve la chiamata AJAX protetta di stayonline per sbloccare il link reale
                    bypassStayOnline(cleanLink)?.let { bypassed ->
                        loadExtractor(bypassed, subtitleCallback, callback) 
                    }
                } else {
                    // Invia direttamente all'estrattore di Uprot o MaxStream
                    loadExtractor(cleanLink, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                // Fallback silenzioso se un singolo link fallisce, per passare al successivo dell'elenco
            }
        }
        return true
    }

    private suspend fun bypassStayOnline(link: String): String? {
        return try {
            val id = link.split("/").last { it.isNotBlank() }
            val response = app.post(
                "https://stayonline.pro/ajax/linkEmbedView.php",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to link),
                data = mapOf("id" to id)
            ).text
            JSONObject(response).getJSONObject("data").getString("value")
        } catch (e: Exception) { null }
    }
}
