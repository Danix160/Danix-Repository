package com.cineby

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

class CinebyProvider : MainAPI() {
    override var mainUrl = "https://www.cineby.gd"
    override var name = "Cineby"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Configurazione per le immagini tramite il proxy wsrv.nl usato dal sito
    private fun fixPoster(url: String?): String? {
        if (url == null) return null
        return if (url.contains("wsrv.nl")) {
            url // Mantieni il link proxy esistente
        } else {
            "https://wsrv.nl/?url=$url&output=webp"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val home = mutableListOf<HomePageList>()
        
        // Sezioni principali identificate: Trending, Netflix, Prime, ecc.
        document.select("div.flex.flex-col.gap-16.wrapper").select("div.flex.flex-col").forEach { section ->
            val title = section.selectFirst("h2")?.text() ?: "Featured"
            val items = section.select("div.movieCard_movieCard__rmkHO").mapNotNull {
                it.toSearchResult()
            }
            if (items.isNotEmpty()) home.add(HomePageList(title, items))
        }
        
        return HomePageResponse(home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // La ricerca usa il percorso /search con parametro q
        val url = "$mainUrl/search?q=$query"
        val document = app.get(url).document
        
        // Estrazione dai risultati della ricerca (glass-card-dark)
        return document.select("div.glass-card-dark").mapNotNull {
            val title = it.selectFirst("h3, span.font-semibold")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = fixPoster(it.selectFirst("img")?.attr("src"))

            if (href.contains("/movie/")) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("img")?.attr("alt") ?: this.selectFirst("h3")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = fixPoster(this.selectFirst("img")?.attr("src"))

        return if (href.contains("/movie/")) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val response = app.get(fullUrl)
        val document = response.document
        
        // Estrazione dati dal tag script __NEXT_DATA__
        val jsonData = document.selectFirst("script#__NEXT_DATA__")?.data()
        val title = document.selectFirst("title")?.text()?.replace(" - Cineby", "") ?: "Cineby Content"
        val poster = fixPoster(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[name=description]")?.attr("content")

        return if (url.contains("/movie/")) {
            newMovieLoadResponse(title, fullUrl, TvType.Movie, fullUrl) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            // Per le serie TV, Cloudstream richiede la lista degli episodi
            // In un'app Next.js questi sono spesso nel JSON __NEXT_DATA__ -> props -> pageProps
            val episodes = mutableListOf<Episode>()
            
            // Logica semplificata: se il sito non mostra gli episodi staticamente, 
            // Cloudstream caricherà l'URL della serie e noi dovremo parsare il JSON.
            newTvSeriesLoadResponse(title, fullUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Il file player.txt suggerisce che il player viene caricato tramite parametro ?play=true
        val playerUrl = if (data.contains("?play=true")) data else "$data?play=true"
        val document = app.get(playerUrl).document
        
        // Cineby solitamente usa wrapper per vidsrc.to, superembed o simili
        // Qui dovresti aggiungere gli extractor necessari (es. VidSrcToExtractor)
        // Esempio ipotetico di estrazione iframe:
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            loadExtractor(src, subtitleCallback, callback)
        }
        
        return true
    }
}
