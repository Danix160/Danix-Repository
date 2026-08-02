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
    // MAINPAGE UFFICIALE
    // ---------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Film – Ultimi inseriti",
        "$mainUrl/serie-tv/" to "Serie TV – Ultime inserite",
    )

    // ---------------------------------------------------------
    // PARSER UNIVERSALE PER CARD FILM / SERIE TV
    // ---------------------------------------------------------
   private fun parseCbItem(element: Element): SearchResponse? {
    // URL
    val link = element.select("a[href]")
        .firstOrNull { it.text().isNotBlank() }   // prende SOLO l’a con testo
        ?.absUrl("href")
        ?: element.selectFirst("a[href]")?.absUrl("href")
        ?: return null

    // TITOLO
    val title = element.select("a[href]")
        .firstOrNull { it.text().isNotBlank() }   // evita l’a del poster
        ?.text()
        ?.trim()
        ?: return null

    // POSTER
    val img = element.selectFirst("img")
    val poster = img?.absUrl("data-src")
        ?.ifEmpty { img.absUrl("src") }
        ?.takeIf { !it.contains("gif") }          // evita placeholder GIF

    // SERIE O FILM?
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
    // LOAD (Film + Serie TV)
    // ---------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text() ?: return null
        val poster = doc.selectFirst(".story-cover img, .full-img img")?.absUrl("src")
        val plot = doc.selectFirst(".full-text")?.text()

        val isSeries = doc.selectFirst(".text-uppercase b")?.text()?.contains("Serie TV") == true

        // ---------------- FILM ----------------
        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        // ---------------- SERIE TV ----------------
        val episodes = mutableListOf<Episode>()

        // In attesa HTML episodi
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

        val doc = app.get(data).document

        doc.select("iframe").forEach { frame ->
            val src = frame.absUrl("src")

            when {
                src.contains("vidx") || src.contains("vidxgo") || src.contains("v.vidxgo") -> {
                    VidxGoExtractor().getUrl(src, mainUrl, subtitleCallback, callback)
                }

                else -> {
                    loadExtractor(src, mainUrl, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
