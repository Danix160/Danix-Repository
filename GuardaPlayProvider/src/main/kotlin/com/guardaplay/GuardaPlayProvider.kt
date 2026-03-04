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
        Log.d("GP_DEBUG", "Inizio analisi pagina: $data")
        val response = app.get(data)
        val html = response.text
        val document = response.document

        val candidateUrls = mutableSetOf<String>()

        // 1. Estrazione da Iframe (inclusi attributi lazy-load)
        document.select("iframe").forEach { iframe ->
            listOf("src", "data-src", "data-litespeed-src").forEach { attr ->
                val src = iframe.attr(attr)
                if (src.isNotEmpty()) candidateUrls.add(src)
            }
        }

        // 2. Estrazione tramite Regex per link offuscati o in script
        // Gestisce anche la decodifica di HTML entities come &#038;
        val regex = Regex("""https?://[^\s"'<>]+(?:trembed|trid=|embed)[^\s"'<>]+""")
        regex.findAll(html).forEach { match ->
            val clean = match.value.replace("&#038;", "&").replace("\\/", "/")
            candidateUrls.add(clean)
        }

        candidateUrls.filter { it.contains("trid=") || it.contains("trembed") || !it.contains("facebook.com") }.forEach { url ->
            processVideoSource(url, data, subtitleCallback, callback)
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
        Log.d("GP_DEBUG", "Analizzando sorgente: $cleanUrl")

        if (cleanUrl.contains("guardaplay.space") && (cleanUrl.contains("trid=") || cleanUrl.contains("trembed"))) {
            try {
                // Carichiamo la pagina intermedia del player
                val res = app.get(cleanUrl, headers = mapOf("Referer" to referer))
                val docHtml = res.text
                val doc = res.document

                // Tentativo A: Cerca m3u8 diretto negli script
                val m3u8Match = Regex("""["'](http[^"']+\.m3u8[^"']*)""").find(docHtml)
                
                // Tentativo B: Cerca un iframe verso un hoster esterno (es. MixDrop, Fastream)
                val nestedIframe = doc.selectFirst("iframe")?.attr("src") 
                    ?: doc.selectFirst("iframe")?.attr("data-src")

                if (m3u8Match != null) {
                    val finalUrl = m3u8Match.groupValues[1].replace("\\/", "/")
                    Log.d("GP_DEBUG", "Link M3U8 Finale trovato: $finalUrl")
                    generateFinalLink(finalUrl, cleanUrl, callback)
                } else if (nestedIframe != null) {
                    Log.d("GP_DEBUG", "Trovato iframe nidificato verso: $nestedIframe")
                    loadExtractor(nestedIframe, cleanUrl, subtitleCallback, callback)
                } else {
                    Log.d("GP_DEBUG", "Nessuna risorsa utile in questa sottopagina")
                }
            } catch (e: Exception) {
                Log.e("GP_DEBUG", "Errore nel processing interno: ${e.message}")
            }
        } else {
            // Se è un link esterno noto, delega agli estrattori di CloudStream
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
