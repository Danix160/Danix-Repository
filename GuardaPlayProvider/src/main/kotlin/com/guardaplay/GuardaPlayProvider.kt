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
        
        // Selettore basato sul tuo HTML: article con classe post e movies
        val home = document.select("article.post, article.movies, .post-thumbnail").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // 1. Estrazione Titolo: cerchiamo in h2.entry-title come nel tuo snippet
        val title = this.selectFirst("h2.entry-title")?.text() 
            ?: this.selectFirst("h3")?.text() 
            ?: this.selectFirst(".post-thumbnail img")?.attr("alt")?.replace("Image ", "")
            ?: return null
            
        // 2. Estrazione Link: il sito usa la classe lnk-blk per il link cliccabile
        val href = this.selectFirst("a.lnk-blk")?.attr("href") 
            ?: this.selectFirst("a")?.attr("href") 
            ?: return null
        
        // 3. Estrazione Immagine: gestione //image.tmdb...
        val imgElement = this.selectFirst(".post-thumbnail img, img")
        var posterUrl = imgElement?.attr("src") ?: ""
        
        if (posterUrl.startsWith("//")) {
            posterUrl = "https:$posterUrl"
        } else if (posterUrl.startsWith("/") && !posterUrl.startsWith("//")) {
            posterUrl = "https://image.tmdb.org/t/p/w500$posterUrl"
        }

        // 4. Estrazione Anno (opzionale ma utile)
        val year = this.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        // Il selettore di ricerca solitamente segue la stessa struttura della home
        return document.select("article.post, article.item").mapNotNull {
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

        // Selettori per trovare i server (Logica per saltare il player interno di GuardaPlay)
        val options = document.select("div[id^=option] iframe, .source-box iframe, li[id^=player-option-]")

        options.forEach { option ->
            val firstUrl = option.attr("data-src")
                .ifBlank { option.attr("src") }
                .ifBlank { option.attr("data-href") }
            
            if (firstUrl.isNotBlank() && !firstUrl.contains("about:blank")) {
                val cleanFirstUrl = if (firstUrl.startsWith("//")) "https:$firstUrl" else firstUrl
                
                try {
                    // Carichiamo la pagina intermedia per trovare l'iframe reale (es. Mixdrop/Supervideo)
                    val innerPage = app.get(cleanFirstUrl, referer = data).document
                    val finalIframe = innerPage.selectFirst(".Video iframe, #player_code iframe, iframe[src*='embed']")
                    val finalUrl = finalIframe?.attr("src") ?: finalIframe?.attr("data-src")
                    
                    if (!finalUrl.isNullOrBlank()) {
                        val fixedFinalUrl = if (finalUrl.startsWith("//")) "https:$finalUrl" else finalUrl
                        loadExtractor(fixedFinalUrl, cleanFirstUrl, subtitleCallback, callback)
                        found = true
                    }
                } catch (e: Exception) {
                    // Fallback: prova l'URL originale se il salto fallisce
                    loadExtractor(cleanFirstUrl, data, subtitleCallback, callback)
                }
            }
        }
        return found
    }
}
