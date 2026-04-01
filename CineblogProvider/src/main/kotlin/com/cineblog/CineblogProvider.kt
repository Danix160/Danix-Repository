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
// PROVIDER PRINCIPALE (Versione Aggiornata 2026)
// =============================================================================

class CineblogProvider : MainAPI() {
    override var mainUrl = "https://cineblog001.ovh" 
    override var name = "Cineblog01"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val homePageList = mutableListOf<HomePageList>()
        val mainDoc = app.get(mainUrl).document
        
        // Selettore aggiornato basato sulla struttura .block-th-cover
        val featured = mainDoc.select(".block-th-cover").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        
        if (featured.isNotEmpty()) {
            homePageList.add(HomePageList("Ultimi Inserimenti", featured))
        }
        
        return newHomePageResponse(homePageList, false)
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val allResults = mutableListOf<SearchResponse>()
        try {
            // URL di ricerca confermato: /search?q=...
            val searchUrl = "$mainUrl/search?q=${query.replace(" ", "+")}"
            val doc = app.get(searchUrl).document
            
            // Usiamo il nuovo selettore specifico per le card
            val searchItems = doc.select(".block-th-cover").mapNotNull { it.toSearchResult() }
            allResults.addAll(searchItems)
        } catch (e: Exception) { 
            Log.e("Cineblog", "Search Error: ${e.message}")
        }
        return allResults.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Estrazione link e titolo
        val a = this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        
        // Pulizia link non validi
        if (href.contains("/tags/") || href.contains("/category/") || href == mainUrl) return null

        val img = this.selectFirst("img")
        var title = a.attr("title").ifEmpty { img?.attr("alt") ?: "Nessun Titolo" }
        
        // Pulizia Titolo Standard
        title = title.split(" – ").get(0)
            .split(" - ").get(0)
            .replace(Regex("(?i) streaming"), "")
            .trim()
        
        // GESTIONE POSTER (Risoluzione percorsi relativi)
        val rawImg = img?.attr("src") ?: img?.attr("data-src")
        val posterUrl = if (rawImg != null && rawImg.startsWith("/")) {
            // Uniamo mainUrl con il percorso relativo e aumentiamo la risoluzione da w200 a w500
            "$mainUrl$rawImg".replace("/w200/", "/w500/")
        } else {
            fixUrlNull(rawImg)
        }
        
        // Riconoscimento Tipo (Serie vs Film)
        return if (href.contains("/detail/tv-") || href.contains("/serie-tv/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        var title = doc.selectFirst("h1")?.text()?.trim() ?: doc.title().trim()
        title = title.split(" – ").get(0)
            .split(" - ").get(0)
            .replace(Regex("(?i) streaming"), "")
            .replace("- cineblog001", "", ignoreCase = true)
            .trim()
        
        // Poster nella pagina interna (spesso dentro .block-th-cover o meta og:image)
        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
            ?: fixUrlNull(doc.selectFirst(".block-th-cover img")?.attr("src"))
            
        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content") 
            ?: doc.selectFirst(".story, .description, .post-content")?.text()

        val isSerie = url.contains("/detail/tv-") || doc.selectFirst(".dropdown-item[data-episode]") != null
        
        return if (isSerie) {
            val episodesList = mutableListOf<Episode>()
            
            // Parsing Episodi dai dropdown
            doc.select(".dropdown-item[data-episode]").forEach { item ->
                val epData = item.attr("data-episode") 
                val parts = epData.split("-")
                val seasonNum = parts.getOrNull(0)?.toIntOrNull() ?: 1
                val epNum = parts.getOrNull(1)?.toIntOrNull() ?: 1
                
                val epName = item.text().trim()
                // Bundle dati per loadLinks
                val dataBundle = "$url?s=$seasonNum&e=$epNum"

                episodesList.add(newEpisode(dataBundle) {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = epNum
                })
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) { 
                this.posterUrl = poster
                this.plot = plot 
            }
        } else {
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
        val finalLinks = mutableListOf<String>()
        
        try {
            val doc = app.get(data).document
            
            // Estrazione link da Script Inline
            doc.select("script").forEach { script ->
                val content = script.html()
                val regex = Regex("""https?://[^\s"'<>]+(?:supervideo|dropload|mixdrop|m1xdrop|dr0pstream)[^\s"'<>]*""")
                regex.findAll(content).forEach { match ->
                    finalLinks.add(fixUrl(match.value))
                }
            }
            
            // Fallback: Iframe e attributi data-link
            doc.select("iframe, li[data-link], a[data-link]").forEach { el ->
                val link = el.attr("src").ifEmpty { el.attr("data-link") }
                if (link.isNotBlank() && !link.contains("guardahd")) {
                    finalLinks.add(fixUrl(link))
                }
            }
        } catch (e: Exception) { }

        // Priorità Supervideo e Dropload
        val prioritizedLinks = finalLinks.distinct().sortedByDescending { 
            it.contains("supervideo") || it.contains("dropload") 
        }

        prioritizedLinks.forEach { link ->
            val clean = link.replace("?download", "")
            when {
                clean.contains("supervideo") -> 
                    SupervideoExtractor().getUrl(clean, clean, subtitleCallback, callback)
                
                clean.contains("dropload") || clean.contains("dr0pstream") -> 
                    DroploadExtractor().getUrl(clean, clean, subtitleCallback, callback)
                
                else -> loadExtractor(clean, clean, subtitleCallback, callback)
            }
        }
        return true
    }
}
