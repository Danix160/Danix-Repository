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
        val doc = app.get(url).document
        
        var title = doc.selectFirst("h1")?.text()?.trim() ?: doc.title().trim()
        title = title.split(" – ").get(0).replace(Regex("(?i) streaming"), "").replace("- cineblog001", "", true).trim()
        
        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
            ?: fixUrlNull(doc.selectFirst(".block-th-cover img")?.attr("src"))
            
        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content") 
            ?: doc.selectFirst(".story, .description")?.text()

        // Identificazione Serie TV e ID Player
        val iframeSrc = doc.selectFirst("#player-iframe")?.attr("src") ?: ""
        val seriesId = Regex("""/tv/(\d+)""").find(iframeSrc)?.groupValues?.get(1)
        val isSerie = seriesId != null || url.contains("/detail/tv-")

        return if (isSerie) {
            val episodesList = mutableListOf<Episode>()
            doc.select(".dropdown-item[data-episode]").forEach { item ->
                val epData = item.attr("data-episode") 
                val parts = epData.split("-")
                val s = parts.getOrNull(0) ?: "1"
                val e = parts.getOrNull(1) ?: "1"
                
                // URL Virtuale per vixsrc.to
                val epUrl = if (seriesId != null) "https://vixsrc.to/tv/$seriesId/$s/$e" else url

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
        
        // Definiamo il referer principale
        val headers = mapOf("Referer" to "$mainUrl/")

        try {
            // Carichiamo la pagina (vixsrc o cineblog) con gli header corretti
            val response = app.get(data, headers = headers).text
            
            // 1. Cerchiamo URL negli script (spesso criptati o in variabili JS)
            val regex = Regex("""https?://[^\s"'<>]+(?:supervideo|dropload|mixdrop|m1xdrop|dr0pstream|vidsrc|vixsrc|vapi|vcdn)[^\s"'<>]*""")
            regex.findAll(response).forEach { match ->
                finalLinks.add(fixUrl(match.value))
            }
            
            // 2. Se non troviamo nulla, proviamo a cercare iframe annidati
            val doc = org.jsoup.Jsoup.parse(response)
            doc.select("iframe").forEach { el ->
                val src = el.attr("src")
                if (src.isNotBlank() && !src.contains("guardahd")) {
                    finalLinks.add(fixUrl(src))
                }
            }
        } catch (e: Exception) { 
            Log.e("Cineblog", "LoadLinks Error: ${e.message}")
        }

        // Se vixsrc restituisce altri link, processiamoli
        finalLinks.distinct().forEach { link ->
            val clean = link.replace("?download", "")
            when {
                clean.contains("supervideo") -> 
                    SupervideoExtractor().getUrl(clean, clean, subtitleCallback, callback)
                
                clean.contains("dropload") || clean.contains("dr0pstream") -> 
                    DroploadExtractor().getUrl(clean, clean, subtitleCallback, callback)
                
                // Mixdrop richiede spesso il referer del player originale
                clean.contains("mixdrop") || clean.contains("m1xdrop") ->
                    loadExtractor(clean, data, subtitleCallback, callback)

                else -> loadExtractor(clean, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
