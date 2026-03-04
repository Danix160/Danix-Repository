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

    // Gestione Sessione/Cookie
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
        Log.d("GP_DEBUG", "Inizio analisi link: $data")
        val response = app.get(data, cookies = sessionCookies)
        val document = response.document

        val candidateUrls = mutableSetOf<String>()

        // 1. Estrazione iframe (anche lazy-loaded)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }.ifEmpty { iframe.attr("data-litespeed-src") }
            if (src.isNotEmpty() && !src.contains("facebook") && !src.contains("twitter")) {
                candidateUrls.add(src)
            }
        }

        // 2. Regex per link nascosti negli script
        val scriptText = document.select("script").joinToString { it.data() }
        Regex("""https?://[^\s"'<>]+(?:trembed|trid=|loadm\.cam)[^\s"'<>]+""").findAll(scriptText).forEach { 
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
        
        // Fix specifico LoadM: Il sito usa l'hash per caricare via JS. Proviamo a bypassarlo
        if (cleanUrl.contains("loadm.cam/#")) {
            val id = cleanUrl.substringAfter("#")
            // Proviamo a richiedere la versione video o embed direttamente
            cleanUrl = "https://loadm.cam/v/$id" 
        }

        Log.d("GP_DEBUG", "Tentativo sorgente: $cleanUrl")

        try {
            val res = app.get(cleanUrl, headers = mapOf("Referer" to referer), cookies = sessionCookies)
            val html = res.text

            // Regex flessibile per m3u8 (cerca sia con virgolette singole che doppie)
            val m3u8Match = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(html)

            if (m3u8Match != null) {
                val finalVideoUrl = m3u8Match.groupValues[1].replace("\\/", "/")
                val uri = java.net.URI(cleanUrl)
                
                callback.invoke(
                    newExtractorLink(
                        name = "GuardaPlay Server",
                        source = this.name,
                        url = finalVideoUrl
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.M3U8
                        this.headers = mapOf(
                            "Referer" to cleanUrl,
                            "Origin" to "${uri.scheme}://${uri.host}",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                        )
                    }
                )
            } else if (html.contains("iframe")) {
                // Se non c'è il video ma c'è un altro iframe, scava ancora
                val nestedIframe = res.document.selectFirst("iframe")?.attr("src")
                if (!nestedIframe.isNullOrEmpty() && nestedIframe != cleanUrl) {
                    processVideoSource(nestedIframe, cleanUrl, callback)
                }
            }
        } catch (e: Exception) {
            Log.e("GP_DEBUG", "Errore sorgente: ${e.message}")
        }
    }
}
