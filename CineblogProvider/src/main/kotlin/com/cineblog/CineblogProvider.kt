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
// PROVIDER PRINCIPALE (Adattato al nuovo layout 2026)
// =============================================================================

class CineblogProvider : MainAPI() {
    // Dai file emerge l'uso dell'estensione .ovh
    override var mainUrl = "https://cineblog001.ovh" 
    override var name = "Cineblog01"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val homePageList = mutableListOf<HomePageList>()
        val mainDoc = app.get(mainUrl).document
        
        // Nuovo selettore per le card dei film/serie presenti nella home fornita
        val featured = mainDoc.select(".movie-card, .promo-item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        if (featured.isNotEmpty()) homePageList.add(HomePageList("In Evidenza", featured))
        
        // Sezione fallback per gli ultimi elementi
        val latest = mainDoc.select(".grid-item, .poster-wrapper").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        if (latest.isNotEmpty()) homePageList.add(HomePageList("Ultimi Aggiunti", latest))
        
        return newHomePageResponse(homePageList, false)
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val allResults = mutableListOf<SearchResponse>()
        try {
            // Dai sorgenti emerge che la ricerca usa una GET su /search?q=query
            val searchUrl = "$mainUrl/search?q=${query.replace(" ", "+")}"
            val doc = app.get(searchUrl).document
            
            val pagedResults = doc.select(".movie-card, .grid-item, .poster-wrapper").mapNotNull { it.toSearchResult() }
            allResults.addAll(pagedResults)
        } catch (e: Exception) { }
        return allResults.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Cerca il link principale all'interno della card
        val a = this.selectFirst("a.meta-title, a.poster-link, a") ?: return null
        val href = fixUrl(a.attr("href"))
        if (href.contains("/tags/") || href.contains("/category/") || href == mainUrl) return null

        var title = a.text().trim().ifEmpty { 
            this.selectFirst(".title, h2, h3")?.text() ?: a.attr("title") 
        } ?: "Senza Titolo"
        
        // Pulizia Titolo come richiesto
        title = title.split(" – ").get(0)
            .split(" - ").get(0)
            .split(" [").get(0)
            .replace(Regex("(?i) streaming"), "")
            .trim()
        
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src") ?: img?.attr("src"))
        
        // Riconoscimento se è serie o film dal link (es: /detail/tv-... o /detail/film-...)
        return if (href.contains("/detail/tv-") || href.contains("/serie-tv/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        // Estrazione titolo dall'h1 o dal tag title della pagina
        var title = doc.selectFirst("h1")?.text()?.trim() ?: doc.title().trim()
        title = title.split(" – ").get(0)
            .split(" - ").get(0)
            .replace(Regex("(?i) streaming"), "")
            .replace("- cineblog001", "", ignoreCase = true)
            .trim()
        
        // Il poster si trova negli OpenGraph o nei metadati principali
        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content") 
            ?: doc.selectFirst("img.poster, .movie-cover img")?.attr("src"))
            
        // Trama estratta dalla descrizione dei metadati o dal tag .story
        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content") 
            ?: doc.selectFirst(".story, .description")?.text()

        // Controllo se è una serie TV in base alla presenza del menu dropdown degli episodi
        val isSerie = doc.selectFirst(".dropdown-item[data-episode]") != null || url.contains("/detail/tv-")
        
        return if (isSerie) {
            val episodesList = mutableListOf<Episode>()
            
            // Estraiamo gli episodi dai dropdown presenti nel file 'serie tv.txt'
            doc.select(".dropdown-item[data-episode]").forEach { item ->
                val epData = item.attr("data-episode") // Formato "1-1" (Stagione-Episodio)
                val parts = epData.split("-")
                val seasonNum = parts.getOrNull(0)?.toIntOrNull() ?: 1
                val epNum = parts.getOrNull(1)?.toIntOrNull() ?: 1
                
                // Nel nuovo sito i link vengono generati via JS combinando ID ed episodi. 
                // Passiamo i metadati necessari per ricostruire o trovare il link in loadLinks
                val epName = item.text().trim()
                val dataBundle = "$url?s=$seasonNum&e=$epNum"

                episodesList.add(newEpisode(dataBundle) {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = epNum
                    this.posterUrl = poster 
                })
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) { 
                this.posterUrl = poster
                this.plot = plot 
            }
        } else {
            // Per i film passiamo l'URL base alla loadLinks
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
            // Carichiamo la pagina passata
            val doc = app.get(data).document
            
            // Il nuovo sito non stampa direttamente i link in chiaro nel sorgente.
            // Esamina tutti i tag script per cercare stringhe riconducibili ai nostri host
            doc.select("script").forEach { script ->
                val content = script.html()
                
                // Ricerca Regex per scovare URL degli host supportati scritti nell'inline JS
                val regex = Regex("""https?://[^\s"'<>]+(?:supervideo|dropload|mixdrop)[^\s"'<>]*""")
                regex.findAll(content).forEach { match ->
                    finalLinks.add(fixUrl(match.value))
                }
            }
            
            // Come fallback, cerchiamo negli iframe e nei canonici tag data-link
            doc.select("iframe, li[data-link], a[data-link]").forEach { el ->
                val link = el.attr("src").ifEmpty { el.attr("data-link") }
                if (link.isNotBlank() && !link.contains("guardahd")) {
                    finalLinks.add(fixUrl(link))
                }
            }
        } catch (e: Exception) { }

        // PRIORITÀ ASSOLUTA: Supervideo per primo
        val prioritizedLinks = finalLinks.distinct().sortedByDescending { it.contains("supervideo") }

        prioritizedLinks.forEach { link ->
            val clean = link.replace("?download", "")
            when {
                clean.contains("supervideo") -> 
                    SupervideoExtractor().getUrl(clean, clean, subtitleCallback, callback)
                
                clean.contains("mixdrop") || clean.contains("m1xdrop") -> 
                    loadExtractor(clean, clean, subtitleCallback, callback)
                
                clean.contains("dropload") || clean.contains("dr0pstream") -> 
                    DroploadExtractor().getUrl(clean, clean, subtitleCallback, callback)
                
                else -> loadExtractor(clean, clean, subtitleCallback, callback)
            }
        }
        return true
    }
}
