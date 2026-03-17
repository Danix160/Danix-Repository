package com.guardaplay

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element

class GuardaPlayProvider : MainAPI() {
    override var mainUrl = "https://guardaplay.space"
    override var name = "GuardaPlay" // Nome che apparirà nell'app
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // Corretto: Usa MainPageRequest invece di HomePageRequest
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = app.get(mainUrl, headers = mapOf("User-Agent" to userAgent)).document
        val home = mutableListOf<HomePageList>()

        doc.select("section.section.movies").forEach { section ->
            val title = section.selectFirst("header .section-title")?.text()?.trim() ?: return@forEach
            val items = section.select(".post-lst li").mapNotNull { it.toSearchResult() }
            if (items.isNotEmpty()) {
                home.add(HomePageList(title, items))
            }
        }
        // Corretto: Usa newHomePageResponse (il costruttore diretto è deprecato)
        return newHomePageResponse(home, false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".entry-title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        return doc.select(".post-lst li").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = doc.selectFirst(".post-thumbnail img")?.attr("src")
        val description = doc.selectFirst(".description p")?.text()?.trim()
        
        // Corretto: Usiamo score invece di rating
        val ratingValue = doc.selectFirst("span.vote.fa-star .num")?.text()?.replace(',', '.')

        val recommendations = doc.select(".post-lst li").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.score = ratingValue?.toDoubleOrNull()
            this.recommendations = recommendations
            
            // Estrazione Trailer
            doc.selectFirst("script#funciones_public_js-js-extra")?.let { script ->
                val b64 = script.attr("src").substringAfter("base64,", "")
                if (b64.isNotEmpty()) {
                    val decoded = base64Decode(b64)
                    val trailerRegex = """\"trailer\"\s*:\s*\".*?src=\\\"(https?:\\/\\/www\.youtube\.com\\/embed\\/[^\\\"]+)\\\"""".toRegex()
                    trailerRegex.find(decoded)?.groupValues?.get(1)?.replace("\\/", "/")?.let {
                        this.addTrailer(it)
                    }
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = mapOf("User-Agent" to userAgent)).document
        
        doc.select("#aa-options div[id^=options-]").forEach { option ->
            val rawIframe = option.selectFirst("iframe[data-src]")?.attr("data-src")
                ?: option.selectFirst("iframe")?.attr("src")
            
            if (rawIframe != null) {
                try {
                    val embedDoc = app.get(rawIframe, headers = mapOf("User-Agent" to userAgent)).document
                    val finalUrl = embedDoc.selectFirst(".Video iframe[src]")?.attr("src")
                    
                    if (!finalUrl.isNullOrBlank()) {
                        loadExtractor(finalUrl, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // Salta l'opzione se fallisce il caricamento dell'iframe
                }
            }
        }
        return true
    }
}
