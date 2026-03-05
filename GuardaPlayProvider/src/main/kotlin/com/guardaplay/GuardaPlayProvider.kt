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
    // Cerchiamo il titolo e il link all'interno del tag 'a' che di solito avvolge l'immagine o il titolo
    val title = this.selectFirst("h3 a, a")?.text() ?: "Senza Titolo"
    val href = this.selectFirst("h3 a, a")?.attr("href") ?: return null
    
    // ESTRAZIONE LOCANDINA dal codice che mi hai mandato
    // Cerchiamo il tag 'img' dentro '.post-thumbnail'
    val imgElement = this.selectFirst(".post-thumbnail img, img")
    var posterUrl = imgElement?.attr("src") ?: ""
    
    // Se il sito usa il lazy loading, l'immagine vera potrebbe essere in 'data-src' o 'data-lazy-src'
    if (posterUrl.isBlank() || posterUrl.contains("data:image")) {
        posterUrl = imgElement?.attr("data-src") ?: imgElement?.attr("data-lazy-src") ?: ""
    }

    // FIX DEFINITIVO: Se l'URL inizia con //, aggiungiamo https:
    if (posterUrl.startsWith("//")) {
        posterUrl = "https:$posterUrl"
    } else if (posterUrl.startsWith("/")) {
        // Se è un percorso relativo tipo /t/p/w500/...
        posterUrl = "https://image.tmdb.org/t/p/w500$posterUrl"
    }

    return newMovieSearchResponse(title, href, TvType.Movie) {
        this.posterUrl = posterUrl
    }
}

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text() ?: return null
        
        var posterUrl = document.selectFirst("div.poster img")?.attr("src") ?: ""
        if (posterUrl.startsWith("//")) {
            posterUrl = "https:$posterUrl"
        } else if (posterUrl.startsWith("/")) {
            posterUrl = "https://image.tmdb.org/t/p/w500$posterUrl"
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
        
        // Il confronto tra i tuoi due file mostra che i link sono dentro i "Source Box" 
        // o nelle liste dei player (li[id^=player-option-])
        
        // 1. Cerchiamo tutti i possibili iframe (sia con src che con data-src)
        val sources = document.select("div.source-box iframe, div[id^=option-] iframe, .Video iframe")
        
        sources.forEach { iframe ->
            // Prendiamo data-src se src è vuoto (comune prima del click)
            val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }
            
            if (src.isNotBlank()) {
                val finalUrl = if (src.startsWith("//")) "https:$src" else src
                
                // Log per debug interno (opzionale)
                // println("Trovato link: $finalUrl")
                
                loadExtractor(finalUrl, data, subtitleCallback, callback)
            }
        }

        // 2. Metodo alternativo: Scansione script per link nascosti (se il punto 1 fallisce)
        if (document.select("iframe").isEmpty()) {
            document.select("script").forEach { script ->
                val content = script.data()
                if (content.contains("src=\"http") || content.contains("data-src=\"http")) {
                    val regex = Regex("""(?:src|data-src)="([^"]+)"""")
                    regex.findAll(content).forEach { match ->
                        val foundUrl = match.groupValues[1]
                        if (foundUrl.contains("embed") || foundUrl.contains("player")) {
                             loadExtractor(foundUrl, data, subtitleCallback, callback)
                        }
                    }
                }
            }
        }
        
        return true
    }
}
