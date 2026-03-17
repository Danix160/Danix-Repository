package com.guardaplay

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
        
        // Selettore espanso per catturare tutti i tipi di layout nella home
        val home = document.select("article, .item, .post").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Cerca il titolo in vari punti comuni
        val title = this.selectFirst("h2, h3, .title, .entry-title")?.text() 
            ?: this.selectFirst("img")?.attr("alt")?.replace("Image ", "")
            ?: return null
            
        // Cerca il link principale
        val href = this.selectFirst("a")?.attr("href") ?: return null
        
        // Fix Immagini: controlla i vari attributi lazy-load
        val img = this.selectFirst("img")
        var posterUrl = img?.attr("data-src")
            ?.ifBlank { img.attr("data-lazy-src") }
            ?.ifBlank { img.attr("src") }
            ?: ""
        
        // Rendi l'URL dell'immagine assoluto e sicuro
        if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
        if (posterUrl.startsWith("/")) posterUrl = mainUrl + posterUrl

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    // Corretto il sistema di ricerca
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        
        // Usa lo stesso sistema della home per coerenza
        return document.select("article, .item, .post").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1")?.text() ?: return null
        
        var posterUrl = document.selectFirst(".poster img, .post-thumbnail img, .entry-content img")?.attr("src") ?: ""
        if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
        if (posterUrl.startsWith("/")) posterUrl = mainUrl + posterUrl

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = document.selectFirst(".wp-content p, .description p, .entry-content p")?.text()
            this.year = Regex("\\d{4}").find(document.select(".date, .year, .entry-meta").text())?.value?.toIntOrNull()
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

        // Estrazione link da iframe e bottoni server
        val sources = document.select("iframe, .source-box iframe, select option, .player-option")
        
        for (source in sources) {
            val link = source.attr("src")
                .ifBlank { source.attr("data-src") }
                .ifBlank { source.attr("value") } // Per i tag <option>
            
            if (link.isNotBlank() && !link.contains("about:blank")) {
                val cleanLink = if (link.startsWith("//")) "https:$link" else link
                
                // Se il link porta a una sottopagina del sito, la seguiamo
                if (cleanLink.contains(mainUrl) || cleanLink.contains("/video/")) {
                    val innerDoc = app.get(cleanLink, referer = data).document
                    innerDoc.select("iframe").forEach { 
                        val finalUrl = it.attr("src")
                        if (finalUrl.isNotBlank()) {
                            loadExtractor(finalUrl, cleanLink, subtitleCallback, callback)
                            found = true
                        }
                    }
                } else {
                    loadExtractor(cleanLink, data, subtitleCallback, callback)
                    found = true
                }
            }
        }
        return found
    }
}
