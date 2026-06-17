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

    private val placeholderPoster = "https://toonitalia.xyz/wp-content/uploads/2023/11/toonitalia-logo-1.png"

    private val headers = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0"
    )

    /** Normalizzazione host */
    private fun fixHost(url: String): String {
        return url
            .replace("chuckle-tube.com", "voe.sx")
            .replace("luluvdo.com", "lulustream.com")
            .replace("luluvideo.com", "lulustream.com")
            .replace("minochinos.com", "vidhidehub.com")
            .replace("megavido.com", "vidhidehub.com")
            .replace("vidhidepro.com", "vidhidehub.com")
            .replace("vidhide.com", "vidhidehub.com")
            .replace("smoothpre.com", "vidhidehub.com")
            .replace("streamup.ws", "streamwish.to")
    }

    // ============================
    // MAIN PAGE
    // ============================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl, headers = headers).document
        val sections = mutableListOf<HomePageList>()

        doc.select("div.col, div.card, section").forEach { block ->
            val title = block.selectFirst("h2, h3, h4")?.text()?.trim() ?: return@forEach
            val items = block.select("a[href]").mapNotNull { a ->
                val href = a.attr("href")
                val img = a.selectFirst("img")?.attr("src")
                val name = a.text().trim()
                if (name.isEmpty() || !href.startsWith("http")) return@mapNotNull null

                newTvSeriesSearchResponse(name, href, TvType.TvSeries) {
                    posterUrl = img ?: placeholderPoster
                    posterHeaders = headers
                }
            }

            if (items.isNotEmpty()) sections.add(HomePageList(title, items))
        }

        return newHomePageResponse(sections, false)
    }

    // ============================
    // SEARCH
    // ============================

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url, headers = headers).document

        return doc.select("article, div.post, div.card").mapNotNull { art ->
            val a = art.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href")
            val title = a.text().trim()

            val inner = app.get(href, headers = headers).document
            val poster = inner.selectFirst("img.attachment-post-thumbnail, .post-thumbnail img, .entry-content img")
                ?.attr("src") ?: placeholderPoster

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                posterHeaders = headers
            }
        }
    }

    // ============================
    // LOAD (EPISODI + FILM)
    // ============================

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?.replace(Regex("(?i)streaming|sub\\s?ita|film"), "")
            ?.trim() ?: "Senza titolo"

        val poster = doc.selectFirst("img.attachment-post-thumbnail, .post-thumbnail img, .entry-content img")
            ?.attr("src") ?: placeholderPoster

        val plot = doc.select("div.entry-content p")
            .map { it.text() }
            .firstOrNull { it.length > 60 }

        val categories = doc.select(".entry-categories-inner a, .cat-links a")
            .map { it.text().lowercase() }

        val isMovie = categories.any { it.contains("film") }

        val episodes = parseEpisodes(doc, poster)

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: "") {
                this.posterUrl = poster
                this.plot = plot
                this.posterHeaders = headers
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.posterHeaders = headers
            }
        }
    }

    // ============================
    // PARSER EPISODI UNIVERSALE
    // ============================

  private fun parseEpisodes(doc: org.jsoup.nodes.Document, poster: String): List<Episode> {
    val episodes = mutableListOf<Episode>()

    // Prendiamo TUTTO l’HTML dell’entry-content
    val rawHtml = doc.selectFirst("div.entry-content")?.html() ?: return episodes

    // Splittiamo manualmente ogni riga
    val lines = rawHtml.split(Regex("<br\\s*/?>|</p>|</div>|\\n"))

    for (line in lines) {
        val clean = Jsoup.parse(line).text().trim()
        if (clean.isEmpty()) continue

        // Estrai link validi
        val links = Jsoup.parse(line).select("a[href]").map { it.attr("href") }
            .filter { link ->
                link.startsWith("http") &&
                !link.contains("toonitalia.xyz") &&
                !link.contains("lulu") &&
                !link.contains("lulu.st") &&
                !link.contains("lulustream")
            }

        if (links.isEmpty()) continue

        // Formato 1x01A
        val matchAB = Regex("""(\d+)x(\d+)([A-Za-z]?)""").find(clean)

        // Formato 01 – Titolo
        val matchSimple = Regex("""^(\d{1,3})\s*[–-]""").find(clean)

        var season = 1
        var episode: Int? = null
        var subEp: String? = null

        if (matchAB != null) {
            season = matchAB.groupValues[1].toInt()
            val epNum = matchAB.groupValues[2].toInt()
            subEp = matchAB.groupValues[3].uppercase().ifEmpty { null }

            episode = if (subEp == null) {
                epNum
            } else {
                val offset = (subEp[0] - 'A' + 1)
                epNum * 10 + offset
            }

        } else if (matchSimple != null) {
            episode = matchSimple.groupValues[1].toInt()
        }

        val titleParts = clean.split("–").map { it.trim() }
        val epTitle = if (titleParts.size >= 2) titleParts[1] else "Episodio"

        val finalName = buildString {
            append("${season}x${episode ?: "?"}")
            if (!subEp.isNullOrEmpty()) append(subEp)
            append(" – $epTitle")
        }

        episodes.add(
            newEpisode(links.joinToString("###")) {
                this.name = finalName
                this.season = season
                this.episode = episode
                this.posterUrl = poster
            }
        )
    }

    return episodes
}


    // ============================
    // LOAD LINKS
    // ============================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        data.split("###").forEach { link ->
            loadExtractor(fixHost(link), subtitleCallback, callback)
        }
        return true
    }
}
