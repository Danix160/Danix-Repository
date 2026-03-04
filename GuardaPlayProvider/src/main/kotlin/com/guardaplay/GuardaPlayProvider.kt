package com.guardaplay

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element

class GuardaPlayProvider : MainAPI() {
    override var mainUrl = "https://guardaplay.space"
    override var name = "GuardaPlay"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    // Cache per i cookie di sessione per evitare blocchi/404
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
            val res = app.get(mainUrl)
            sessionCookies = res.cookies
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
        val posterUrl = selectFirst("img")?.attr("src")?.let { src ->
            if (src.startsWith("//")) "https:$src" else src
        }
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun load(url: String): LoadResponse {
        getCookies()
        val document = app.get(url, cookies = sessionCookies).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: ""
        val poster = document.selectFirst(".post-thumbnail img")?.attr("src")
        val plot = document.selectFirst(".description p")?.text()
        val trailer = document.selectFirst("iframe[src*='youtube']")?.attr("src")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        getCookies()
        Log.d("GP_DEBUG", "Analisi pagina: $data")
        val response = app.get(data, cookies = sessionCookies)
        val document = response.document

        val candidateUrls = mutableSetOf<String>()

        // 1. Estrazione ID dal sistema trembed (trid)
        val html = document.html()
        val trid = Regex("""trid=(\d+)""").find(html)?.groupValues?.get(1)
        if (trid != null) {
            candidateUrls.add("$mainUrl/?trembed=0&trid=$trid&trtype=1")
        }

        // 2. Ricerca diretta di loadm.cam negli script
        val scriptText = document.select("script").joinToString { it.data() }
        Regex("""https?://loadm\.cam/[^\s"'<>]+""").findAll(scriptText + html).forEach { 
            candidateUrls.add(it.value.replace("\\/", "/")) 
        }

        candidateUrls.forEach { url ->
            processVideoSource(url, data, callback)
        }

        return true
    }

    private suspend fun processVideoSource(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        var cleanUrl = if (url.startsWith("//")) "https:$url" else url
        
        // Correzione URL LoadM per evitare il 404
        if (cleanUrl.contains("loadm.cam")) {
            val id = if (cleanUrl.contains("/v/")) {
                cleanUrl.substringAfter("/v/").substringBefore("/")
            } else {
                cleanUrl.substringAfter("#", "").ifEmpty { cleanUrl.substringAfterLast("/") }
            }
            // Spesso l'endpoint /e/ (embed) è più stabile di /v/ (video) per il bypass
            cleanUrl = "https://loadm.cam/e/$id"
        }

        Log.d("GP_DEBUG", "Tentativo sorgente: $cleanUrl")

        try {
            // È fondamentale inviare Referer e User-Agent corretti per non ricevere 404
            val res = app.get(cleanUrl, headers = mapOf(
                "Referer" to referer,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            ), cookies = sessionCookies)

            val pageContent = res.text

            // Regex per trovare il file video (m3u8 o mp4)
            val videoUrl = Regex("""file\s*[:=]\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(pageContent)?.groupValues?.get(1)
                ?: Regex("""src\s*[:=]\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(pageContent)?.groupValues?.get(1)

            if (videoUrl != null) {
                val finalVideo = videoUrl.replace("\\/", "/")
                callback.invoke(
                    newExtractorLink(
                        name = "GuardaPlay Server",
                        source = this.name,
                        url = finalVideo
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.type = if (finalVideo.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.Direct
                        // Il file video finale spesso richiede il referer del server di streaming
                        this.headers = mapOf("Referer" to "https://loadm.cam/")
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("GP_DEBUG", "Errore sorgente $cleanUrl: ${e.message}")
        }
    }
}
