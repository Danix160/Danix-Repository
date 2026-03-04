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

    private var sessionCookies: Map<String, String> = emptyMap()

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ultimi Film",
        "$mainUrl/category/animazione/" to "Animazione",
        "$mainUrl/category/azione/" to "Azione",
        "$mainUrl/category/commedia/" to "Commedia",
        "$mainUrl/category/horror/" to "Horror"
    )

    private suspend fun getCookies() {
        if (sessionCookies.isEmpty()) {
            try {
                val res = app.get(mainUrl, timeout = 15)
                sessionCookies = res.cookies
            } catch (e: Exception) {
                Log.e("GP_DEBUG", "Errore inizializzazione cookie: ${e.message}")
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        getCookies()
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, cookies = sessionCookies).document
        val home = document.select("article.movies").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        getCookies()
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, cookies = sessionCookies).document
        return document.select("article.movies").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".entry-title")?.text() ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null
        
        // Pulizia URL Poster per evitare errori CoilImgLoader
        val posterUrl = selectFirst("img")?.attr("src")?.let { src ->
            when {
                src.startsWith("//") -> "https:$src"
                src.startsWith("/") -> "https://image.tmdb.org$src" 
                else -> src
            }
        }
        
        return newMovieSearchResponse(title, href, TvType.Movie) { 
            this.posterUrl = posterUrl 
        }
    }

    override suspend fun load(url: String): LoadResponse {
        getCookies()
        val document = app.get(url, cookies = sessionCookies).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: ""
        val poster = document.selectFirst(".post-thumbnail img")?.attr("src")
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
        getCookies()
        val doc = app.get(data, cookies = sessionCookies).document
        
        // Estrazione Server usando la logica Streamflix (#aa-options)
        val serverIframes = doc.select("#aa-options div[id^=options-]").mapIndexedNotNull { _, optionDiv ->
            optionDiv.selectFirst("iframe[data-src]")?.attr("data-src")
                ?: optionDiv.selectFirst("iframe")?.attr("src")
        }.toMutableSet()

        // Fallback: cerca link trembed se #aa-options è vuoto
        if (serverIframes.isEmpty()) {
            val trid = Regex("""trid=(\d+)""").find(doc.html())?.groupValues?.get(1)
            if (trid != null) {
                serverIframes.add("$mainUrl/?trembed=0&trid=$trid&trtype=1")
            }
        }

        if (serverIframes.isEmpty()) return false

        serverIframes.forEach { serverUrl ->
            processVideoSource(serverUrl, data, callback)
        }

        return true
    }

    private suspend fun processVideoSource(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = if (url.startsWith("//")) "https:$url" else url
        
        try {
            // Primo passaggio: Caricamento dell'iframe selezionato
            val embedRes = app.get(cleanUrl, headers = mapOf("Referer" to referer), cookies = sessionCookies)
            val embedDoc = embedRes.document
            
            // Secondo passaggio: Cerca l'iframe del player vero e proprio (spesso LoadM)
            val finalIframe = embedDoc.selectFirst(".Video iframe[src]")?.attr("src")
                ?: embedDoc.selectFirst("iframe[src*='loadm']")?.attr("src")
                ?: cleanUrl

            val videoPageUrl = if (finalIframe.startsWith("//")) "https:$finalIframe" else finalIframe

            // Terzo passaggio: Estrazione del link video finale (.m3u8 o .mp4)
            val videoPageRes = app.get(videoPageUrl, headers = mapOf("Referer" to cleanUrl))
            val html = videoPageRes.text

            val videoLink = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(html)?.groupValues?.get(1)

            if (videoLink != null) {
                val finalVideo = videoLink.replace("\\/", "/")
                val isM3u8 = finalVideo.contains("m3u8")
                
                callback.invoke(
                    newExtractorLink(
                        name = "Server LoadM",
                        source = this.name,
                        url = finalVideo
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        // Headers critici per evitare errori 403/404 durante il play
                        this.headers = mapOf(
                            "Referer" to "https://loadm.cam/",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                        )
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("GP_DEBUG", "Errore in processVideoSource: ${e.message}")
        }
    }
}
