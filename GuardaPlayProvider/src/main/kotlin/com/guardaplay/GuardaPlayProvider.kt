package com.guardaplay

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class GuardaPlayProvider : MainAPI() {
    override var mainUrl = "https://guardaplay.space"
    override var name = "GuardaPlay"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ultimi Film",
        "$mainUrl/category/animazione/" to "Animazione",
        "$mainUrl/category/azione/" to "Azione",
        "$mainUrl/category/commedia/" to "Commedia",
        "$mainUrl/category/horror/" to "Horror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        val home = document.select("article.movies").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("article.movies").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".entry-title")?.text() ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val posterUrl = selectFirst("img")?.attr("src")?.let { src ->
            if (src.startsWith("//")) "https:$src" else src
        }
        
        return newMovieSearchResponse(title, href, TvType.Movie) { 
            this.posterUrl = posterUrl 
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: ""
        val poster = document.selectFirst(".post-thumbnail img")?.attr("src")?.let { src ->
            if (src.startsWith("//")) "https:$src" else src
        }
        val plot = document.selectFirst(".description p")?.text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Parsing basato sull'HTML fornito: cerca gli iframe nelle opzioni
        val options = document.select("aside#aa-options div.video iframe")
        
        options.forEach { iframe ->
            val iframeUrl = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (iframeUrl.isNotEmpty()) {
                processVideoSource(iframeUrl, data, subtitleCallback, callback)
            }
        }
        return true
    }

    private suspend fun processVideoSource(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = if (url.startsWith("//")) "https:$url" else url

        if (cleanUrl.contains("trembed") || cleanUrl.contains("trid=")) {
            try {
                val iframeDoc = app.get(cleanUrl, headers = mapOf("Referer" to referer)).document
                
                // Cerca iframe annidato o file diretto
                val nestedIframe = iframeDoc.selectFirst("iframe")?.attr("src")
                val scriptData = iframeDoc.select("script").html()
                val directFile = Regex("""file:\s*["']([^"']+)""").find(scriptData)?.groupValues?.get(1)
                
                if (nestedIframe != null) {
                    loadExtractor(nestedIframe, cleanUrl, subtitleCallback, callback)
                } else if (directFile != null) {
                    generateFinalLink(directFile, cleanUrl, callback)
                }
            } catch (e: Exception) {
                Log.e("GP_DEBUG", "Errore: ${e.message}")
            }
        } else {
            loadExtractor(cleanUrl, referer, subtitleCallback, callback)
        }
    }

    private suspend fun generateFinalLink(videoUrl: String, url: String, callback: (ExtractorLink) -> Unit) {
        callback.invoke(
            newExtractorLink(
                name = "Server HD",
                source = this.name,
                url = videoUrl,
            ) {
                this.quality = Qualities.P1080.value
                this.type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                this.headers = mapOf(
                    "Referer" to url,
                    "Origin" to "https://guardaplay.space"
                )
            }
        )
    }
}
