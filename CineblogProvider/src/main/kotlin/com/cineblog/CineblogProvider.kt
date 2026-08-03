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
    // PARSER CARD (block-list + block-th)
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
    // LOAD (Film + Serie TV con IMDB → VidxGo + episodi)
    // ---------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text() ?: return null
        val poster = doc.selectFirst(".story-cover img, .full-img img")?.absUrl("src")
        val plot = doc.selectFirst(".full-text")?.text()

        val isSeries = doc.selectFirst(".ep-item") != null ||
                       doc.selectFirst(".episode-info") != null

        // --- Estrai IMDB ---
        val imdb = doc.select("script")
            .html()
            .substringAfter("var imdb = '", "")
            .substringBefore("';", "")
            .trim()

        val imdbNumeric = imdb.replace("tt", "")
        val vidxUrl = "https://v.vidxgo.co/$imdbNumeric"

        // --- FILM ---
        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, vidxUrl) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        // --- SERIE TV: parsing episodi dalla sidebar ---
        val episodes = doc.select(".ep-item").mapNotNull { ep ->
            val href = ep.absUrl("href") // es: https://cineblog001.store/34688214/1/1
            val parts = href.split("/")
        
            if (parts.size < 4) return@mapNotNull null
        
            val imdbNumeric = parts[parts.size - 3] // 34688214
            val season = parts[parts.size - 2].toIntOrNull() ?: 1
            val episodeNum = parts[parts.size - 1].toIntOrNull() ?: 1
        
            val epTitle = ep.selectFirst(".ep-name")?.text()?.trim()
                ?: "Episodio $episodeNum"
        
            val epThumb = ep.selectFirst(".ep-thumb")?.absUrl("src")
        
            val vidxUrl = "https://v.vidxgo.co/$imdbNumeric"
        
            newEpisode(
                href,      // URL pagina episodio
                vidxUrl    // URL video VidxGo
            ) {
                this.season = season
                this.episode = episodeNum
                this.name = epTitle
                this.posterUrl = epThumb
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ---------------------------------------------------------
    // LOAD LINKS (usa il tuo VidxGoExtractor)
    // ---------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // data è già l’URL VidxGo (es: https://v.vidxgo.co/34688214)
        if (data.contains("vidx") || data.contains("vidxgo") || data.contains("v.vidxgo")) {
            VidxGoExtractor().getUrl(data, mainUrl, subtitleCallback, callback)
            return true
        }

        // fallback generico
        loadExtractor(data, mainUrl, subtitleCallback, callback)
        return true
    }
}
