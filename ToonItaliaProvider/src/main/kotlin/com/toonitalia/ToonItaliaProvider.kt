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
        val url = if (page <= 1) request.data else "${request.data}/page/$page/"
        val document = app.get(url, headers = commonHeaders, timeout = 10).document
        
        // Modifica qui: Adattamento alla nuova struttura <div class="item"> o <article>
        val items = document.select("article, .item").mapNotNull { element ->
            // Cerca il link: può essere h2.entry-title a (vecchio) o a diretto (nuovo)
            val linkElement = element.selectFirst("h2.entry-title a") ?: element.selectFirst("a")
            val href = linkElement?.attr("href") ?: return@mapNotNull null
            val title = linkElement.text().trim().ifEmpty { "Senza Titolo" }
            
            // Cerca l'immagine dentro il link o nell'elemento
            val posterUrl = element.selectFirst("img")?.let { img ->
                img.attr("src").ifEmpty { img.attr("data-src") }
            } ?: searchPlaceholderLogo

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.posterHeaders = commonHeaders
            }
        }
        
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = commonHeaders).document
        
        return document.select("article, .item").amap { element ->
            val linkElement = element.selectFirst("h2.entry-title a") ?: element.selectFirst("a")
            val href = linkElement?.attr("href") ?: return@amap null
            val title = linkElement.text()

            // Per la ricerca carichiamo la pagina interna per avere il poster corretto se manca nella lista
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
        val title = document.selectFirst("h1.entry-title")?.text()
            ?.replace(Regex("(?i)streaming|sub\\s?ita"), "")?.trim() ?: ""
        
        val poster = document.selectFirst("img.attachment-post-thumbnail, .post-thumbnail img, .entry-content img")?.attr("src")
            ?: searchPlaceholderLogo

        val entryContent = document.selectFirst("div.entry-content")
        val fullText = entryContent?.text() ?: ""

        val categories = document.select(".entry-categories-inner a").map { it.text().lowercase() }
        val isMovie = categories.any { it.contains("film animazione") || it == "film" }
        val tvType = if (isMovie) TvType.Movie else TvType.TvSeries

        var plot = document.select("div.entry-content p")
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
                val href = a.attr("href")
                href.startsWith("http") && !href.contains("toonitalia.xyz") && 
                supportedHosts.any { host -> href.contains(host) }
            }.map { it.attr("href") }.distinct()

            if (validLinks.isNotEmpty()) {
                val isTrailerRow = text.contains(Regex("(?i)sigla|intro|trailer"))
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
        data.split("###").forEach { url ->
            loadExtractor(fixHostUrl(url), subtitleCallback, callback)
        }
        return true
    }
}
