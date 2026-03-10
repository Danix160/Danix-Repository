package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element

class OnlineSerietvProvider : MainAPI() {
    override var mainUrl = "https://onlineserietv.live"
    override var name = "OnlineSerieTV"
    override var lang = "it"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val pcUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/" to "Film Recenti",
        "$mainUrl/serie-tv/page/" to "Serie TV",
        "$mainUrl/film-generi/animazione/page/" to "Animazione"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page/"
        val res = app.get(url, headers = mapOf("User-Agent" to pcUserAgent))
        val items = res.document.select("article, .uagb-post__inner-wrap, .movie-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleTag = this.selectFirst("h2 a, .uagb-post__title a, .entry-title a") ?: return null
        val href = titleTag.attr("href")
        val title = titleTag.text().replace(Regex("(?i)streaming|sub ita"), "").trim()
        val posterUrl = this.selectFirst("img")?.attr("src")

        return if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get("$mainUrl/?s=$query", headers = mapOf("User-Agent" to pcUserAgent))
        return res.document.select("article, .uagb-post__inner-wrap").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = mapOf("User-Agent" to pcUserAgent))
        val doc = response.document
        val title = doc.selectFirst("h1, .entry-title")?.text()
            ?.replace(Regex("(?i)streaming|serie tv"), "")?.trim() ?: "Senza Titolo"
        val poster = doc.selectFirst("meta[property='og:image'], .wp-post-image")?.attr("content")
        val plot = doc.selectFirst(".entry-content p, meta[name='description']")?.text()?.trim()

        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            val episodes = mutableListOf<Episode>()

            // Scraping Tabella (es. Gumball)
            doc.select("#hostlinks tr").forEach { row ->
                val cells = row.select("td")
                if (cells.size >= 2) {
                    val infoText = cells[0].text()
                    val regex = Regex("""(\d+)[xX](\d+)""")
                    val match = regex.find(infoText)
                    
                    val s = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val e = match?.groupValues?.get(2)?.toIntOrNull()
                    
                    if (e != null) {
                        val link = row.selectFirst("a[href*='uprot'], a[href*='flexy']")?.attr("href")
                        if (link != null) {
                            episodes.add(newEpisode(link) {
                                this.name = infoText
                                this.season = s
                                this.episode = e
                            })
                        }
                    }
                }
            }

            // Scraping Bottoni (Fallback)
            if (episodes.isEmpty()) {
                doc.select(".div_episodes a").forEach { el ->
                    val href = fixUrlNull(el.attr("href")) ?: return@forEach
                    val segments = href.trimEnd('/').split("/")
                    if (segments.size >= 3) {
                        val epNum = segments.last().toIntOrNull()
                        val sNum = segments[segments.size - 2].toIntOrNull()
                        if (epNum != null && sNum != null) {
                            episodes.add(newEpisode(href) {
                                this.name = "Episodio $epNum"
                                this.season = sNum
                                this.episode = epNum
                            })
                        }
                    }
                }
            }

            return newTvSeriesLoadResponse(
                title, url, TvType.TvSeries,
                episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))
            ) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // STEP 1: Risoluzione del ponte UPROT
        val finalUrl = if (data.contains("uprot.net")) {
            val uprotRes = app.get(data, headers = mapOf("User-Agent" to pcUserAgent))
            uprotRes.document.selectFirst("a[href*='flexy.stream']")?.attr("href") ?: data
        } else {
            data
        }

        // STEP 2: WebView per estrarre il video
        val webViewRes = app.get(
            finalUrl,
            interceptor = WebViewResolver(
                Regex(".*flexy\\.stream.*|.*uprot\\.net.*|.*master\\.m3u8.*|.*index\\.m3u8.*|.*maxstream.*")
            ),
            headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to pcUserAgent
            ),
            timeout = 45 
        )

        // STEP 3: Creazione Link (Corretto per compilazione)
        if (webViewRes.url.contains(".m3u8")) {
            callback.invoke(
                newExtractorLink(
                    this.name,
                    webViewRes.url,
                    referer = finalUrl,
                    {
                    Qualities.Unknown.value
                    }
                )
            )
            return true
        }

        // STEP 4: Fallback agli estrattori
        webViewRes.document.select("iframe[src*='flexy'], iframe[src*='uprot'], iframe[src*='maxstream']").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            loadExtractor(src, finalUrl, subtitleCallback, callback)
        }

        return true
    }
}
