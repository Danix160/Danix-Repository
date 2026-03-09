package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

    // Mappa per gestire i cookie manualmente se necessario
    private var currentCookies: Map<String, String> = HashMap()

    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/" to "Film: Ultimi aggiunti",
        "$mainUrl/serie-tv/page/" to "Serie TV: Ultime aggiunte",
        "$mainUrl/film-generi/azione/page/" to "Film: Azione"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page/"
        // Eseguiamo la chiamata salvando i cookie
        val res = app.get(url)
        currentCookies = res.cookies
        
        val document = res.document
        val items = document.select(".uagb-post__inner-wrap, article, .movie").mapNotNull {
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
        val res = app.get("$mainUrl/?s=$query", cookies = currentCookies)
        return res.document.select(".uagb-post__inner-wrap, article, .movie").mapNotNull { 
            it.toSearchResult() 
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, cookies = currentCookies)
        currentCookies = response.cookies // Aggiorna i cookie (es. Cloudflare o sessione)
        
        val doc = response.document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
        val plot = doc.selectFirst("meta[name='description']")?.attr("content")

        return if (url.contains("/film/")) {
            val videoLink = doc.selectFirst("iframe[src*=/stream], a[href*=/stream]")?.run {
                attr("src").ifEmpty { attr("href") }
            } ?: url
            
            newMovieLoadResponse(title, url, TvType.Movie, videoLink) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            val episodes = mutableListOf<Episode>()
            // Selettore specifico per le serie tv del sito
            doc.select(".div_episodes a, .episodes a, a[href*='/episodio/']").forEach { el ->
                val epHref = el.attr("href")
                val epName = el.text().trim()
                val epNum = Regex("""\d+""").find(epName)?.value?.toIntOrNull()
                
                episodes.add(newEpisode(epHref) {
                    this.name = "Episodio $epName"
                    this.episode = epNum
                    this.posterUrl = poster // ANTEPRIMA: Copertina della serie
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
        // Fondamentale: inviamo i cookie della sessione anche qui
        val res = app.get(data, referer = mainUrl, cookies = currentCookies)
        val doc = res.document
        
        // Cerchiamo i player bypassando le protezioni cookie-based
        val players = doc.select("iframe[src], a[href*='stream'], a[href*='uprot']")
        
        players.forEach {
            val link = it.attr("src").ifEmpty { it.attr("href") }
            val absoluteLink = fixUrlNull(link) ?: return@forEach
            
            if (absoluteLink.contains("uprot") || absoluteLink.contains("msf") || absoluteLink.contains("fxf")) {
                val bypassed = bypassUprot(absoluteLink)
                if (bypassed != null) {
                    // Carichiamo l'estrattore con i cookie correnti
                    loadExtractor(bypassed, data, subtitleCallback, callback)
                }
            } else {
                loadExtractor(absoluteLink, data, subtitleCallback, callback)
            }
        }
        return true
    }

    private suspend fun bypassUprot(link: String): String? {
        val cleanLink = link.replace("msf", "mse").replace("fxf", "fxe")
        return try {
            // Passiamo i cookie anche al bypasser
            val response = app.get(cleanLink, referer = mainUrl, cookies = currentCookies).document
            response.selectFirst("a[href*='flexy'], a[href*='maxstream'], iframe")?.attr("href") 
                ?: response.selectFirst("iframe")?.attr("src")
        } catch (e: Exception) {
            null
        }
    }
}
