package com.cineblog

import com.cineblog.extractor.VidxGoExtractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class CineblogProvider : MainAPI() {

    override var mainUrl = "https://cineblog001.store"
    override var name = "Cineblog"
    override val hasMainPage = true
    override var lang = "it"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ---------------------------------------------------------
    // MAINPAGE
    // ---------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Film – Ultimi inseriti",
        "$mainUrl/serie-tv/" to "Serie TV – Ultime inserite",
    )

    // ---------------------------------------------------------
    // PARSER CARD
    // ---------------------------------------------------------
    private fun parseCbItem(element: Element): SearchResponse? {
        val link = element.select("a[href]")
            .firstOrNull { it.text().isNotBlank() }
            ?.absUrl("href")
            ?: element.selectFirst("a[href]")?.absUrl("href")
            ?: return null

        val title = element.select("a[href]")
            .firstOrNull { it.text().isNotBlank() }
            ?.text()
            ?.trim()
            ?: return null

        val img = element.selectFirst("img")
        val poster = img?.absUrl("data-src")
            ?.ifEmpty { img.absUrl("src") }
            ?.takeIf { !it.contains("gif") }

        val genresText = element.selectFirst(".text-uppercase b")
            ?.text()
            ?.lowercase()
            ?: ""

        val isSeries = genresText.contains("serie tv")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, link) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    // ---------------------------------------------------------
    // MAINPAGE PARSER
    // ---------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = app.get(request.data).document
        val items = mutableListOf<SearchResponse>()

        doc.select("article.short.block-list").forEach { el ->
            parseCbItem(el)?.let { items.add(it) }
        }

        doc.select("div.block-th").forEach { el ->
            parseCbItem(el)?.let { items.add(it) }
        }

        return newHomePageResponse(request.name, items)
    }

    // ---------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/index.php?do=search&subaction=search&story=$query"
        val doc = app.get(url).document
        val results = mutableListOf<SearchResponse>()

        doc.select("article.short.block-list, div.block-th").forEach { el ->
            parseCbItem(el)?.let { results.add(it) }
        }

        return results.distinctBy { it.url }
    }

    // ---------------------------------------------------------
    // LOAD (Film + Serie TV con episodi)
    // ---------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text() ?: return null
        val poster = doc.selectFirst(".story-cover img, .full-img img")?.absUrl("src")
        val plot = doc.selectFirst(".full-text")?.text()

        // --- Estrai IMDB ---
        val imdb = doc.select("script")
            .html()
            .substringAfter("var imdb = '", "")
            .substringBefore("';", "")
            .trim()

        val imdbNumeric = imdb.replace("tt", "")
        val vidxUrl = "https://v.vidxgo.co/$imdbNumeric"

        // --- Riconoscimento serie TV ---
        val isSeries = title.contains("Serie TV", ignoreCase = true)

        // --- FILM ---
        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, vidxUrl) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        // --- SERIE TV: parsing episodi ---
        val episodes = doc.select(".ep-item").mapNotNull { ep ->
        val href = ep.absUrl("href") // /34688214/1/2 → https://cineblog001.store/34688214/1/2
        val parts = href.split("/")
    
        if (parts.size < 4) return@mapNotNull null
    
        val imdbId = parts[1]
        val season = parts[2].toIntOrNull() ?: 1
        val episodeNum = parts[3].toIntOrNull() ?: 1
    
        val epTitle = ep.selectFirst(".ep-name")?.text()?.trim()
            ?: "Episodio $episodeNum"
    
        val epThumb = ep.selectFirst(".ep-thumb")?.absUrl("src")
    
        val vidxEpUrl = "https://v.vidxgo.co/t/$imdbId/$season/$episodeNum"
    
        newEpisode(href) {
            this.name = epTitle
            this.season = season
            this.episode = episodeNum
            this.data = vidxEpUrl
            this.posterUrl = epThumb
        }
    }


        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ---------------------------------------------------------
    // LOAD LINKS (usa VidxGoExtractor)
    // ---------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        if (data.contains("vidx") || data.contains("vidxgo") || data.contains("v.vidxgo")) {
            VidxGoExtractor().getUrl(data, mainUrl, subtitleCallback, callback)
            return true
        }

        loadExtractor(data, mainUrl, subtitleCallback, callback)
        return true
    }
}
