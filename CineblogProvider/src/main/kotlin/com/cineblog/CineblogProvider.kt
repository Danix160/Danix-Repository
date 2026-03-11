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
        
        val sections = listOf(
            "In Evidenza" to ".promo-item, .m-item",
            "Ultimi Aggiunti" to ".block-th",
            "Serie TV" to ".m-item:has(a[href*='/serie-tv/'])"
        )

        sections.forEach { (title, selector) ->
            val results = mainDoc.select(selector).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
            if (results.isNotEmpty()) homePageList.add(HomePageList(title, results))
        }

        val genres = listOf(
            "Azione" to "$mainUrl/film/?genere=1",
            "Animazione" to "$mainUrl/film/?genere=2",
            "Avventura" to "$mainUrl/film/?genere=3",
            "Horror" to "$mainUrl/film/?genere=13"
        )
        
        genres.forEach { (name, url) ->
            try {
                val items = app.get(url).document.select(".block-th").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
                if (items.isNotEmpty()) homePageList.add(HomePageList(name, items))
            } catch (e: Exception) { }
        }
        return newHomePageResponse(homePageList, false)
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val allResults = mutableListOf<SearchResponse>()
        for (page in 1..3) {
            try {
                val pagedResults = app.post(
                    "$mainUrl/index.php?do=search",
                    data = mapOf(
                        "do" to "search",
                        "subaction" to "search",
                        "search_start" to "$page",
                        "story" to query
                    )
                ).document.select(".m-item, .movie-item, .block-th").mapNotNull { it.toSearchResult() }
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

        var title = a.text().trim().ifEmpty { 
            this.selectFirst("h2, h3, .m-title, .block-th-haeding")?.text() ?: a.attr("title") 
        }
        
        // Pulizia Titolo
        title = title.split(" – ").get(0)
            .split(" - ").get(0)
            .split(" [").get(0)
            .replace(Regex("(?i) streaming"), "")
            .trim()
        
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
        var title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        
        title = title.split(" – ").get(0)
            .split(" - ").get(0)
            .replace(Regex("(?i) streaming"), "")
            .trim()
        
        val poster = fixUrlNull(doc.selectFirst("img._player-cover, .story-poster img, img[itemprop='image']")?.attr("src"))
        val plot = doc.selectFirst(".story")?.text()?.split("+Info")?.get(0)?.trim()

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
        val foundLinks = mutableListOf<String>()

        data.split("|").forEach { rawLink ->
            val fixed = fixUrl(rawLink)
            if (fixed.contains("guardahd") || fixed.contains("mostraguarda") || fixed.contains("cineblog") || fixed.contains("cinemm")) {
                try {
                    val doc = app.get(fixed).document
                    doc.select("tr[onclick], li[data-link], a[data-link], iframe").forEach { el ->
                        val link = if (el.hasAttr("onclick")) {
                            Regex("href='([^']+)'").find(el.attr("onclick"))?.groupValues?.get(1)
                        } else {
                            el.attr("data-link").ifEmpty { el.attr("src") }
                        }
                        if (!link.isNullOrBlank() && !link.contains("guardahd")) foundLinks.add(fixUrl(link))
                    }
                } catch (e: Exception) { }
            } else {
                foundLinks.add(fixed)
            }
        }

        // PRIORITÀ: Supervideo per primo
        val prioritizedLinks = foundLinks.distinct().sortedByDescending { it.contains("supervideo") }

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
