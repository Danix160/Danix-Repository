package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
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
        "$mainUrl/movies/" to "Film: Ultimi aggiunti",
        "$mainUrl/serie-tv/" to "Serie TV: Ultime aggiunte",
        "$mainUrl/film-generi/azione/" to "Film: Azione",
        "$mainUrl/serie-tv-generi/animazione/" to "Serie TV: Animazione"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = document.select(".uagb-post__inner-wrap, .movie, article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(HomePageList(request.name, items), false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleTag = this.selectFirst(".uagb-post__title a, h2 a, h3 a, .title a") ?: return null
        val title = titleTag.text().trim().replace(Regex("""\d{4}$"""), "")
        val href = titleTag.attr("href")
        val posterUrl = this.selectFirst("img")?.attr("src")

        return if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Migliorata la ricerca usando l'URL corretto del sito
        val document = app.get("$mainUrl/?s=$query").document
        // Il selettore copre sia i post nei container moderni che quelli classici
        return document.select(".uagb-post__inner-wrap, article, .movie").mapNotNull { 
            it.toSearchResult() 
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url)
        val doc = response.document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")

        return if (url.contains("/film/")) {
            // Estrazione iframe per i film
            val iframeUrl = doc.selectFirst("iframe[src*=/stream-film/]")?.attr("src") 
                ?: doc.selectFirst("a[href*=/stream-film/]")?.attr("href") ?: url
            
            newMovieLoadResponse(title, url, TvType.Movie, iframeUrl) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            // FIX SERIE TV: estrazione puntate dai bottoni .episodes_button
            val episodes = doc.select("a:has(button.episodes_button)").mapNotNull { el ->
                val epHref = el.attr("href")
                val epText = el.selectFirst("button")?.text()?.trim() ?: ""
                val epNum = epText.filter { it.isDigit() }.toIntOrNull()
                
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
        val response = app.get(data, referer = mainUrl)
        val doc = response.document

        // Gestione Captcha: se presente, l'utente deve risolverlo via WebView
        if (doc.selectFirst("input[name=capt]") != null) {
            return false // Cloudstream mostrerà il player vuoto o darà errore, invitando all'uso della WebView
        }

        // Cerchiamo i link diretti ai player (uprot, flexy, maxstream)
        val players = doc.select("iframe[src*=/uprot.], a[href*=/uprot.], iframe[src*=/fxe/], iframe[src*=/mse/]")
        
        players.forEach { player ->
            val link = player.attr("src").ifEmpty { player.attr("href") }
            if (link.isNotEmpty()) {
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
        }
        return true
    }

    private suspend fun bypassUprot(link: String): String? {
        val updatedLink = link.replace("msf", "mse").replace("fxf", "fxe")
        return try {
            val response = app.get(
                updatedLink, 
                referer = mainUrl,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            ).document
            
            response.selectFirst("a[href*='flexy'], a[href*='maxstream'], a[href*='uprot'], iframe[src*='uprot']")?.let {
                it.attr("href").ifEmpty { it.attr("src") }
            } ?: Regex("window\\.location\\.href\\s*=\\s*\"(.*?)\"").find(response.html())?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }
}
