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
    ) [cite: 38, 39]

    private suspend fun getCookies() {
        if (sessionCookies.isEmpty()) {
            try {
                val res = app.get(mainUrl, timeout = 15)
                sessionCookies = res.cookies
            } catch (e: Exception) {
                Log.e("GP_DEBUG", "Errore Cookie: ${e.message}")
            }
        }
    } [cite: 39, 40]

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        getCookies()
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, cookies = sessionCookies).document
        val home = document.select("article.movies, li.post-lst").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    } [cite: 40, 41]

    override suspend fun search(query: String): List<SearchResponse> {
        getCookies()
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, cookies = sessionCookies).document
        return document.select("article.movies, .post-lst li").mapNotNull { it.toSearchResult() }
    } [cite: 41]

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".entry-title")?.text() ?: return null
        val href = selectFirst("a.lnk-blk, a")?.attr("href") ?: return null
        val posterUrl = selectFirst("img")?.attr("src")?.let { src ->
            if (src.startsWith("//")) "https:$src" else src
        }
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    } [cite: 42, 43]

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
    } [cite: 44, 45]

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        getCookies()
        val doc = app.get(data, cookies = sessionCookies).document

        // 1. Estrazione opzioni basata sul tuo HTML (#aa-options)
        val options = doc.select("#aa-options div.video[id^=options-]")
        
        if (options.isEmpty()) {
            val trid = Regex("""trid=(\d+)""").find(doc.html())?.groupValues?.get(1)
            if (trid != null) {
                processVideoSource("$mainUrl/?trembed=0&trid=$trid&trtype=1", data, "Server Alternativo", callback)
                return true
            }
            return false
        } [cite: 46, 47]

        options.forEach { optionDiv ->
            val id = optionDiv.attr("id")
            val iframeUrl = optionDiv.selectFirst("iframe")?.attr("data-src")
                ?: optionDiv.selectFirst("iframe")?.attr("src")
                ?: return@forEach

            // 2. Mapping nome server (es. Loadm) dal menu ITA
            val serverName = doc.select("a[href='#$id'] span.server").text().trim()
                .replace("-ITA", "")
                .ifEmpty { "Opzione ${id.replace("options-", "")}" }

            processVideoSource(iframeUrl, data, serverName, callback)
        } [cite: 47, 48]
        return true
    }

    private suspend fun processVideoSource(
        url: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = if (url.startsWith("//")) "https:$url" else url
        try {
            // Passaggio 1: Pagina intermedia (trembed)
            val embedRes = app.get(cleanUrl, headers = mapOf("Referer" to referer), cookies = sessionCookies)
            val embedDoc = embedRes.document
            
            // Passaggio 2: Cerca il player reale (LoadM o simili)
            val finalIframe = embedDoc.selectFirst(".Video iframe[src]")?.attr("src")
                ?: embedDoc.selectFirst("iframe[src*='loadm']")?.attr("src")
                ?: embedDoc.selectFirst("iframe")?.attr("src")
                ?: cleanUrl [cite: 49, 50]

            val videoPageUrl = if (finalIframe.startsWith("//")) "https:$finalIframe" else finalIframe

            // Passaggio 3: Estrazione del link m3u8/mp4 finale
            val videoPageRes = app.get(videoPageUrl, headers = mapOf("Referer" to cleanUrl))
            val html = videoPageRes.text
            val videoLink = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(html)?.groupValues?.get(1) [cite: 50, 51]

            if (videoLink != null) {
                val finalVideo = videoLink.replace("\\/", "/")
                callback.invoke(
                    newExtractorLink(serverName, this.name, finalVideo) {
                        this.quality = Qualities.Unknown.value
                        this.type = if (finalVideo.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        this.headers = mapOf(
                            "Referer" to videoPageUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                        )
                    }
                ) [cite: 52, 53, 54]
            }
        } catch (e: Exception) {
            Log.e("GP_DEBUG", "Errore extraction: ${e.message}")
        }
    }
}
