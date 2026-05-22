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
    val plot = document.select("div.ignore-css p, .entry-content p")
        .firstOrNull { it.text().length > 50 }
        ?.text()

    val episodes = mutableListOf<Episode>()

    // ============================
    //          FILM
    // ============================
    if (!isSeries) {
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

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            episodes.firstOrNull()?.data ?: ""
        ) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ============================
    //        SERIE TV
    // ============================
    document.select("div.sp-wrap").forEachIndexed { index, wrap ->
        val seasonTitle = wrap.selectFirst(".sp-head")?.text() ?: ""
        var currentSeason = index + 1

        // Estrai numero stagione se presente
        "(?i)Stagione\\s*(\\d+)".toRegex().find(seasonTitle)?.let {
            currentSeason = it.groupValues[1].toIntOrNull() ?: currentSeason
        }

        // Ogni episodio è in un <p>
        wrap.select(".sp-body p").forEach { row ->
            val rowText = row.text().trim()
            val anchors = row.select("a")

            if (anchors.isEmpty()) return@forEach
            if (rowText.contains("[riduci]", ignoreCase = true)) return@forEach

            // Match episodio tipo 1×01 o 1x01
            val epMatch = "(\\d+)\\s*[x×]\\s*(\\d+)".toRegex().find(rowText)
            if (epMatch == null) return@forEach

            val seasonNumber = epMatch.groupValues[1].toInt()
            val episodeNumber = epMatch.groupValues[2].toInt()
            val baseEpName = "${seasonNumber}x${episodeNumber}"

            anchors.forEach { a ->
                val link = a.attr("href")
                if (supportedHosts.any { link.contains(it) }) {

                    episodes.add(
                        newEpisode(link) {
                            this.name = "$baseEpName - ${a.text().trim()}"
                            this.season = seasonNumber
                            this.episode = episodeNumber
                        }
                    )
                }
            }
        }
    }

    return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
        this.posterUrl = poster
        this.plot = plot
    }
}

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val allLinks = data.split("###").map { it.trim() }

        allLinks.forEach { cleanLink ->
            try {
                if (cleanLink.contains("stayonline.pro")) {
                    bypassStayOnline(cleanLink)?.let { bypassed ->
                        // bypassed fornirà l'URL di uprot.net/msf/... o simili
                        loadExtractor(bypassed, subtitleCallback, callback) 
                    }
                } else {
                    loadExtractor(cleanLink, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                // Passa al link successivo in caso di errore
            }
        }
        return true
    }

    private suspend fun bypassStayOnline(link: String): String? {
        return try {
            val id = link.split("/").last { it.isNotBlank() }
            val response = app.post(
                "https://stayonline.pro/ajax/linkEmbedView.php",
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest", 
                    "Referer" to link,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                ),
                data = mapOf("id" to id)
            ).text
            JSONObject(response).getJSONObject("data").getString("value")
        } catch (e: Exception) { null }
    }
}
