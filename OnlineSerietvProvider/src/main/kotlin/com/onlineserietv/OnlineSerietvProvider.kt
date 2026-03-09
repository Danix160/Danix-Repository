package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import java.util.HashMap

class OnlineSerietvProvider : MainAPI() {
    override var mainUrl = "https://onlineserietv.live"
    override var name = "OnlineSerieTV"
    override var lang = "it"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie, 
        TvType.TvSeries, 
        TvType.Cartoon, 
        TvType.Anime
    )

    private val pcUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    private var currentCookies: Map<String, String> = HashMap()

    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/" to "Film: Ultimi aggiunti",
        "$mainUrl/serie-tv/page/" to "Serie TV: Ultime aggiunte",
        "$mainUrl/film-generi/azione/page/" to "Film: Azione"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page/"
        val res = app.get(url, headers = mapOf("User-Agent" to pcUserAgent))
        currentCookies = res.cookies
        
        val items = res.document.select(".uagb-post__inner-wrap, article, .movie").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleTag = this.selectFirst("h2 a, .uagb-post__title a, .imagen a, .entry-title a") ?: return null
        val href = titleTag.attr("href")
        val title = (this.selectFirst("h2")?.text() ?: titleTag.text()).trim()
            .replace(Regex("(?i)guarda|in streaming|– SUB ITA"), "").trim()
        val posterUrl = this.selectFirst("img")?.attr("src")

        return if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get("$mainUrl/?s=$query", headers = mapOf("User-Agent" to pcUserAgent))
        return res.document.select(".uagb-post__inner-wrap, article, .movie").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, cookies = currentCookies, headers = mapOf("User-Agent" to pcUserAgent))
        val doc = response.document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Senza Titolo"
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
        val plot = doc.selectFirst("meta[name='description']")?.attr("content")

        return if (url.contains("/film/")) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            val episodes = mutableListOf<Episode>()
            doc.select(".div_episodes a, .episodes a, a[href*='/episodio/']").forEach { el ->
                val epHref = el.attr("href")
                val epName = el.text().trim()
                episodes.add(newEpisode(epHref) {
                    this.name = "Episodio $epName"
                    this.posterUrl = poster
                })
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
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
        // Bypass con WebView invisibile per caricare i JavaScript e i Cookie del player
        val res = app.get(
            data,
            interceptor = WebViewResolver(Regex(".*onlineserietv.*")), 
            headers = mapOf("User-Agent" to pcUserAgent),
            referer = mainUrl
        )
        
        val doc = res.document
        val potentialLinks = mutableListOf<String>()

        // Cerchiamo ovunque: iframe, attributi data, o link diretti
        doc.select("iframe, [data-src], [data-l], a[href*='uprot'], a[href*='msf']").forEach { el ->
            potentialLinks.add(el.attr("src"))
            potentialLinks.add(el.attr("data-src"))
            potentialLinks.add(el.attr("href"))
            potentialLinks.add(el.attr("data-l"))
        }

        potentialLinks.filter { it.isNotBlank() && it.startsWith("http") }.distinct().forEach { link ->
            val absoluteLink = fixUrl(link)
            
            // Gestione dei "Bridge" (siti intermedi come uprot/msf)
            if (absoluteLink.contains("uprot") || absoluteLink.contains("msf") || absoluteLink.contains("fxf")) {
                val finalPlayer = resolveBridge(absoluteLink)
                if (finalPlayer != null) {
                    loadExtractor(finalPlayer, data, subtitleCallback, callback)
                }
            } else {
                loadExtractor(absoluteLink, data, subtitleCallback, callback)
            }
        }
        return true
    }

    private suspend fun resolveBridge(url: String): String? {
        return try {
            // Risolviamo il bridge attendendo che il JS generi il link finale
            val res = app.get(
                url, 
                interceptor = WebViewResolver(Regex(".*")), 
                timeout = 20,
                headers = mapOf("User-Agent" to pcUserAgent)
            )
            val doc = res.document
            // Cerca il player finale (MixDrop, Voe, SuperVideo, ecc.)
            doc.selectFirst("iframe[src], a.btn-primary, div#player a, a[href*='flexy']")?.run {
                val found = attr("src").ifEmpty { attr("href") }
                if (found.isNotEmpty()) fixUrl(found) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
