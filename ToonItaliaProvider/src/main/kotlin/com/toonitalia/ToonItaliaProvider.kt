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
        val res = app.get(mainUrl, headers = commonHeaders, timeout = 10)
        val document = res.document
        val homeSections = mutableListOf<HomePageList>()

        document.select("div.col").forEach { column ->
            val sectionName = column.selectFirst("h2")?.text()?.trim() ?: return@forEach
            val items = parseItems(column)
            if (items.isNotEmpty()) {
                homeSections.add(HomePageList(sectionName, items))
            }
        }

        return newHomePageResponse(homeSections, false)
    }

    private fun parseItems(container: org.jsoup.nodes.Element): List<SearchResponse> {
        return container.select("div.item").mapNotNull { element ->
            val linkElement = element.selectFirst("a.card-link") ?: element.selectFirst("a") ?: return@mapNotNull null
            val href = linkElement.attr("href")
            
            // Il titolo ora è dentro <span class="title">
            val title = linkElement.selectFirst("span.title")?.text()?.trim() 
                ?: linkElement.text().trim()

            val imgElement = element.selectFirst("img")
            val posterUrl = imgElement?.let { 
                val src = it.attr("src")
                val dataSrc = it.attr("data-src")
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
        
        // Estraiamo le categorie per capire se è un film
        val categories = document.select(".entry-categories-inner a, .cat-links a").map { it.text().lowercase() }
        val isMovie = categories.any { it.contains("film animazione") || it == "film" }
        
        // Pulizia titolo: rimuove "film", "streaming", ecc.
        val title = document.selectFirst("h1.entry-title")?.text()
            ?.replace(Regex("(?i)streaming|sub\\s?ita|\\bfilm\\b"), "")?.trim() ?: ""
        
        val poster = document.selectFirst("img.attachment-post-thumbnail, .post-thumbnail img, .entry-content img")?.attr("src")
            ?: searchPlaceholderLogo

        val entryContent = document.selectFirst("div.entry-content")
        val fullText = entryContent?.text() ?: ""

        val tvType = if (isMovie) TvType.Movie else TvType.TvSeries

        val plot = document.select("div.entry-content p")
            .map { it.text() }
            .firstOrNull { it.length > 60 && !it.contains(Regex("(?i)Titolo originale|Paese di origine")) }

        val duration = Regex("""(\d+)\s?min""").find(fullText)?.groupValues?.get(1)?.toIntOrNull()
        val year = Regex("""\b(19\d{2}|20[0-2]\d)\b""").find(fullText)?.groupValues?.get(1)?.toIntOrNull()

        val episodes = mutableListOf<Episode>()
        val lines = entryContent?.html()?.split(Regex("<br\\s*/?>|</p>|</div>|<li>|\\n")) ?: listOf()
        var absoluteEpCounter = 1

        lines.forEach { line ->
            val docLine = Jsoup.parseBodyFragment(line)
            val text = docLine.text().trim()
            
            val validLinks = docLine.select("a").filter { a -> 
                val link = a.attr("href")
                link.startsWith("http") && !link.contains("toonitalia.xyz") && 
                supportedHosts.any { host -> link.contains(host) }
            }.map { it.attr("href") }.distinct()

            if (validLinks.isNotEmpty()) {
                val isTrailerRow = text.contains(Regex("(?i)sigla|intro|trailer"))
                
                // Se è un film, non ci servono stagioni o numeri episodio nel database interno
                val s = if (isTrailerRow) 0 else if (isMovie) null else 1
                val e = if (isTrailerRow) 0 else if (isMovie) null else absoluteEpCounter

                val dataUrls = validLinks.joinToString("###")
                
                // Gestione label per serie (1a, 1b...)
                val matchSE = Regex("""(\d+)[×x](\d+)([a-zA-Z]?)""").find(text)
                val epLabel = matchSE?.let { "${it.groupValues[2]}${it.groupValues[3]}" } ?: "$absoluteEpCounter"

                var epNamePart = text.split(Regex("(?i)VOE|Lulu|Streaming|Vidhide|Mixdrop|RPMShare|STREAMUP|Link| -")).first().trim()
                if (epNamePart.isEmpty() || epNamePart.length < 2) epNamePart = "Episodio"

                val finalName = if (isMovie) "Riproduci Film" else "$epLabel - $epNamePart"

                episodes.add(newEpisode(dataUrls) {
                    this.name = finalName
                    this.season = s
                    this.episode = e
                    this.posterUrl = poster
                })

                if (!isMovie && !isTrailerRow) absoluteEpCounter++ 
            }
        }

        // Importante: CloudStream usa risposte diverse per attivare layout diversi
        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: "") {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.duration = duration
                this.posterHeaders = commonHeaders
            }
        } else {
            newTvSeriesLoadResponse(title, url, tvType, episodes) {
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
        data.split("###").forEach { url ->
            loadExtractor(fixHostUrl(url), subtitleCallback, callback)
        }
        return true
    }
}
