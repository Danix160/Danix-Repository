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
            
            // Miriamo specificamente ai link degli episodi che abbiamo visto nell'HTML
            // Cercano i link dentro .div_episodes e i bottoni associati
            doc.select(".div_episodes a, .div_seasons a").forEach { el ->
                val href = fixUrlNull(el.attr("href")) ?: return@forEach
                
                // Analizziamo l'URL tipo: .../144035/1/2/
                // Il pattern cerca tre gruppi di numeri alla fine dell'URL
                val pathSegments = href.trimEnd('/').split("/")
                if (pathSegments.size >= 3) {
                    val episodeNum = pathSegments.last().toIntOrNull()
                    val seasonNum = pathSegments[pathSegments.size - 2].toIntOrNull()
                    
                    if (episodeNum != null && seasonNum != null) {
                        episodes.add(newEpisode(href) {
                            this.name = "Episodio $episodeNum"
                            this.season = seasonNum
                            this.episode = episodeNum
                        })
                    }
                }
            }

            // Se la lista è ancora vuota, usiamo un selettore più generico basato sui bottoni
            if (episodes.isEmpty()) {
                doc.select("button.episodes_button, button.selected_btn").forEach { btn ->
                    val parentA = btn.parent()
                    if (parentA?.tagName() == "a") {
                        val href = fixUrl(parentA.attr("href"))
                        episodes.add(newEpisode(href) {
                            this.name = "Episodio " + btn.text().trim()
                        })
                    }
                }
            }

            return newTvSeriesLoadResponse(
                title, 
                url, 
                TvType.TvSeries, 
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
        val webViewRes = app.get(
            data, 
            interceptor = WebViewResolver(
                Regex(".*flexy\\.stream.*|.*uprot\\.net.*|.*master\\.m3u8.*|.*index\\.m3u8.*")
            ),
            headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to pcUserAgent
            ),
            timeout = 30 
        )

        if (webViewRes.url.contains(".m3u8")) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "Flexy Player",
                    url = webViewRes.url
                ) {
                    // Costruttore vuoto per compatibilità SDK
                }
            )
            return true
        }

        val doc = webViewRes.document
        doc.select("iframe[src*='flexy'], iframe[src*='uprot']").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            loadExtractor(src, data, subtitleCallback, callback)
        }

        return true
    }
}
