package com.cb

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder

class CbProvider : MainAPI() {
    override var mainUrl = "https://cb01uno.download"
    override var name = "CB01"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true

    // Configurazione Proxy Privato
    private val myProxyUrl = "https://esproxy.onrender.com/"
    private val proxyAuth = "1601"

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
    )

    private val supportedHosts = listOf(
        "voe", "mixdrop", "streamtape", "fastream", "filemoon", 
        "wolfstream", "streamwish", "maxstream", "lulustream", 
        "uprot", "stayonline", "swzz", "supervideo", "vidmoly", "maxsa"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Film Recenti",
        "$mainUrl/serietv/" to "Serie TV Recenti"
    )

    private fun fixTitle(title: String, isMovie: Boolean): String {
        return if (isMovie) {
            title.replace(Regex("(?i)streaming|\\[HD]|film gratis by cb01 official|\\(\\d{4}\\)"), "").trim()
        } else {
            title.replace(Regex("(?i)streaming|serie tv gratis by cb01 official|stagione \\d+|completa|[-–] ITA|[-–] HD"), "").trim()
        }
    }

    private fun parseElement(element: Element, isTvSeriesSearch: Boolean = false): SearchResponse? {
        val titleElement = element.selectFirst("h2 a, h3 a, .card-title a, .post-title a, a[title]") ?: return null
        val href = titleElement.attr("href")
        if (href.contains("/tag/") || href.contains("/category/") || href.length < 15) return null
        
        val rawTitle = titleElement.text()
        val isSeries = isTvSeriesSearch || href.contains("/serietv/") || href.contains("/serie/") || 
                       rawTitle.contains(Regex("(?i)Stagion|Serie|Episodio"))

        val title = fixTitle(rawTitle, !isSeries)
        val posterUrl = element.selectFirst("img")?.let { img ->
            img.attr("data-lazy-src").ifBlank { 
                img.attr("data-src").ifBlank { img.attr("src") } 
            }
        }

        return newMovieSearchResponse(title, href, if (isSeries) TvType.TvSeries else TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url, headers = commonHeaders).document
        val items = document.select("div.card, div.post-video, article.post, div.mp-post").mapNotNull { 
            parseElement(it, request.data.contains("serietv")) 
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl, headers = commonHeaders).document
        return document.select("div.card, div.post-video, article, div.mp-post, .result-item").mapNotNull {
            parseElement(it)
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = commonHeaders).document
        val isSeries = url.contains("/serietv/") || url.contains("/serie/")
        val title = fixTitle(document.selectFirst("h1")?.text() ?: "", !isSeries)
        val poster = document.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        val plot = document.select("div.ignore-css p, .entry-content p").firstOrNull { it.text().length > 50 }?.text()

        val episodes = mutableListOf<Episode>()

        if (!isSeries) {
            val links = document.select("table a, a.buttona_stream, .stream-link, iframe")
                .map { it.attr("href").ifBlank { it.attr("src") } }
                .filter { link -> supportedHosts.any { link.contains(it) } }
            
            if (links.isNotEmpty()) {
                episodes.add(newEpisode(links.joinToString("###")) { this.name = "Film - Streaming" })
            }
        } else {
            document.select("div.sp-wrap").forEachIndexed { index, wrap ->
                wrap.select("p, li").forEach { row ->
                    val rowLinks = row.select("a").map { it.attr("href") }
                        .filter { link -> supportedHosts.any { link.contains(it) } }
                    
                    if (rowLinks.isNotEmpty()) {
                        episodes.add(newEpisode(rowLinks.joinToString("###")) {
                            this.name = row.text().substringBefore("–").trim()
                            this.season = index + 1
                        })
                    }
                }
            }
        }

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { this.posterUrl = poster; this.plot = plot }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: "") { this.posterUrl = poster; this.plot = plot }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        data.split("###").forEach { rawLink ->
            val cleanLink = rawLink.trim()
            
            // Gestione Host con Proxy (Uprot, MaxStream, Maxsa)
            if (cleanLink.contains("uprot.net") || cleanLink.contains("msf") || cleanLink.contains("maxsa")) {
                val embedUrl = if (cleanLink.contains("msf/")) {
                    cleanLink.replace("msf/", "embed-") + ".html"
                } else cleanLink

                println("CB_DEBUG: [START] Proxy per: $embedUrl")

                try {
                    val encodedTarget = URLEncoder.encode(embedUrl, "UTF-8")
                    val finalProxyRequest = "$myProxyUrl?url=$encodedTarget"
                    
                    println("CB_DEBUG: Chiamata a: $finalProxyRequest")

                    val res = app.get(
                        url = finalProxyRequest, 
                        headers = mapOf(
                            "Authorization" to proxyAuth,
                            "X-Proxy-Key" to proxyAuth
                        ),
                        timeout = 30 // Render free può essere lento
                    )

                    println("CB_DEBUG: Risposta Codice: ${res.code}")

                    if (res.code == 200) {
                        val responseBody = res.text
                        val directVideo = Regex("""file:\s*["'](http[^"']+)["']""").find(responseBody)?.groupValues?.get(1)
                        
                        if (directVideo != null) {
                            println("CB_DEBUG: [SUCCESS] Video trovato: $directVideo")
                            
                            val isM3u8 = directVideo.contains(".m3u8") || directVideo.contains(".ts")

                            callback.invoke(
                                newExtractorLink(
                                    name = "CB-Proxy-MaxStream",
                                    source = this.name,
                                    url = directVideo
                                ) {
                                    this.quality = Qualities.P720.value
                                    this.referer = embedUrl // Fondamentale per il player MaxStream
                                    this.type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                }
                            )
                        } else {
                            println("CB_DEBUG: [FAIL] Proxy risponde 200 ma 'file:' non trovato nel body")
                            loadExtractor(embedUrl, subtitleCallback, callback)
                        }
                    } else {
                        println("CB_DEBUG: [FAIL] HTTP Error ${res.code}")
                        loadExtractor(embedUrl, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    println("CB_DEBUG: [EXCEPTION] ${e.message}")
                    loadExtractor(embedUrl, subtitleCallback, callback)
                }
            } else {
                // Host Standard (Voe, Mixdrop, ecc.)
                val finalUrl = if (cleanLink.contains("stayonline.pro")) bypassStayOnline(cleanLink) else cleanLink
                finalUrl?.let { loadExtractor(it, subtitleCallback, callback) }
            }
        }
        return true
    }

    private suspend fun bypassStayOnline(link: String): String? {
        return try {
            val id = link.split("/").last { it.isNotBlank() }
            val response = app.post(
                "https://stayonline.pro/ajax/linkEmbedView.php",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to link),
                data = mapOf("id" to id)
            ).text
            JSONObject(response).getJSONObject("data").getString("value")
        } catch (e: Exception) { null }
    }
}
