package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import android.util.Log

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

            doc.select("#hostlinks tr").forEach { row ->
                val cells = row.select("td")
                if (cells.size >= 2) {
                    val infoText = cells[0].text()
                    val regex = Regex("""(\d+)[xX](\d+)""")
                    val match = regex.find(infoText)
                    
                    val s = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val e = match?.groupValues?.get(2)?.toIntOrNull()
                    
                    if (e != null) {
                        val link = row.select("a").map { it.attr("href") }.firstOrNull { 
                            (it.contains("uprot.net") && (it.contains("/uprots/") || it.contains("/fxf/"))) 
                            || it.contains("flexy.stream")
                        }
                        
                        if (link != null) {
                            episodes.add(newEpisode(link) {
                                this.name = infoText
                                this.season = s
                                this.episode = e
                            })
                        }
                    }
                }
            }

            return newTvSeriesLoadResponse(
                title, url, TvType.TvSeries,
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
    var currentUrl = data
    Log.d("OnlineSerieTV", "Inizio bypass Cloudflare per: $currentUrl")

    if (currentUrl.contains("uprot.net")) {
        // 1. Usiamo CloudflareKiller per ottenere l'HTML superando la protezione
        val cfInterceptor = CloudflareKiller()
        val response = app.get(
            currentUrl, 
            interceptor = cfInterceptor,
            headers = mapOf("User-Agent" to pcUserAgent)
        )
        
        val html = response.text
        
        // 2. Cerchiamo il link flexy.stream (Regex diretta)
        val flexyRegex = Regex("""https?://flexy\.stream/uprots/[a-zA-Z0-9+=/]+""")
        var foundLink = flexyRegex.find(html)?.value

        // 3. Se non lo trova, proviamo a decodificare la stringa Base64 che abbiamo visto nell'HTML
        if (foundLink.isNullOrEmpty()) {
            Log.d("OnlineSerieTV", "Link diretto non trovato, provo decodifica Base64...")
            // Cerchiamo stringhe Base64 lunghe almeno 40 caratteri che finiscono con ==
            val base64Regex = Regex("""[a-zA-Z0-9+/]{40,}=?=?""")
            base64Regex.findAll(html).forEach { match ->
                try {
                    val decoded = base64Decode(match.value)
                    if (decoded.contains("flexy.stream")) {
                        foundLink = decoded.trim()
                        Log.d("OnlineSerieTV", "Link trovato in Base64: $foundLink")
                    }
                } catch (e: Exception) { }
            }
        }

        if (!foundLink.isNullOrEmpty()) {
            currentUrl = fixUrl(foundLink!!)
        } else {
            Log.e("OnlineSerieTV", "Impossibile trovare il link di reindirizzamento nell'HTML")
            return false
        }
    }

    // 4. Ora risolviamo il video finale con la WebView standard sul link flexy
    Log.d("OnlineSerieTV", "Apertura WebView finale su: $currentUrl")
    
    val videoPage = app.get(
        currentUrl,
        interceptor = WebViewResolver(
            Regex(".*master\\.m3u8.*|.*index\\.m3u8.*|.*playlist\\.m3u8.*|.*\\.mp4.*")
        ),
        headers = mapOf(
            "Referer" to "https://uprot.net/",
            "User-Agent" to pcUserAgent
        ),
        timeout = 30 // Ridotto a 30s per evitare blocchi infiniti
    )

    if (videoPage.url.contains(".m3u8") || videoPage.url.contains(".mp4")) {
        Log.d("OnlineSerieTV", "Successo! Video: ${videoPage.url}")
        
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = videoPage.url,
                type = if (videoPage.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = currentUrl
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    return loadExtractor(currentUrl, "https://uprot.net/", subtitleCallback, callback)
}
}
