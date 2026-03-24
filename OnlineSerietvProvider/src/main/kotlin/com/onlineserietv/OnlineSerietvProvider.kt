package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.SocketTimeoutException

class OnlineSerietvProvider : MainAPI() {
    override var mainUrl = "https://onlineserietv.live"
    override var name = "OnlineSerieTV"
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries,
        TvType.Cartoon, TvType.Anime, TvType.Documentary
    )
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "Film: Ultimi aggiunti",
        "$mainUrl/serie-tv/" to "Serie TV: Ultime aggiunte",
        "$mainUrl/serie-tv-generi/animazione/" to "Serie TV: Animazione",
        "$mainUrl/film-generi/animazione/" to "Film: Animazione",
        "$mainUrl/film-generi/azione/" to "Film: Azione",
        "$mainUrl/film-generi/fantascienza/" to "Film: Fantascienza",
        "$mainUrl/film-generi/horror/" to "Film: Horror",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val response = try {
            app.get(request.data).document
        } catch (e: Exception) {
            return null
        }
        val searchResponses = getItems(request.name, response)
        return newHomePageResponse(HomePageList(request.name, searchResponses), false)
    }

    private fun getItems(section: String, page: Document): List<SearchResponse> {
        return if (section.contains("Ultimi") || section.contains("Ultime")) {
            page.select(".uagb-post__inner-wrap").mapNotNull {
                val itemTag = it.selectFirst(".uagb-post__title > a") ?: return@mapNotNull null
                val title = itemTag.text().trim().replace(Regex("""\s\d{4}$"""), "")
                val url = itemTag.attr("href")
                val poster = it.selectFirst(".uagb-post__image img")?.attr("src")
                newTvSeriesSearchResponse(title, url) { this.posterUrl = poster }
            }
        } else {
            page.select("#box_movies .movie").map { it.toSearchResponse() }
        }
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val title = this.select("h2").text().trim().replace(Regex("""\s\d{4}$"""), "")
        val url = this.select("a").attr("href")
        val poster = this.select("img").attr("src")
        return newTvSeriesSearchResponse(title, url) { this.posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val page = app.get("$mainUrl/?s=$query").document
        return page.select("#box_movies .movie").map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url).document
        val dati = response.selectFirst(".headingder")!!
        val poster = dati.selectFirst(".imgs > img")?.attr("src")?.replace(Regex("""-\d+x\d+"""), "")
        val title = dati.select(".dataplus h1").text().trim().replace(Regex("""\s\d{4}$"""), "")
        
        val genres = dati.select(".stars span:contains(Genere) i").text()
        val year = dati.select(".stars span:contains(Anno) i").text().toIntOrNull()
        val plot = response.select("div.post p").firstOrNull { it.text().length > 30 }?.text()?.trim()

        return if (url.contains("/film/")) {
            val streamLinks = response.select("#hostlinks a").map { it.attr("href") }
            newMovieLoadResponse(title, url, TvType.Movie, streamLinks.toString()) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = genres.split(",").map { it.trim() }
            }
        } else {
            val episodes = getEpisodes(response)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = genres.split(",").map { it.trim() }
            }
        }
    }

    private fun getEpisodes(page: Document): List<Episode> {
        val rows = page.select("#hostlinks tr")
        var currentSeason = 1
        return rows.mapNotNull { row ->
            val cells = row.select("td")
            if (cells.size == 1 && row.text().contains("Stagione", true)) {
                currentSeason = Regex("""\d+""").find(row.text())?.value?.toIntOrNull() ?: currentSeason
                null
            } else if (cells.size > 1) {
                val epTitle = cells[0].text()
                val links = row.select("a").map { it.attr("href") }
                newEpisode(links.toString()) {
                    this.name = epTitle
                    this.season = currentSeason
                    this.episode = Regex("""\d+""").find(epTitle.substringAfter("x"))?.value?.toIntOrNull()
                }
            } else null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // Pulizia e parsing dei link salvati come stringa JSON
        val cleanData = data.removeSurrounding("[", "]").split(", ")
        cleanData.forEach { rawLink ->
            val link = rawLink.trim('"')
            if (link.contains("uprot")) {
                val bypassed = bypassUprot(link)
                if (bypassed != null) loadExtractor(bypassed, subtitleCallback, callback)
            } else {
                loadExtractor(link, subtitleCallback, callback)
            }
        }
        return true
    }

    private suspend fun bypassUprot(link: String): String? {
        // Sostituzione endpoint fxf -> fxe (saltiamo il primo step se possibile)
        val target = link.replace("/fxf/", "/fxe/").replace("/msf/", "/mse/").replace("/wff/", "/wfe/")
        
        val doc = app.get(target, referer = mainUrl).document
        
        // Filtriamo i link per evitare le trappole analizzate nei file txt
        return doc.select("a[href]").firstOrNull { element ->
            val href = element.attr("href")
            val style = element.attr("style")
            
            val isRealLink = href.contains("flexy.stream") || href.contains("maxstream.video")
            val isVisible = !style.contains("display:none") && !style.contains("-1000px")
            val isNotTrap = !href.contains("discovernative") && !href.contains("bulliongliding")
            
            isRealLink && isVisible && isNotTrap
        }?.attr("href")
    }
}
