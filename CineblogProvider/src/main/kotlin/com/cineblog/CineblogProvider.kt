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
// PROVIDER PRINCIPALE
// =============================================================================

class CineblogProvider : MainAPI() {
    override var mainUrl = "https://cineblog001.makeup"
    override var name = "Cineblog01"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val homePageList = mutableListOf<HomePageList>()
        val mainDoc = app.get(mainUrl).document
        
        val featured = mainDoc.select(".promo-item, .m-item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        if (featured.isNotEmpty()) homePageList.add(HomePageList("In Evidenza", featured))

        val latest = mainDoc.select(".block-th").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        if (latest.isNotEmpty()) homePageList.add(HomePageList("Ultimi Aggiunti", latest))

        return newHomePageResponse(homePageList, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val allResults = mutableListOf<SearchResponse>()
        for (page in 1..5) {
            try {
                val pagedResults = app.post(
                    "$mainUrl/index.php?do=search",
                    data = mapOf(
                        "do" to "search",
                        "subaction" to "search",
                        "search_start" to "$page",
                        "full_search" to "0",
                        "result_from" to "${(page - 1) * 20 + 1}",
                        "story" to query
                    )
                ).document.select(".m-item, .movie-item, article, .block-th").mapNotNull { it.toSearchResult() }
                if (pagedResults.isEmpty()) break
                allResults.addAll(pagedResults)
            } catch (e: Exception) { break }
        }
        return allResults.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst(".block-th-haeding a, a") ?: return null
        val href = fixUrl(a.attr("href"))
        if (href.contains("/tags/") || href.contains("/category/")) return null

        // 2. PULIZIA TITOLO: Rimuove "Episodio X", "Stagione X" o diciture simili dal titolo nella ricerca
        var title = a.text().trim().ifEmpty { 
            this.selectFirst("h2, h3, .m-title, .block-th-haeding")?.text() ?: a.attr("title") 
        }
        title = title.split(" – ").get(0).split(" - ").get(0).split(" [").get(0).trim()
        
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src") ?: img?.attr("src"))
        
        return if (href.contains("/serie-tv/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        // Pulizia titolo anche nella pagina di caricamento
        var title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        title = title.split(" – ").get(0).split(" - ").get(0).trim()
        
        val poster = fixUrlNull(doc.selectFirst("img._player-cover, .story-poster img, img[itemprop='image']")?.attr("src"))
        
        val plotElement = doc.selectFirst(".story")
        val strongText = plotElement?.selectFirst("strong")?.text() ?: ""
        val plot = plotElement?.text()?.replace(strongText, "")?.replace("+Info»", "")?.trim()?.removePrefix(",")?.trim()

        val seasonContainer = doc.selectFirst(".tt_season")
        return if (seasonContainer != null) {
            val episodesList = mutableListOf<Episode>()
            doc.select(".tt_series .tab-content .tab-pane").forEachIndexed { index, pane ->
                val seasonNum = index + 1
                pane.select("li").forEach { li ->
                    val a = li.selectFirst("a[id^=serie-]") ?: return@forEach
                    val mainLink = a.attr("data-link").ifEmpty { a.attr("href") }
                    val mirrors = li.select(".mirrors a.mr").map { it.attr("data-link") }
                    val allLinks = (listOf(mainLink) + mirrors).filter { it.isNotBlank() }.joinToString("|")
                    val epNum = a.text().toIntOrNull() ?: 1

                    episodesList.add(newEpisode(allLinks) {
                        this.name = "Episodio $epNum"
                        this.season = seasonNum
                        this.episode = epNum
                        // 1. COPERTINA EPISODI: Usiamo il poster della serie
                        this.posterUrl = poster 
                    })
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) { 
                this.posterUrl = poster
                this.plot = plot 
            }
        } else {
            val iframe = doc.selectFirst("iframe[src*='guardahd'], iframe#_player, a[href*='mostraguarda']")?.let {
                it.attr("src").ifEmpty { it.attr("href") }
            }
            val movieLinks = if (!iframe.isNullOrBlank()) fixUrl(iframe) else url

            newMovieLoadResponse(title, url, TvType.Movie, movieLinks) {
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

        data.split("|").forEach { rawLink ->
            val fixed = fixUrl(rawLink)
            
            if (fixed.contains("guardahd") || fixed.contains("mostraguarda") || fixed.contains("cineblog")) {
                try {
                    val doc = app.get(fixed).document
                    doc.select("tr[onclick]").forEach { tr ->
                        val code = tr.attr("onclick")
                        val match = Regex("href='([^']+)'").find(code)
                        match?.groupValues?.get(1)?.let { finalLinks.add(fixUrl(it)) }
                    }
                    doc.select("li[data-link], a[data-link], iframe").forEach { el ->
                        val found = el.attr("data-link").ifEmpty { el.attr("src") }
                        if (found.isNotBlank() && !found.contains("guardahd")) finalLinks.add(fixUrl(found))
                    }
                } catch (e: Exception) { }
            } else {
                finalLinks.add(fixed)
            }
        }

        finalLinks.distinct().forEach { link ->
            val clean = link.replace("?download", "")
            when {
                clean.contains("mixdrop") || clean.contains("m1xdrop") -> 
                    loadExtractor(clean, clean, subtitleCallback, callback)
                
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
