package com.onlineserietv

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Document
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

    // Configurazione della Home Page basata sui tuoi file sorgente
    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "Film: Ultimi aggiunti",
        "$mainUrl/serie-tv/" to "Serie TV: Ultime aggiunte",
        "$mainUrl/film-generi/azione/" to "Film: Azione",
        "$mainUrl/serie-tv-generi/animazione/" to "Serie TV: Animazione",
        "$mainUrl/film-generi/horror/" to "Film: Horror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        // Selettore basato su Home.txt (uagb-post per le griglie moderne)
        val items = document.select(".uagb-post__inner-wrap, .movie").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(HomePageList(request.name, items), false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleTag = this.selectFirst(".uagb-post__title a, h2 a, h3 a") ?: return null
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
        val document = app.get("$mainUrl/?s=$query").document
        return document.select(".movie, .uagb-post__inner-wrap").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
        val year = doc.select(".stars i").firstOrNull()?.text()?.toIntOrNull()

        return if (url.contains("/film/")) {
            // Per i film cerchiamo l'iframe di streaming
            val iframeUrl = doc.selectFirst("iframe[src*=/stream-film/]")?.attr("src") 
                ?: doc.selectFirst("#hostlinks a")?.attr("href") ?: ""
            
            newMovieLoadResponse(title, url, TvType.Movie, iframeUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            // Per le serie estraiamo gli episodi dai pulsanti (IframeSerie.txt)
            val episodes = mutableListOf<Episode>()
            doc.select("a.episodes_button, #hostlinks table tr").forEach { el ->
                val epHref = el.selectFirst("a")?.attr("href") ?: el.attr("href")
                if (epHref.isNotEmpty()) {
                    val epNum = el.text().filter { it.isDigit() }.toIntOrNull()
                    episodes.add(Episode(epHref, episode = epNum))
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
        // 'data' può essere un singolo URL o una lista JSON
        val urls = if (data.startsWith("[")) parseJson<List<String>>(data) else listOf(data)

        urls.amap { link ->
            val bypassedUrl = if (link.contains("uprot")) bypassUprot(link) else link
            
            if (bypassedUrl != null) {
                val doc = app.get(bypassedUrl, referer = link).document
                val scriptHtml = doc.select("script").html()

                // Logica per FLEXY: cerca il file video negli script
                val videoUrl = Regex("""file(?:\s*):(?:\s*)"([^"]+)"""").find(scriptHtml)?.groupValues?.get(1)
                
                if (videoUrl != null) {
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            "Flexy Player",
                            videoUrl,
                            referer = bypassedUrl,
                            quality = getQualityFromName("720p"),
                            isM3u8 = videoUrl.contains(".m3u8")
                        )
                    )
                } else {
                    // Fallback su estrattori automatici (es. MaxStream)
                    loadExtractor(bypassedUrl, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    private suspend fun bypassUprot(link: String): String? {
        // Sostituzione parametro per tentare il bypass diretto
        val updatedLink = link.replace("msf", "mse")
        
        return try {
            val response = app.get(
                updatedLink, 
                referer = mainUrl,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            ).document
            
            // Cerca il link del player nel pulsante di redirect o nel Javascript
            response.selectFirst("a[href*='flexy'], a[href*='maxstream']")?.attr("href")
                ?: Regex("window\\.location\\.href\\s*=\\s*\"(.*?)\"").find(response.html())?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }
}
