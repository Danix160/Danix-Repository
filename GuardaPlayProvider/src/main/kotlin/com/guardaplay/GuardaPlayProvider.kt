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
        Log.d("GP_DEBUG", "Caricamento link per: $data")
        val response = app.get(data)
        val document = response.document
        val html = response.text

        // 1. Ricerca tramite Iframe (Standard)
        val iframes = document.select("iframe")
        Log.d("GP_DEBUG", "Trovati ${iframes.size} iframe")

        iframes.forEach { iframe ->
            val iframeUrl = iframe.attr("src")
                .ifEmpty { iframe.attr("data-src") }
                .ifEmpty { iframe.attr("data-litespeed-src") }
            
            if (iframeUrl.isNotEmpty() && !iframeUrl.contains("facebook.com")) {
                Log.d("GP_DEBUG", "Iframe trovato: $iframeUrl")
                processVideoSource(iframeUrl, data, subtitleCallback, callback)
            }
        }

        // 2. Ricerca tramite Regex (Per link nascosti negli script)
        // Questa regex cerca link che portano a player comuni
        val regex = Regex("""https?://[^\s"'<>]+(?:trembed|loadm|trid=|embed)[^\s"'<>]+""")
        val matches = regex.findAll(html).toList()
        Log.d("GP_DEBUG", "Trovati ${matches.size} link tramite Regex")

        matches.forEach { match ->
            val foundUrl = match.value.replace("\\/", "/")
            Log.d("GP_DEBUG", "Link Regex trovato: $foundUrl")
            processVideoSource(foundUrl, data, subtitleCallback, callback)
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

        if (cleanUrl.contains("loadm.cam") || cleanUrl.contains("trembed") || cleanUrl.contains("trid=")) {
            try {
                Log.d("GP_DEBUG", "Analizzando sorgente: $cleanUrl")
                val response = app.get(cleanUrl, headers = mapOf("Referer" to referer))
                val docHtml = response.text
                
                // Cerca il master.m3u8 nel testo o nel tag source
                val m3u8Match = Regex("""["'](http[^"']+\.m3u8[^"']*)""").find(docHtml)
                val directSource = response.document.selectFirst("video source")?.attr("src")
                
                val finalUrl = directSource ?: m3u8Match?.groupValues?.get(1)

                if (finalUrl != null) {
                    val decodedUrl = finalUrl.replace("\\/", "/")
                    Log.d("GP_DEBUG", "FINAL LINK TROVATO: $decodedUrl")
                    generateFinalLink(decodedUrl, cleanUrl, callback)
                } else {
                    Log.d("GP_DEBUG", "Nessun m3u8 trovato in questa sorgente")
                }
            } catch (e: Exception) {
                Log.e("GP_DEBUG", "Errore durante processVideoSource: ${e.message}")
            }
        } else {
            // Se è un hoster conosciuto (Mixdrop, Supervideo ecc), usa gli estrattori di sistema
            loadExtractor(cleanUrl, referer, subtitleCallback, callback)
        }
    }

    private suspend fun generateFinalLink(videoUrl: String, url: String, callback: (ExtractorLink) -> Unit) {
        val uri = java.net.URI(url)
        val baseReferer = "${uri.scheme}://${uri.host}/"

        val link = newExtractorLink(
            name = "GuardaPlay HD",
            source = this.name,
            url = videoUrl,
        ) {
            this.quality = Qualities.Unknown.value
            this.type = ExtractorLinkType.M3U8
            this.headers = mapOf(
                "Referer" to baseReferer,
                "Origin" to "${uri.scheme}://${uri.host}",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            )
        }
        callback.invoke(link)
    }
}
