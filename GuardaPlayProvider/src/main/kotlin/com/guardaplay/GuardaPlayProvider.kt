package com.guardaplay

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element

class GuardaPlayProvider : MainAPI() {
    override var mainUrl = "https://guardaplay.space"
    override var name = "GuardaPlay"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ultimi Film",
        "$mainUrl/category/animazione/" to "Animazione",
        "$mainUrl/category/azione/" to "Azione",
        "$mainUrl/category/commedia/" to "Commedia",
        "$mainUrl/category/horror/" to "Horror",
        "$mainUrl/category/fantascienza/" to "Fantascienza"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        
        // Selettore aggiornato per Home e Categorie
        val home = document.select("article.post, article.movies, .post-thumbnail").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Titolo da h2.entry-title (come da tuo snippet)
        val title = this.selectFirst("h2.entry-title")?.text() 
            ?: this.selectFirst("h3")?.text() 
            ?: this.selectFirst("img")?.attr("alt")?.replace("Image ", "")
            ?: return null
            
        // Link dal tag a.lnk-blk
        val href = this.selectFirst("a.lnk-blk")?.attr("href") 
            ?: this.selectFirst("a")?.attr("href") 
            ?: return null
        
        // Immagine con fix protocollo //
        val imgElement = this.selectFirst("img")
        var posterUrl = imgElement?.attr("data-src") 
            ?: imgElement?.attr("data-lazy-src") 
            ?: imgElement?.attr("src") 
            ?: ""
        
        if (posterUrl.startsWith("//")) {
            posterUrl = "https:$posterUrl"
        }

        val year = this.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("article.post, .item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1")?.text() ?: return null
        
        var posterUrl = document.selectFirst(".poster img, .post-thumbnail img")?.attr("src") ?: ""
        if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"

        val description = document.selectFirst(".wp-content p, .description p")?.text()
        val year = Regex("\\d{4}").find(document.select(".date, .year, .entry-meta").text())?.value?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = description
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false

        // 1. Scansione degli iframe nelle opzioni (Server 1, Server 2...)
        val options = document.select("div[id^=option] iframe, .source-box iframe, li[id^=player-option-]")
        
        for (option in options) {
            val rawUrl = option.attr("data-src")
                .ifBlank { option.attr("src") }
                .ifBlank { option.attr("data-href") }
            
            if (rawUrl.isNotBlank() && !rawUrl.contains("about:blank")) {
                if (processUrl(rawUrl, data, subtitleCallback, callback)) found = true
            }
        }

        // 2. Scansione di emergenza negli script (per link nascosti in Ajax)
        if (!found) {
            document.select("script").forEach { script ->
                val code = script.data()
                if (code.contains("https://")) {
                    Regex("""https?://[^\s"'<>]+""").findAll(code).forEach { match ->
                        val url = match.value.replace("\\/", "/")
                        if (url.contains("embed") || url.contains("video")) {
                            if (processUrl(url, data, subtitleCallback, callback)) found = true
                        }
                    }
                }
            }
        }

        return found
    }

    private suspend fun processUrl(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanUrl = if (url.startsWith("//")) "https:$url" else url
        
        return try {
            // Logica "Doppio Salto": se l'URL è interno al sito, carichiamo la sottopagina
            if (cleanUrl.contains("guardaplay") || cleanUrl.contains("/video/")) {
                val innerDoc = app.get(cleanUrl, referer = referer).document
                val finalIframe = innerDoc.selectFirst("iframe")?.attr("src")
                    ?: innerDoc.selectFirst("iframe")?.attr("data-src")
                
                if (!finalIframe.isNullOrBlank()) {
                    val fixedFinal = if (finalIframe.startsWith("//")) "https:$finalIframe" else finalIframe
                    loadExtractor(fixedFinal, cleanUrl, subtitleCallback, callback)
                    true
                } else false
            } else {
                // Se è già un link esterno (Mixdrop, ecc.), lo carichiamo direttamente
                loadExtractor(cleanUrl, referer, subtitleCallback, callback)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
