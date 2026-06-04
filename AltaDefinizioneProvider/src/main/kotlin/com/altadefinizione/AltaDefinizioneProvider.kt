package com.altadefinizione

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.loadExtractor
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
        val a = el.selectFirst(".cover.boxcaption h2 a, h3 a, .boxcaption h2 a") ?: return null
        val title = a.text().trim()
        val href = fixUrl(a.attr("href"))
        val img = el.selectFirst("a > img")?.attr("data-src") ?: ""
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
        if (query.isBlank()) return emptyList()

        val url = "$mainUrl/index.php?do=search&subaction=search&titleonly=3&story=$query"
        val doc = app.get(url).document

        return doc.select("#dle-content .boxgrid.caption").mapNotNull { parseItem(it) }
    }

    // -----------------------------
    // LOAD MOVIE / SERIES
    // -----------------------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("h1,h2")?.text()?.trim()
            ?: "Senza titolo"

        val poster = fixUrl(doc.selectFirst(".fix img")?.attr("data-src") ?: "")

        // SERIE TV
        val seasonTabs = doc.select("#tt_holder .tt_season ul li a[data-toggle=tab]")
        if (seasonTabs.isNotEmpty()) {
            val seasons = seasonTabs.mapNotNull { a ->
                val seasonNumber = a.text().trim().toIntOrNull() ?: return@mapNotNull null
                val seasonId = a.attr("href").removePrefix("#")
                val pane = doc.selectFirst("#$seasonId") ?: return@mapNotNull null

                val episodes = pane.select("ul > li > a[allowfullscreen][data-link]").mapNotNull { ep ->
                    val epNum = ep.attr("data-num").substringAfter('x').toIntOrNull()
                        ?: ep.text().trim().toIntOrNull()
                        ?: return@mapNotNull null

                    val epTitle = ep.attr("data-title").ifBlank { "Episodio $epNum" }

                    newEpisode("$url#s${seasonNumber}e$epNum") {
                        name = epTitle
                        season = seasonNumber
                        episode = epNum
                    }
                }

                Season(
                    seasonNumber,
                    episodes
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, seasons) {
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
        val s = data.substringAfter("#s", "").substringBefore("e").toIntOrNull()
        val e = data.substringAfter("e", "").toIntOrNull()

        val doc = app.get(showUrl).document

        // EPISODI
        if (s != null && e != null) {
            val pane = doc.selectFirst("#season-$s")
            val ep = pane?.select("ul > li > a[allowfullscreen][data-link]")?.firstOrNull { a ->
                val n = a.attr("data-num").substringAfter('x').toIntOrNull()
                    ?: a.text().trim().toIntOrNull()
                n == e
            }

            val vidxLink = ep?.attr("data-link")?.trim()
            if (!vidxLink.isNullOrBlank()) {
                loadExtractor(vidxLink, showUrl, subtitleCallback, callback)
                return true
            }

            // fallback VidxGo /t/imdb/season/ep
            val scripts = doc.select("script")
            val imdbId = scripts.firstNotNullOfOrNull { script ->
                Regex("var\\s+imdb\\s*=\\s*['\"]tt(\\d+)['\"]").find(script.html())?.groupValues?.get(1)
            }
            if (imdbId != null) {
                val vidxgoUrl = "https://v.vidxgo.co/t/$imdbId/$s/$e"
                loadExtractor(vidxgoUrl, showUrl, subtitleCallback, callback)
                return true
            }
        }

        // FILM → iframe VidxGo
        val iframe = doc.selectFirst("iframe#vidxgo-player-film, iframe[src*='v.vidxgo.co']")
        if (iframe != null) {
            val src = iframe.attr("src").trim()
            if (src.isNotBlank()) {
                loadExtractor(src, showUrl, subtitleCallback, callback)
                return true
            }
        }

        // FILM → solo imdb → https://v.vidxgo.co/$imdb
        val scripts = doc.select("script")
        val imdbId = scripts.firstNotNullOfOrNull { script ->
            Regex("var\\s+imdb\\s*=\\s*['\"]tt(\\d+)['\"]").find(script.html())?.groupValues?.get(1)
        }
        if (imdbId != null) {
            val vidxgoUrl = "https://v.vidxgo.co/$imdbId"
            loadExtractor(vidxgoUrl, showUrl, subtitleCallback, callback)
            return true
        }

        return false
    }
}
