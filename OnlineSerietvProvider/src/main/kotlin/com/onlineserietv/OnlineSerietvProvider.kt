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
        val dati = response.selectFirst(".headingder")!!
        val poster = dati.selectFirst(".imgs > img")?.attr("src")?.replace(Regex("""-\d+x\d+"""), "")
        val title = dati.select(".dataplus h1").text().trim().replace(Regex("""\s\d{4}$"""), "")
        
        val genres = dati.select(".stars span:contains(Genere) i").text()
        val year = dati.select(".stars span:contains(Anno) i").text().toIntOrNull()
        val plot = response.select("div.post p").firstOrNull { it.text().length > 30 }?.text()?.trim()

        return if (url.contains("/film/")) {
            // Per i film cerchiamo solo il link Flexy nel box hostlinks
            val flexyLink = response.select("#hostlinks a").firstOrNull { 
                it.text().contains("Flexy", true) || it.attr("href").contains("/fxf/") 
            }?.attr("href")
            
            newMovieLoadResponse(title, url, TvType.Movie, flexyLink ?: "") {
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
            
            // Gestione cambio stagione nelle righe della tabella
            if (cells.size == 1 && row.text().contains("Stagione", true)) {
                currentSeason = Regex("""\d+""").find(row.text())?.value?.toIntOrNull() ?: currentSeason
                null
            } 
            // Estrazione riga episodio
            else if (cells.size > 1) {
                val epTitle = cells[0].text().trim()
                
                // PRENDE SOLO IL LINK FLEXY
                val flexyLink = row.select("a").firstOrNull { a ->
                    a.text().contains("Flexy", true) || 
                    a.attr("href").contains("/fxf/") || 
                    a.attr("href").contains("/fxe/")
                }?.attr("href")

                if (flexyLink != null) {
                    newEpisode(flexyLink) {
                        this.name = epTitle
                        this.season = currentSeason
                        // Estrae il numero dopo la 'x' (es. 01x12 -> 12)
                        this.episode = Regex("""(?i)\d+x(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                    }
                } else null
            } else null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.isBlank()) return false
        
        // Se il link è di uprot, eseguiamo il bypass
        if (data.contains("uprot")) {
            val bypassed = bypassUprot(data)
            if (bypassed != null) {
                loadExtractor(bypassed, subtitleCallback, callback)
            }
        } else {
            loadExtractor(data, subtitleCallback, callback)
        }
        return true
    }

    private suspend fun bypassUprot(link: String): String? {
        // Step 1: Forza l'endpoint /fxe/ che spesso salta il countdown/captcha
        val target = link.replace("/fxf/", "/fxe/")
        
        val doc = app.get(target, referer = mainUrl).document
        
        // Step 2: Cerca il link reale al video (flexy.stream)
        // Evitiamo i link bot-trap identificati (discovernative, ecc.)
        val realLink = doc.select("a").firstOrNull { element ->
            val href = element.attr("href")
            val isVisible = !element.attr("style").contains("display:none")
            
            href.contains("flexy.stream") && isVisible && !href.contains("visit.php")
        }?.attr("href")
        
        return realLink
    }
}
