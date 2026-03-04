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
        
        // Cerchiamo tutti gli iframe nella pagina
        val iframes = document.select("iframe")
        
        iframes.forEach { iframe ->
            val iframeUrl = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
            if (iframeUrl.isNotEmpty() && !iframeUrl.contains("facebook.com")) {
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

        // Gestione specifica per loadm.cam e trembed
        if (cleanUrl.contains("loadm.cam") || cleanUrl.contains("trembed") || cleanUrl.contains("trid=")) {
            try {
                val response = app.get(cleanUrl, headers = mapOf("Referer" to referer))
                val doc = response.document
                
                // 1. Prova a cercare il tag <source src="..."> direttamente
                val directSource = doc.selectFirst("video source")?.attr("src")
                
                // 2. Prova a cercare nel testo (per link m3u8 dinamici)
                val m3u8Match = Regex("""["'](http[^"']+\.m3u8[^"']*)""").find(doc.html())
                
                val finalUrl = directSource ?: m3u8Match?.groupValues?.get(1)

                if (finalUrl != null) {
                    generateFinalLink(finalUrl.replace("\\/", "/"), cleanUrl, callback)
                } else {
                    // Se non trova nulla, prova a cercare un iframe annidato (fallback)
                    val nestedIframe = doc.selectFirst("iframe")?.attr("src")
                    if (nestedIframe != null) {
                        loadExtractor(nestedIframe, cleanUrl, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                Log.e("GP_DEBUG", "Errore estrazione: ${e.message}")
            }
        } else {
            // Se è un provider standard (Mixdrop, ecc)
            loadExtractor(cleanUrl, referer, subtitleCallback, callback)
        }
    }

    private fun generateFinalLink(videoUrl: String, url: String, callback: (ExtractorLink) -> Unit) {
        callback.invoke(
            newExtractorLink(
                name = "GuardaPlay Player",
                source = this.name,
                url = videoUrl,
            ) {
                this.quality = Qualities.Unknown.value
                this.type = ExtractorLinkType.M3U8
                this.headers = mapOf(
                    "Referer" to "https://loadm.cam/", // Fondamentale per loadm.cam
                    "Origin" to "https://loadm.cam",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                )
            }
        )
    }
}
