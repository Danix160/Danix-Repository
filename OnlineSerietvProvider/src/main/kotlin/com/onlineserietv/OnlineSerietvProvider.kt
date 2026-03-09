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

    private val pcUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/" to "Film Recenti",
        "$mainUrl/serie-tv/page/" to "Serie TV Aggiornate",
        "$mainUrl/film-generi/azione/page/" to "Azione",
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
        val title = doc.selectFirst("h1, .entry-title")?.text()?.replace(Regex("(?i)streaming|serie tv"), "")?.trim() ?: "Senza Titolo"
        val poster = doc.selectFirst("meta[property='og:image'], .wp-post-image")?.attr("content")
        val plot = doc.selectFirst(".entry-content p, meta[name='description']")?.text()?.trim()

        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            val episodes = mutableListOf<Episode>()
            // Selettore specifico per le stagioni nascoste (Shortcodes Ultimate)
            val episodeElements = doc.select(".su-spoiler-content a[href*='/episodio/'], .entry-content a[href*='/episodio/'], .lista-episodi a")
            
            episodeElements.forEach { el ->
                val epHref = el.attr("href")
                val epName = el.text().trim()
                if (epHref.isNotEmpty()) {
                    episodes.add(newEpisode(epHref) {
                        this.name = epName.ifBlank { "Episodio" }
                        this.posterUrl = poster
                    })
                }
            }

            // Fallback: se non ci sono episodi listati, usa i bottoni diretti (comune in Gumball)
            if (episodes.isEmpty()) {
                doc.select("a.su-button, a[href*='uprot'], a[href*='msf']").forEach { el ->
                    val href = el.attr("href")
                    if (!href.contains("share") && !href.contains("facebook")) {
                        episodes.add(newEpisode(href) { this.name = "Play / Streaming" })
                    }
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
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
        // WebViewResolver bypassa la protezione anti-bot del player
        val res = app.get(data, interceptor = WebViewResolver(Regex(".*")), timeout = 20)
        
        // Estraiamo tutti i possibili link di player
        val links = mutableListOf<String>()
        res.document.select("iframe, a, [data-src], [data-l]").forEach { 
            links.add(it.attr("src"))
            links.add(it.attr("href"))
            links.add(it.attr("data-src"))
            links.add(it.attr("data-l"))
        }

        links.filter { it.startsWith("http") }.distinct().forEach { link ->
            val cleanLink = fixUrl(link)
            if (cleanLink.contains("uprot") || cleanLink.contains("msf") || cleanLink.contains("fxf")) {
                val finalPlayer = resolveBridge(cleanLink)
                if (finalPlayer != null) loadExtractor(finalPlayer, data, subtitleCallback, callback)
            } else {
                loadExtractor(cleanLink, data, subtitleCallback, callback)
            }
        }
        return true
    }

    private suspend fun resolveBridge(url: String): String? {
        return try {
            val res = app.get(url, interceptor = WebViewResolver(Regex(".*")), timeout = 15)
            res.document.selectFirst("iframe[src], a.btn-primary, div#player a")?.run {
                val found = attr("src").ifEmpty { attr("href") }
                if (found.isNotEmpty()) fixUrl(found) else null
            }
        } catch (e: Exception) { null }
    }
}
