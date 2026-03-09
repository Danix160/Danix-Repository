package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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

    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/" to "Film: Ultimi aggiunti",
        "$mainUrl/serie-tv/page/" to "Serie TV: Ultime aggiunte",
        "$mainUrl/film-generi/azione/page/" to "Film: Azione",
        "$mainUrl/serie-tv-generi/animazione/page/" to "Serie TV: Animazione"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Aggiunta gestione paginazione per la home e i generi
        val url = "${request.data}$page/"
        val document = app.get(url).document
        val items = document.select("#box_movies .movie, .uagb-post__inner-wrap, article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleTag = this.selectFirst("h2 a, .uagb-post__title a, .imagen a") ?: return null
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
        // La ricerca di base mostra solo i primi risultati
        val document = app.get("$mainUrl/?s=$query").document
        return document.select(".uagb-post__inner-wrap, article, .movie").mapNotNull { 
            it.toSearchResult() 
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url)
        val doc = response.document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
        val plot = doc.selectFirst("meta[name='description']")?.attr("content")

        return if (url.contains("/film/")) {
            val iframeUrl = doc.selectFirst("iframe[src*=/stream-film/]")?.attr("src") 
                ?: doc.selectFirst("a[href*=/stream-film/]")?.attr("href") ?: url
            
            newMovieLoadResponse(title, url, TvType.Movie, iframeUrl) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            // FIX EPISODI: Usiamo un selettore più aggressivo che cerca tutti i link nel div episodi
            val episodes = doc.select(".div_episodes a, a:has(button[class*='_btn'])").mapNotNull { el ->
                val epHref = el.attr("href")
                // Estraiamo il numero dal testo del bottone o dal link stesso
                val epText = el.text().trim()
                val epNum = epText.filter { it.isDigit() }.toIntOrNull() 
                    ?: Regex("""/(\d+)/?$""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                
                newEpisode(epHref) {
                    this.episode = epNum
                    this.name = "Episodio $epText"
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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
        val doc = app.get(data).document
        
        // Se c'è un captcha numerico, Cloudstream non può proseguire da solo
        if (doc.select("input[name=capt]").isNotEmpty()) return false

        // Cerchiamo i link ai player
        doc.select("iframe[src*=/uprot.], a[href*=/uprot.], iframe[src*=/fxe/], iframe[src*=/mse/]").forEach { el ->
            val link = el.attr("src").ifEmpty { el.attr("href") }
            val bypassedUrl = bypassUprot(link)
            
            if (bypassedUrl != null) {
                val playerDoc = app.get(bypassedUrl, referer = data).document
                val scriptHtml = playerDoc.select("script").html()
                val videoUrl = Regex("""file(?:\s*):(?:\s*)"([^"]+)"""").find(scriptHtml)?.groupValues?.get(1)
                
                if (videoUrl != null) {
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            if (bypassedUrl.contains("flexy")) "Flexy" else "MaxStream",
                            videoUrl,
                            if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.P720.value
                            this.referer = bypassedUrl
                        }
                    )
                } else {
                    loadExtractor(bypassedUrl, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    private suspend fun bypassUprot(link: String): String? {
        val cleanLink = link.replace("msf", "mse").replace("fxf", "fxe")
        return try {
            val response = app.get(cleanLink, referer = mainUrl).document
            response.selectFirst("a[href*='flexy'], a[href*='maxstream'], a[href*='uprot']")?.attr("href")
        } catch (e: Exception) {
            null
        }
    }
}
