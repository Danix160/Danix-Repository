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
        "$mainUrl/movies/" to "Film: Ultimi aggiunti",
        "$mainUrl/serie-tv/" to "Serie TV: Ultime aggiunte",
        "$mainUrl/film-generi/azione/" to "Film: Azione",
        "$mainUrl/serie-tv-generi/animazione/" to "Serie TV: Animazione"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        // Selettore aggiornato per includere .movie (usato in Azione) e .uagb-post__inner-wrap (usato in Home)
        val items = document.select("#box_movies .movie, .uagb-post__inner-wrap, article.movie").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(HomePageList(request.name, items), false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Cerchiamo il link: può essere in h2 (ricerca) o dentro .imagen (generi)
        val titleTag = this.selectFirst("h2 a, .uagb-post__title a, .imagen a") ?: return null
        val href = titleTag.attr("href")
        
        // Il titolo testuale spesso è meglio prenderlo dal tag h2 o dall'alt dell'immagine
        val title = this.selectFirst("h2")?.text()?.trim() 
            ?: this.selectFirst(".uagb-post__title")?.text()?.trim()
            ?: titleTag.attr("title").replace("Guarda ", "").replace(" in streaming", "").trim()

        val posterUrl = this.selectFirst("img")?.attr("src")

        return if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        // Nella ricerca i risultati sono solitamente articoli o wrap Gutenberg
        return document.select(".uagb-post__inner-wrap, article, .movie").mapNotNull { 
            it.toSearchResult() 
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")

        return if (url.contains("/film/")) {
            val iframeUrl = doc.selectFirst("iframe[src*=/stream-film/]")?.attr("src") 
                ?: doc.selectFirst("a[href*=/stream-film/]")?.attr("href") ?: url
            
            newMovieLoadResponse(title, url, TvType.Movie, iframeUrl) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            val episodes = doc.select(".div_episodes a, a:has(.episodes_button)").mapNotNull { el ->
                val epHref = el.attr("href")
                val epNum = el.text().trim().filter { it.isDigit() }.toIntOrNull()
                
                newEpisode(epHref) {
                    this.episode = epNum
                    this.name = "Episodio $epNum"
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
        val response = app.get(data)
        val doc = response.document
        if (doc.select("input[name=capt]").isNotEmpty()) return false

        doc.select("iframe[src*=/uprot.], a[href*=/uprot.], iframe[src*=/stream-film/]").forEach { el ->
            val link = el.attr("src").ifEmpty { el.attr("href") }
            val bypassedUrl = bypassUprot(link)
            
            if (bypassedUrl != null) {
                val playerDoc = app.get(bypassedUrl, referer = data).document
                val videoUrl = Regex("""file(?:\s*):(?:\s*)"([^"]+)"""").find(playerDoc.html())?.groupValues?.get(1)
                
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
                }
            }
        }
        return true
    }

    private suspend fun bypassUprot(link: String): String? {
        val cleanLink = link.replace("msf", "mse").replace("fxf", "fxe")
        return try {
            val response = app.get(cleanLink, referer = mainUrl).document
            response.selectFirst("a[href*='flexy'], a[href*='maxstream'], a[href*='mxtm']")?.attr("href")
        } catch (e: Exception) {
            null
        }
    }
}
