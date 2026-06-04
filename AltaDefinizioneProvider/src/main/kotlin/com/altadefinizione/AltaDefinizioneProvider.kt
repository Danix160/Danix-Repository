package com.altadefinizione

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AltaDefinizioneProvider : MainAPI() {

    override var mainUrl = "https://altadefinizione-01.forum"
    override var name = "Altadefinizione01"
    override val hasMainPage = true
    override var lang = "it"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        mainUrl to "Ultimi inseriti"
    )

    // -----------------------------
    // HOME
    // -----------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document

        val items = doc.select("#dle-content .boxgrid.caption").mapNotNull { parseItem(it) }

        return newHomePageResponse(
            HomePageList("Ultimi inseriti", items)
        )
    }

    private fun parseItem(el: Element): SearchResponse? {
        val a = el.selectFirst("h2 a, h3 a") ?: return null
        val title = a.text().trim()
        val href = fixUrl(a.attr("href"))
        val img = el.selectFirst("img")?.attr("data-src") ?: ""

        val poster = fixUrl(img)

        val isTv = el.selectFirst(".se_num") != null ||
                el.selectFirst(".ml-cat a[href*='/serie-tv/']") != null

        return if (isTv) {
            newTvSeriesSearchResponse(title, href) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href) {
                this.posterUrl = poster
            }
        }
    }

    // -----------------------------
    // SEARCH
    // -----------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/index.php?do=search&subaction=search&story=$query"
        val doc = app.get(url).document

        return doc.select("#dle-content .boxgrid.caption").mapNotNull { parseItem(it) }
    }

    // -----------------------------
    // LOAD MOVIE / SERIES
    // -----------------------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1,h2")?.text()?.trim()
            ?: "Senza titolo"

        val poster = fixUrl(doc.selectFirst(".fix img")?.attr("data-src") ?: "")

        // SERIE TV
        val seasons = doc.select("#tt_holder .tt_season ul li a[data-toggle=tab]")
        if (seasons.isNotEmpty()) {
            val seasonList = seasons.mapNotNull { a ->
                val num = a.text().trim().toIntOrNull() ?: return@mapNotNull null
                val id = a.attr("href").removePrefix("#")
                val pane = doc.selectFirst("#$id") ?: return@mapNotNull null

                val eps = pane.select("ul > li > a[allowfullscreen][data-link]").mapNotNull { ep ->
                    val epNum = ep.attr("data-num").substringAfter("x").toIntOrNull()
                        ?: ep.text().trim().toIntOrNull()
                        ?: return@mapNotNull null

                    Episode(
                        data = "$url#s${num}e$epNum",
                        name = ep.attr("data-title"),
                        episode = epNum,
                        season = num
                    )
                }

                Season(num, eps)
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, seasonList) {
                this.posterUrl = poster
            }
        }

        // FILM
        return newMovieLoadResponse(title, url, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    // -----------------------------
    // LOAD LINKS (SOLO VIDXGO)
    // -----------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val showUrl = data.substringBefore("#")
        val s = data.substringAfter("#s").substringBefore("e").toIntOrNull()
        val e = data.substringAfter("e").toIntOrNull()

        val doc = app.get(showUrl).document

        // Episodi locali
        if (s != null && e != null) {
            val pane = doc.selectFirst("#season-$s")
            val ep = pane?.select("a[allowfullscreen][data-link]")?.firstOrNull { a ->
                val n = a.attr("data-num").substringAfter("x").toIntOrNull()
                    ?: a.text().trim().toIntOrNull()
                n == e
            }

            val vidx = ep?.attr("data-link")
            if (!vidx.isNullOrBlank()) {
                loadExtractor(vidx, showUrl, callback)
                return true
            }
        }

        // Film → iframe VidxGo
        val iframe = doc.selectFirst("iframe#vidxgo-player-film")
        if (iframe != null) {
            val src = iframe.attr("src")
            loadExtractor(src, showUrl, callback)
            return true
        }

        return false
    }
}
