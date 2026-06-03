package com.loonex

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class LoonexProvider : MainAPI() {

    override var mainUrl = "https://loonex.eu/cartoni"
    override var name = "Loonex"
    override val hasMainPage = true
    override var lang = "it"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Novità",
        "$mainUrl/?trending=1" to "I più visti"
    )

    // -----------------------------
    // CLEAN TITLE
    // -----------------------------
    private fun cleanTitle(t: String): String {
        return t.replace("""\s+""".toRegex(), " ").trim()
    }

    // -----------------------------
    // MAIN PAGE
    // -----------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val list = mutableListOf<SearchResponse>()

        doc.select(".scroller-item").forEach { item ->
            val a = item.selectFirst("a") ?: return@forEach
            val relative = a.attr("href")
            val fullUrl = "$mainUrl/$relative"

            val title = cleanTitle(item.selectFirst(".card-title-cine")?.text() ?: return@forEach)
            val poster = item.selectFirst("img")?.attr("src")

            val isMovie = item.selectFirst(".movie-badge") != null

            list.add(
                newMovieSearchResponse(title, fullUrl, if (isMovie) TvType.Movie else TvType.TvSeries) {
                    this.posterUrl = poster
                }
            )
        }

        return newHomePageResponse(request.name, list)
    }

    // -----------------------------
    // SEARCH
    // -----------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        val list = mutableListOf<SearchResponse>()

        doc.select(".scroller-item").forEach { item ->
            val a = item.selectFirst("a") ?: return@forEach
            val fullUrl = "$mainUrl/${a.attr("href")}"

            val title = cleanTitle(item.selectFirst(".card-title-cine")?.text() ?: return@forEach)
            val poster = item.selectFirst("img")?.attr("src")
            val isMovie = item.selectFirst(".movie-badge") != null

            list.add(
                newMovieSearchResponse(title, fullUrl, if (isMovie) TvType.Movie else TvType.TvSeries) {
                    this.posterUrl = poster
                }
            )
        }

        return list
    }

    // -----------------------------
    // LOAD (FILM + SERIE)
    // -----------------------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val rawTitle =
            doc.selectFirst(".cartoon-title-logo")?.attr("title")
                ?: doc.selectFirst("h1")?.text()
                ?: "Senza titolo"

        val title = cleanTitle(rawTitle)

        val poster = doc.selectFirst(".cartoon-title-logo")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = doc.selectFirst(".text-secondary")?.text()

        // -----------------------------
        // FILM
        // -----------------------------
        val filmLink = doc.selectFirst("a[href*=/guarda/?id=]")?.attr("href")
        val isMovie = doc.select("h3:contains(Riproduci Film)").isNotEmpty()

        if (isMovie && filmLink != null) {
            return newMovieLoadResponse(title, url, TvType.Movie, filmLink) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

// -----------------------------
// SERIE (stagione 0 per tutto ciò che non è "Stagione X")
// -----------------------------
val episodes = mutableListOf<Episode>()

doc.select(".tab-pane").forEach { tab ->
    // Legge il titolo della sezione (es. "Stai guardando: Speciali TV")
    val sectionTitle = tab.selectFirst("h5")?.text()?.trim() ?: ""

    // Determina la stagione:
    // - Se contiene "Stagione X" → usa X
    // - Altrimenti → stagione 0 (speciali, film vari, extra, ecc.)
    val seasonNumber =
        Regex("""Stagione\s+(\d+)""", RegexOption.IGNORE_CASE)
            .find(sectionTitle)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0  // default → speciali

    tab.select(".episode-row").forEach { row ->
        val epTitle = row.selectFirst(".episode-title")?.text() ?: "Episodio"
        val link = row.selectFirst("a[href]")?.attr("href") ?: return@forEach

        episodes.add(
            newEpisode(link) {
                this.name = cleanTitle(epTitle)
                this.season = seasonNumber
                this.posterUrl = poster
            }
        )
    }
  }
}

    // -----------------------------
    // LOAD LINKS
    // -----------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        loadExtractor(data, mainUrl, subtitleCallback, callback)
        return true
    }
}
