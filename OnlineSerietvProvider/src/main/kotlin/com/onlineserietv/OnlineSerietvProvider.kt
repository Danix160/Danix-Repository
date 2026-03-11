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

            doc.select("#hostlinks tr").forEach { row ->
                val cells = row.select("td")
                if (cells.size >= 2) {
                    val infoText = cells[0].text()
                    val regex = Regex("""(\d+)[xX](\d+)""")
                    val match = regex.find(infoText)
                    
                    val s = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val e = match?.groupValues?.get(2)?.toIntOrNull()
                    
                    if (e != null) {
                        val link = row.select("a").map { it.attr("href") }.firstOrNull { 
                            (it.contains("uprot.net") && (it.contains("/uprots/") || it.contains("/fxf/"))) 
                            || it.contains("flexy.stream")
                        }
                        
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
        var currentUrl = data

        if (currentUrl.contains("uprot.net")) {
            val res = app.get(currentUrl, headers = mapOf("User-Agent" to pcUserAgent))
            val doc = res.document
            
            val flexyLink = doc.select("a[href*='flexy.stream']").map { it.attr("href") }
                .firstOrNull { (it.contains("/uprots/") || it.contains("/fxf/")) && !it.contains("discovernative") }
            
            if (flexyLink != null) {
                currentUrl = fixUrl(flexyLink)
            }
        }

        val webViewRes = app.get(
            currentUrl,
            interceptor = WebViewResolver(
                Regex(".*flexy\\.stream.*|.*master\\.m3u8.*|.*index\\.m3u8.*|.*playlist\\.m3u8.*|.*\\.mp4.*")
            ),
            headers = mapOf(
                "Referer" to "https://uprot.net/",
                "User-Agent" to pcUserAgent
            ),
            timeout = 60 
        )

        if (webViewRes.url.contains(".m3u8") || webViewRes.url.contains(".mp4")) {
            // Usiamo il costruttore DSL passando tutto come argomenti nominali.
            // Questo evita errori di "reassignment" e "unresolved putAll".
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = webViewRes.url,
                    referer = currentUrl,
                    quality = Qualities.Unknown.value,
                    type = if (webViewRes.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    headers = mapOf(
                        "Referer" to currentUrl,
                        "User-Agent" to pcUserAgent
                    )
                )
            )
            return true
        }

        loadExtractor(currentUrl, "https://uprot.net/", subtitleCallback, callback)

        return true
    }
}
