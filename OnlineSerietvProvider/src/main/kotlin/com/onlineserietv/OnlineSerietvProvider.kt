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

    // User agent moderno per evitare blocchi bot
    private val pcUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        val urls = listOf(
            "$mainUrl/movies/page/" to "Film Recenti",
            "$mainUrl/serie-tv/page/" to "Serie TV"
        )
        
        for ((url, title) in urls) {
            val res = app.get("$url$page/", headers = mapOf("User-Agent" to pcUserAgent))
            val searchRes = res.document.select("article, .uagb-post__inner-wrap").mapNotNull {
                it.toSearchResult()
            }
            if (searchRes.isNotEmpty()) items.add(HomePageList(title, searchRes))
        }
        return newHomePageResponse(items, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleTag = this.selectFirst("h2 a, .uagb-post__title a") ?: return null
        val href = titleTag.attr("href")
        val title = titleTag.text().replace(Regex("(?i)streaming|sub ita"), "").trim()
        val posterUrl = this.selectFirst("img")?.attr("src")

        return if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = mapOf("User-Agent" to pcUserAgent)).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "No Title"
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
        
        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) { this.posterUrl = poster }
        } else {
            // Logica migliorata per trovare episodi anche nei menu a tendina/spoiler
            val episodes = doc.select(".su-spoiler-content a, .entry-content a[href*='/episodio/']").mapNotNull {
                val href = it.attr("href")
                if (href.isEmpty() || href.contains("facebook")) null
                else newEpisode(href) { 
                    this.name = it.text().trim()
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { this.posterUrl = poster }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Carichiamo la pagina del player
        val res = app.get(data, headers = mapOf("User-Agent" to pcUserAgent))
        val document = res.document

        // 1. Cerchiamo iframe diretti (YouTube, Mixdrop, etc)
        document.select("iframe").forEach {
            val src = it.attr("src")
            loadExtractor(src, data, subtitleCallback, callback)
        }

        // 2. Gestione specifica per i link "ponte" come uprot.net/msf/
        document.select("a[href*='uprot.net'], a[href*='msf']").forEach {
            val link = it.attr("href")
            // Usiamo un resolver con un timeout più basso per evitare il blocco visto nei log
            val resolvedRes = app.get(link, interceptor = WebViewResolver(Regex(".*")), timeout = 20)
            
            // Cerchiamo il link finale dentro la pagina risolta
            resolvedRes.document.select("iframe, a.btn").forEach { el ->
                val finalUrl = el.attr("src").ifEmpty { el.attr("href") }
                if (finalUrl.isNotEmpty()) {
                    loadExtractor(finalUrl, link, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
