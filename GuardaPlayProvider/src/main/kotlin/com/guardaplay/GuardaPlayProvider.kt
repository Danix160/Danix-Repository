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
        Log.d("GP_DEBUG", "Inizio analisi profonda: $data")
        val response = app.get(data)
        val html = response.text
        val document = response.document

        val candidateUrls = mutableSetOf<String>()

        // 1. Estrazione Iframe (inclusi lazy-load)
        document.select("iframe").forEach { iframe ->
            listOf("src", "data-src", "data-litespeed-src", "data-lazy-src").forEach { attr ->
                val src = iframe.attr(attr)
                if (src.isNotEmpty()) candidateUrls.add(src)
            }
        }

        // 2. Regex per link nascosti negli script o attributi data
        val regex = Regex("""https?://[^\s"'<>]+(?:trembed|trid=|embed|loadm\.cam)[^\s"'<>]+""")
        regex.findAll(html).forEach { match ->
            val clean = match.value.replace("&#038;", "&")
                .replace("\\/", "/")
                .replace(Regex("""\\$"""), "")
            candidateUrls.add(clean)
        }

        // 3. Processamento candidati
        candidateUrls.forEach { url ->
            if (!url.contains("facebook.com") && !url.contains("twitter.com")) {
                processVideoSource(url, data, subtitleCallback, callback)
            }
        }

        return true
    }

    private suspend fun processVideoSource(
        url: String,
        baseReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var cleanUrl = if (url.startsWith("//")) "https:$url" else url
        cleanUrl = cleanUrl.trim().removeSurrounding("\"", "'")
        
        Log.d("GP_DEBUG", "Analisi sorgente: $cleanUrl")

        // Gestione interna GuardaPlay (trembed/trid)
        if (cleanUrl.contains("guardaplay.space") && (cleanUrl.contains("trid=") || cleanUrl.contains("trembed"))) {
            try {
                val res = app.get(cleanUrl, headers = mapOf("Referer" to baseReferer))
                val docHtml = res.text
                
                val m3u8Match = Regex("""["'](http[^"']+\.m3u8[^"']*)""").find(docHtml)
                if (m3u8Match != null) {
                    val finalUrl = m3u8Match.groupValues[1].replace("\\/", "/")
                    generateFinalLink(finalUrl, cleanUrl, baseReferer, callback)
                } else {
                    val nestedIframe = res.document.selectFirst("iframe")?.attr("src") 
                        ?: res.document.selectFirst("iframe")?.attr("data-src")
                    
                    if (nestedIframe != null) {
                        processVideoSource(nestedIframe, cleanUrl, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                Log.e("GP_DEBUG", "Errore step interno: ${e.message}")
            }
        } 
        // Gestione LoadM
        else if (cleanUrl.contains("loadm.cam")) {
            try {
                val res = app.get(cleanUrl, headers = mapOf("Referer" to baseReferer))
                val videoRegex = Regex("""(?:file|source|src)\s*[:=]\s*["'](http[^"']+\.m3u8[^"']*)["']""")
                val match = videoRegex.find(res.text)
                
                if (match != null) {
                    generateFinalLink(match.groupValues[1].replace("\\/", "/"), cleanUrl, baseReferer, callback)
                }
            } catch (e: Exception) {
                Log.e("GP_DEBUG", "Errore LoadM: ${e.message}")
            }
        }
        // Altri estrattori
        else {
            loadExtractor(cleanUrl, baseReferer, subtitleCallback, callback)
        }
    }

    private suspend fun generateFinalLink(
        videoUrl: String, 
        currentUrl: String, 
        baseReferer: String, 
        callback: (ExtractorLink) -> Unit
    ) {
        val uri = java.net.URI(currentUrl)
        
        callback.invoke(
            newExtractorLink(
                name = "GuardaPlay Server",
                source = this.name,
                url = videoUrl
            ) {
                this.quality = Qualities.Unknown.value
                this.type = ExtractorLinkType.M3U8
                this.headers = mapOf(
                    "Referer" to baseReferer,
                    "Origin" to "${uri.scheme}://${uri.host}",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                )
            }
        )
    }
}
