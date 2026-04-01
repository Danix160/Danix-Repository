package com.cineblog

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import org.jsoup.nodes.Element

// =============================================================================
// ESTRATTORI DEDICATI
// =============================================================================

class DroploadExtractor : ExtractorApi() {
    override var name = "Dropload"
    override var mainUrl = "https://dropload.tv"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val body = app.get(url).body.string()
            val unpacked = getAndUnpack(body)
            val videoUrl = Regex("""(?:file|src)\s*:\s*"([^"]+(?:\.m3u8|\.mp4)[^"]*)"""").find(unpacked)?.groupValues?.get(1)

            videoUrl?.let {
                callback.invoke(newExtractorLink(name, name, it, if(it.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = url
                })
            }
        } catch (e: Exception) { Log.e("Dropload", "Error: ${e.message}") }
    }
}

class SupervideoExtractor : ExtractorApi() {
    override var name = "Supervideo"
    override var mainUrl = "https://supervideo.cc"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val response = app.get(url).body.string()
            val unpacked = getAndUnpack(response)
            val videoUrl = Regex("""file\s*:\s*"([^"]+.(?:m3u8|mp4)[^"]*)"""").find(unpacked)?.groupValues?.get(1)

            videoUrl?.let {
                callback.invoke(newExtractorLink(name, name, it, if(it.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = url
                })
            }
        } catch (e: Exception) { Log.e("Supervideo", "Error: ${e.message}") }
    }
}

// =============================================================================
// PROVIDER PRINCIPALE (Versione Integrata 2026)
// =============================================================================

class CineblogProvider : MainAPI() {
    override var mainUrl = "https://cineblog001.ovh" 
    override var name = "Cineblog01"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val mainDoc = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()
        
        val latest = mainDoc.select(".block-th-cover").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        if (latest.isNotEmpty()) homePageList.add(HomePageList("Ultimi Aggiunti", latest))
        
        return newHomePageResponse(homePageList, false)
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val searchUrl = "$mainUrl/search?q=${query.replace(" ", "+")}"
            val doc = app.get(searchUrl).document
            doc.select(".block-th-cover").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        } catch (e: Exception) { emptyList() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        if (href.contains("/tags/") || href.contains("/category/") || href == mainUrl) return null

        val img = this.selectFirst("img")
        var title = a.attr("title").ifEmpty { img?.attr("alt") ?: "Senza Titolo" }
        
        title = title.split(" – ").get(0).split(" - ").get(0).replace(Regex("(?i) streaming"), "").trim()
        
        val rawImg = img?.attr("src") ?: img?.attr("data-src")
        val posterUrl = if (rawImg != null && rawImg.startsWith("/")) {
            "$mainUrl$rawImg".replace("/w200/", "/w500/")
        } else fixUrlNull(rawImg)
        
        return if (href.contains("/tv-") || href.contains("/serie-tv/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

   override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url)
        val doc = response.document
        
        val title = doc.selectFirst("h1")?.text()?.trim() ?: doc.title()
        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content")

        // 1. TROVA L'ID DELLA SERIE DALL'IFRAME (Fondamentale per le serie)
        val iframe = doc.selectFirst("#player-iframe")
        val iframeSrc = iframe?.attr("src") ?: ""
        // Esempio iframeSrc: https://vixsrc.to/tv/2098/1/1 -> ID è 2098
        val seriesId = Regex("""/tv/(\d+)""").find(iframeSrc)?.groupValues?.get(1)

        val isSerie = seriesId != null || doc.selectFirst(".series-select") != null

        return if (isSerie) {
            val episodesList = mutableListOf<Episode>()
            
            // 2. PARSING DEGLI EPISODI
            doc.select("span[data-episode]").forEach { item ->
                val epData = item.attr("data-episode") // es. "1-1"
                val parts = epData.split("-")
                val s = parts.getOrNull(0) ?: "1"
                val e = parts.getOrNull(1) ?: "1"
                
                // COSTRUIAMO IL LINK DIRETTO AL PLAYER ESTERNO
                // Questo link verrà passato a loadLinks
                val epUrl = "https://vixsrc.to/tv/$seriesId/$s/$e"

                episodesList.add(newEpisode(epUrl) {
                    this.name = item.text().trim()
                    this.season = s.toIntOrNull()
                    this.episode = e.toIntOrNull()
                })
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) { 
                this.posterUrl = poster
                this.plot = plot 
            }
        } else {
            // Per i film usiamo l'URL della pagina stessa
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 3. LOGICA DI ESTRAZIONE DIFFERENZIATA
        val finalLinks = mutableListOf<String>()

        // Se l'URL è di vixsrc.to (Serie TV), serve il Referer di Cineblog
        val headers = mapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        )

        try {
            val responseText = app.get(data, headers = headers).text
            
            // Cerchiamo i link degli host (Supervideo, Dropload, ecc)
            // Usiamo una regex che pulisce anche i caratteri di escape \/
            val regex = Regex("""https?://[^\s"'<>]+(?:supervideo|dropload|mixdrop|m1xdrop|dr0pstream)[^\s"'<>]*""")
            regex.findAll(responseText).forEach { match ->
                finalLinks.add(match.value.replace("\\/", "/"))
            }
            
            // Se non trova nulla con la regex, proviamo l'estrazione standard Cloudstream
            if (finalLinks.isEmpty()) {
                loadExtractor(data, data, subtitleCallback, callback)
            }

        } catch (e: Exception) {
            Log.e("Cineblog", "Errore loadLinks: ${e.message}")
        }

        // 4. INVIO DEI LINK AGLI ESTRATTORI
        finalLinks.distinct().forEach { link ->
            when {
                link.contains("supervideo") -> 
                    SupervideoExtractor().getUrl(link, link, subtitleCallback, callback)
                link.contains("dropload") || link.contains("dr0pstream") -> 
                    DroploadExtractor().getUrl(link, link, subtitleCallback, callback)
                else -> loadExtractor(link, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
