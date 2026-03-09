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
        val url = "${request.data}$page/"
        val document = app.get(url).document
        val items = document.select("#box_movies .movie, .uagb-post__inner-wrap, article").mapNotNull {
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
        // Implementazione ricerca multi-pagina (Cloudstream chiama search più volte se restituisci una lista)
        // Per ora carichiamo la prima pagina, ma la struttura permette di aggiungere il supporto page
        val document = app.get("$mainUrl/?s=$query").document
        return document.select(".uagb-post__inner-wrap, article, .movie").mapNotNull { 
            it.toSearchResult() 
        }
    }

    // AGGIUNTA: Supporto per la ricerca multi-pagina in Cloudstream
    override suspend fun search(query: String, page: Int): List<SearchResponse> {
        val url = if (page <= 1) "$mainUrl/?s=$query" else "$mainUrl/page/$page/?s=$query"
        val document = app.get(url).document
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
            // FIX EPISODI: Selettore potenziato basato sull'HTML inviato
            val episodes = doc.select(".div_episodes a, a:has(button[class*='_btn']), .episodes_button_container a").mapNotNull { el ->
                val epHref = el.attr("href")
                // Se il testo è vuoto o non numerico, lo estraiamo dall'URL
                val epText = el.text().trim()
                val epNum = epText.filter { it.isDigit() }.toIntOrNull() 
                    ?: Regex("""/(\d+)/?$""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                
                if (epHref.isEmpty()) null else
                newEpisode(epHref) {
                    this.episode = epNum
                    this.name = if (epText.isNotEmpty()) "Episodio $epText" else "Episodio $epNum"
                }
            }.distinctBy { it.data } // Evita duplicati se i selettori si sovrappongono

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
        val response = app.get(data)
        val doc = response.document
        
        // Se c'è un captcha numerico, il caricamento link fallirà finché l'utente non lo risolve in WebView
        if (doc.select("input[name=capt]").isNotEmpty()) return false

        doc.select("iframe[src*=/uprot.], a[href*=/uprot.], iframe[src*=/fxe/], iframe[src*=/mse/], iframe[src*=/stream-]").forEach { el ->
            val link = el.attr("src").ifEmpty { el.attr("href") }
            val bypassedUrl = if (link.contains("uprot")) bypassUprot(link) else link
            
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
