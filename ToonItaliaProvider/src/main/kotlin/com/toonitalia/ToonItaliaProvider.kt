package com.toonitalia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import org.jsoup.Jsoup

class ToonItaliaProvider : MainAPI() {
    override var mainUrl = "https://toonitalia.xyz"
    override var name = "ToonItalia"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true

    private val searchPlaceholderLogo = "https://toonitalia.xyz/wp-content/uploads/2023/11/toonitalia-logo-1.png"

    private val commonHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    )

    private val supportedHosts = listOf(
        "voe", "chuckle-tube", "luluvdo", "lulustream", "vidhide", "ryderjet", 
        "minochinos", "megavido", "rpmshare", "rpmplay", "streamup", "smoothpre",
        "mixdrop", "streamtape", "fastream", "filemoon", "wolfstream", "streamwish"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category/anime" to "Anime",
        "$mainUrl/category/film-animazione/" to "Film Animazione",
        "$mainUrl/category/serie-tv/" to "Serie TV",
    )

    private fun fixHostUrl(url: String): String {
        return url
            .replace("chuckle-tube.com", "voe.sx")
            .replace("luluvdo.com", "lulustream.com")
            .replace("luluvideo.com", "lulustream.com")
            .replace("toonitalia.rpmplay.xyz/", "rpmplay.xyz")
            .replace("minochinos.com", "vidhidehub.com")
            .replace("megavido.com", "vidhidehub.com")
            .replace("vidhidepro.com", "vidhidehub.com")
            .replace("vidhide.com", "vidhidehub.com")
            .replace("smoothpre.com", "vidhidehub.com")
            .replace("streamup.ws", "streamwish.to")
    }

override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // La home di questo sito è statica, quindi carichiamo sempre la base
        val res = app.get(mainUrl, headers = commonHeaders, timeout = 10)
        val document = res.document
        
        val homeSections = mutableListOf<HomePageList>()

        // Selezioniamo ogni colonna (div.col)
        document.select("div.col").forEach { column ->
            // Il titolo della sezione è dentro l'h2 (es: 🔥 Ultimi Aggiornamenti)
            val sectionName = column.selectFirst("h2")?.text()?.trim() ?: return@forEach
            
            // Estraiamo gli item di questa specifica colonna
            val items = parseItems(column)
            
            if (items.isNotEmpty()) {
                homeSections.add(HomePageList(sectionName, items))
            }
        }

        return newHomePageResponse(homeSections, false)
    }

    private fun parseItems(container: org.jsoup.nodes.Element): List<SearchResponse> {
        // Cerchiamo i div con classe 'item' come nel tuo HTML
        return container.select("div.item").mapNotNull { element ->
            val linkElement = element.selectFirst("a") ?: return@mapNotNull null
            val href = linkElement.attr("href")
            val title = linkElement.text().trim()

            // Estraiamo l'immagine dal tag img
            val imgElement = element.selectFirst("img")
            val posterUrl = imgElement?.let { 
                val src = it.attr("src")
                val dataSrc = it.attr("data-src")
                // Gestione lazy-loading
                if (src.isEmpty() || src.contains(".gif") || src.startsWith("data:")) {
                    if (!dataSrc.isNullOrEmpty()) dataSrc else src
                } else src
            }

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.posterHeaders = commonHeaders
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = commonHeaders).document
        
        return document.select("article").amap { article ->
            val titleHeader = article.selectFirst("h2.entry-title a") ?: article.selectFirst("a")
            val href = titleHeader?.attr("href") ?: return@amap null
            val title = titleHeader.text()

            // Per i risultati di ricerca, spesso è meglio estrarre il poster dalla pagina interna 
            // per avere la massima qualità o se non presente nell'anteprima
            val innerPage = app.get(href, headers = commonHeaders).document
            val posterUrl = innerPage.selectFirst("img.attachment-post-thumbnail, .post-thumbnail img, .entry-content img")?.attr("src")
                ?: innerPage.selectFirst("meta[property=\"og:image\"]")?.attr("content")

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl ?: searchPlaceholderLogo
                this.posterHeaders = commonHeaders
            }
        }.filterNotNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = commonHeaders)
        val document = response.document
        
        // Pulizia titolo dai tag inutili
        val title = document.selectFirst("h1.entry-title")?.text()
            ?.replace(Regex("(?i)streaming|sub\\s?ita"), "")?.trim() ?: ""
        
        val poster = document.selectFirst("img.attachment-post-thumbnail, .post-thumbnail img, .entry-content img")?.attr("src")
            ?: searchPlaceholderLogo

        val entryContent = document.selectFirst("div.entry-content")
        val fullText = entryContent?.text() ?: ""

        val categories = document.select(".entry-categories-inner a").map { it.text().lowercase() }
        val isMovie = categories.any { it.contains("film animazione") || it == "film" }
        val tvType = if (isMovie) TvType.Movie else TvType.TvSeries

        // Estrazione trama: prende il primo paragrafo significativo
        var plot = document.select("div.entry-content p")
            .map { it.text() }
            .firstOrNull { it.length > 60 && !it.contains(Regex("(?i)Titolo originale|Paese di origine")) }

        val duration = Regex("""(\d+)\s?min""").find(fullText)?.groupValues?.get(1)?.toIntOrNull()
        val year = Regex("""\b(19\d{2}|20[0-2]\d)\b""").find(fullText)?.groupValues?.get(1)?.toIntOrNull()

        val episodes = mutableListOf<Episode>()
        // Split dei contenuti per identificare le righe degli episodi
        val lines = entryContent?.html()?.split(Regex("<br\\s*/?>|</p>|</div>|<li>|\\n")) ?: listOf()
        var absoluteEpCounter = 1

        lines.forEach { line ->
            val docLine = Jsoup.parseBodyFragment(line)
            val text = docLine.text().trim()
            
            // Filtra solo link esterni validi appartenenti agli host supportati
            val validLinks = docLine.select("a").filter { a -> 
                val link = a.attr("href")
                link.startsWith("http") && !link.contains("toonitalia.xyz") && 
                supportedHosts.any { host -> link.contains(host) }
            }.map { it.attr("href") }.distinct()

            if (validLinks.isNotEmpty()) {
                val isTrailerRow = text.contains(Regex("(?i)sigla|intro|trailer"))
                // Gestione pattern SxE (es: 1x05 o 1x05a)
                val matchSE = Regex("""(\d+)[×x](\d+)([a-zA-Z]?)""").find(text)

                val s = if (isTrailerRow) 0 else if (isMovie) null else (matchSE?.groupValues?.get(1)?.toIntOrNull() ?: 1)
                val e = if (isTrailerRow) 0 else if (isMovie) null else absoluteEpCounter

                val epLabel = matchSE?.let { 
                    val epNum = it.groupValues[2]
                    val epLetter = it.groupValues[3]
                    "$epNum$epLetter" 
                } ?: "$absoluteEpCounter"

                val dataUrls = validLinks.joinToString("###")
                
                var epNamePart = text.split(Regex("(?i)VOE|Lulu|Streaming|Vidhide|Mixdrop|RPMShare|STREAMUP|Link| -")).first().trim()
                if (epNamePart.isEmpty() || epNamePart.length < 2) epNamePart = "Episodio"

                val finalName = if (isMovie) "Film" else "$epLabel - $epNamePart"

                episodes.add(newEpisode(dataUrls) {
                    this.name = finalName
                    this.season = s
                    this.episode = e
                    this.posterUrl = poster
                })

                if (!isMovie && !isTrailerRow) absoluteEpCounter++ 
            }
        }

        val finalEpisodes = episodes.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        return if (tvType == TvType.Movie) {
            newMovieLoadResponse(title, url, TvType.Movie, finalEpisodes.firstOrNull()?.data ?: "") {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.duration = duration
                this.posterHeaders = commonHeaders
            }
        } else {
            newTvSeriesLoadResponse(title, url, tvType, finalEpisodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.duration = duration
                this.posterHeaders = commonHeaders
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Carica ogni link trovato per l'episodio tramite gli estrattori automatici
        data.split("###").forEach { url ->
            loadExtractor(fixHostUrl(url), subtitleCallback, callback)
        }
        return true
    }
}
