package com.cb

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

class CbProvider : MainAPI() {
    override var mainUrl = "https://cb01uno.bar"
    override var name = "CB01"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0",
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
            "streaming",
            "[HD]",
            "film gratis by cb01 official",
            "serie tv gratis by cb01 official",
            "completa",
            "ITA",
            "HD",
            "Stagione",
            "stagione",
            "Serie",
            "Episodio",
            "(",
            ")"
        )

        removeList.forEach { bad ->
            t = t.replace(bad, "", ignoreCase = true)
        }

        return t.trim()
    }

    private fun parseElement(element: Element, isTvSeriesSearch: Boolean = false): SearchResponse? {
        val titleElement = element.selectFirst("h2 a, h3 a, .card-title a, .post-title a, a[title]")
            ?: return null

        val href = titleElement.attr("href")
        if (href.contains("/tag/") || href.contains("/category/") || href.length < 15) return null

        val rawTitle = titleElement.text()
        val isSeries = isTvSeriesSearch ||
                href.contains("/serietv/") ||
                href.contains("/serie/") ||
                rawTitle.contains("Stagion", ignoreCase = true) ||
                rawTitle.contains("Serie", ignoreCase = true) ||
                rawTitle.contains("Episodio", ignoreCase = true)

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

        val items = document.select("div.card, div.post-video, article.post, div.mp-post")
            .mapNotNull { parseElement(it, request.data.contains("serietv")) }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl, headers = commonHeaders).document

        return document.select("div.card, div.post-video, article, div.mp-post, .result-item")
            .mapNotNull { parseElement(it) }
            .distinctBy { it.url }
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
        //          SERIE TV
        // ============================
        val seasonsData = mutableListOf<SeasonData>()

        document.select("div.sp-wrap").forEachIndexed { index, wrap ->
            val seasonHead = wrap.selectFirst(".sp-head")?.text().orEmpty()
            val seasonNumber = Regex("\\d+").find(seasonHead)?.value?.toIntOrNull() ?: (index + 1)

            // Salva nome stagione pulito
            val seasonNameClean = seasonHead
                .replace("- ITA", "", ignoreCase = true)
                .replace("- HD", "", ignoreCase = true)
                .trim()

            if (seasonNameClean.isNotBlank()) {
                seasonsData.add(SeasonData(seasonNumber, seasonNameClean))
            }

            wrap.select(".sp-body p, .sp-body li, .sp-body div, .sp-body span").forEach { row ->
                val rowText = row.text().trim()
                val anchors = row.select("a[href]")

                if (anchors.isEmpty()) return@forEach

                val epMatch = Regex("(\\d+)x(\\d+)").find(rowText) ?: return@forEach
                val sNum = epMatch.groupValues[1].toInt()
                val eNum = epMatch.groupValues[2].toInt()

                val linksForEpisode = anchors.mapNotNull { a ->
                    val link = a.attr("href")
                    if (supportedHosts.any { host -> link.contains(host) }) link else null
                }

                if (linksForEpisode.isNotEmpty()) {
                    episodes.add(
                        newEpisode(linksForEpisode.joinToString("###")) {
                            this.name = "${sNum}x${eNum}"
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
                    val bypassed = bypassStayOnline(cleanLink)
                    if (bypassed != null) {
                        loadExtractor(bypassed, cleanLink, subtitleCallback, callback)
                    }
                } else {
                    loadExtractor(cleanLink, cleanLink, subtitleCallback, callback)
                }
            } catch (_: Exception) {}
        }
        return true
    }

    private suspend fun bypassStayOnline(link: String): String? {
        return try {
            val headers = mapOf(
                "Origin" to "https://stayonline.pro",
                "Referer" to link,
                "User-Agent" to "Mozilla/5.0",
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
            )

            // 1️⃣ GET pagina per estrarre linkId corretto
            val pageResponse = app.get(link, headers = headers)
            val pageHtml = pageResponse.text

            var linkId = link.substringAfterLast("/")
            val idPattern = Regex("""var linkId\s*=\s*"([^"]+)";""")
            val idMatch = idPattern.find(pageHtml)
            if (idMatch != null) {
                linkId = idMatch.groupValues[1]
            }

            // 2️⃣ POST ajax/linkView.php
            val response = app.post(
                "https://stayonline.pro/ajax/linkView.php",
                headers = headers,
                data = mapOf(
                    "id" to linkId,
                    "ref" to ""
                )
            ).text

            val json = JSONObject(response)
            if (json.optString("status") == "success") {
                var realUrl = json.getJSONObject("data").getString("value")

                // Fix m1xdrop → mixdrop
                if (realUrl.contains("m1xdrop.net/f/")) {
                    val videoId = realUrl.substringAfterLast("/")
                    realUrl = "https://mixdrop.top/e/$videoId"
                }
                realUrl
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
