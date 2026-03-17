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
        "$mainUrl/category/horror/" to "Horror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        val home = document.select("article.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3 a")?.text() ?: return null
        val href = this.selectFirst("h3 a")?.attr("href") ?: return null
        
        // FIX LOCANDINE: Gestione Protocol-Relative URL (//image.tmdb...)
        val imgElement = this.selectFirst(".post-thumbnail img, img")
        var posterUrl = imgElement?.attr("data-src") 
            ?: imgElement?.attr("data-lazy-src") 
            ?: imgElement?.attr("src") 
            ?: ""
        
        if (posterUrl.startsWith("//")) {
            posterUrl = "https:$posterUrl"
        } else if (posterUrl.startsWith("/") && !posterUrl.startsWith("//")) {
            posterUrl = "https://image.tmdb.org/t/p/w500$posterUrl"
        }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("article.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text() ?: return null
        
        val imgElement = document.selectFirst("div.poster img")
        var posterUrl = imgElement?.attr("src") ?: ""
        if (posterUrl.startsWith("//")) {
            posterUrl = "https:$posterUrl"
        }

        val description = document.selectFirst("div.wp-content p")?.text()
        val year = Regex("\\d{4}").find(document.select("span.date").text())?.value?.toIntOrNull()

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

        // 1. Troviamo i contenitori delle opzioni (Source Box / Options)
        // Questo legge i data-src che hai visto nel file film.txt
        val options = document.select("div[id^=option] iframe, .source-box iframe, li[id^=player-option-]")

        options.forEach { option ->
            val firstUrl = option.attr("data-src")
                .ifBlank { option.attr("src") }
                .ifBlank { option.attr("data-href") }
            
            if (firstUrl.isNotBlank() && !firstUrl.contains("about:blank")) {
                val cleanFirstUrl = if (firstUrl.startsWith("//")) "https:$firstUrl" else firstUrl
                
                try {
                    // 2. LOGICA DOPPIO SALTO (come Streamflix):
                    // Carichiamo la pagina interna del player di GuardaPlay
                    val innerPage = app.get(cleanFirstUrl, referer = data).document
                    
                    // 3. Estraiamo il vero link del server (Mixdrop, Supervideo, ecc.)
                    // Cerchiamo l'iframe finale dentro la classe .Video o tag iframe generici
                    val finalIframe = innerPage.selectFirst(".Video iframe, #player_code iframe, iframe[src*='embed']")
                    val finalUrl = finalIframe?.attr("src") ?: finalIframe?.attr("data-src")
                    
                    if (!finalUrl.isNullOrBlank()) {
                        val fixedFinalUrl = if (finalUrl.startsWith("//")) "https:$finalUrl" else finalUrl
                        
                        // 4. Invia all'estrattore di CloudStream
                        loadExtractor(fixedFinalUrl, cleanFirstUrl, subtitleCallback, callback)
                        found = true
                    }
                } catch (e: Exception) {
                    // Se il caricamento della pagina interna fallisce, proviamo comunque l'URL originale
                    loadExtractor(cleanFirstUrl, data, subtitleCallback, callback)
                }
            }
        }
        
        return found
    }
}
