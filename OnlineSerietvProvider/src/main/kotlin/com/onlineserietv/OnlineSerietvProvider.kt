package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

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
        val title = response.selectFirst("h1")?.text()?.trim() ?: ""
        val poster = response.selectFirst(".imgs > img")?.attr("src")
        
        val episodes = getEpisodes(response)
        
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
        }
    }

    private fun getEpisodes(page: Document): List<Episode> {
        val rows = page.select("#hostlinks tr")
        var currentSeason = 1
        
        return rows.mapNotNull { row ->
            if (row.text().contains("Stagione", true)) {
                currentSeason = Regex("""\d+""").find(row.text())?.value?.toIntOrNull() ?: currentSeason
                null
            } else {
                val cells = row.select("td")
                if (cells.size > 1) {
                    val epTitle = cells[0].text().trim()
                    // Preleviamo il link Flexy (uprot.net/fxf/...)
                    val flexyLink = row.select("a").firstOrNull { a ->
                        a.text().contains("Flexy", true) || a.attr("href").contains("/fxf/")
                    }?.attr("href")

                    if (flexyLink != null) {
                        newEpisode(flexyLink) {
                            this.name = epTitle
                            this.season = currentSeason
                            this.episode = Regex("""(?i)\d+x(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                        }
                    } else null
                } else null
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.isBlank()) return false
        
        // LOGICA DI BYPASS: Se il link è uprot, dobbiamo "scartarlo" per trovare il video
        val finalUrl = if (data.contains("uprot.net")) {
            bypassUprot(data)
        } else {
            data
        }

        if (finalUrl != null) {
            // Ora finalUrl è "https://flexy.stream/...", Cloudstream sa come gestirlo
            loadExtractor(finalUrl, subtitleCallback, callback)
        }
        return true
    }

    private suspend fun bypassUprot(uprotUrl: String): String? {
        // 1. Proviamo a passare dall'endpoint 'fxe' invece di 'fxf' 
        // Spesso uprot mostra il link diretto se si cambia la lettera finale (fxf -> fxe)
        val attemptUrl = uprotUrl.replace("/fxf/", "/fxe/")
        
        val response = app.get(attemptUrl, referer = mainUrl).document
        
        // 2. Analizziamo l'HTML della pagina di uprot per trovare il link flexy.stream
        // Basandoci sul tuo file 'secondapagina.txt', il link è in un tag <a>
        val realVideoLink = response.select("a").map { it.attr("href") }.firstOrNull { 
            it.contains("flexy.stream") && !it.contains("discovernative") 
        }
        
        return realVideoLink
    }
}
