package com.altadefinizione

import org.jsoup.nodes.Element
import org.json.JSONObject
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class AltaDefinizioneProvider : MainAPI() {
    override var mainUrl = "https://altadefinizione-01.forum"
    override var name = "AltaDefinizione"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true
    
    // 1. HOME PAGE
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()

        // Carosello "Al Cinema Ora"
        val sliderItems = document.select("div#slider div.boxgrid")
        if (sliderItems.isNotEmpty()) {
            val sliderResults = sliderItems.mapNotNull { card -> parseCard(card) }
            if (sliderResults.isNotEmpty()) {
                homePageList.add(HomePageList("Al Cinema Ora", sliderResults))
            }
        }

        // Griglia "Ultimi Arrivi"
        val gridItems = document.select("div.son_eklenen div.boxgrid:not(.slidercaprion)")
        if (gridItems.isNotEmpty()) {
            val gridResults = gridItems.mapNotNull { card -> parseCard(card) }
            if (gridResults.isNotEmpty()) {
                homePageList.add(HomePageList("Ultimi Arrivi", gridResults))
            }
        }

        return newHomePageResponse(homePageList)
    }

    private fun parseCard(card: Element): SearchResponse? {
        val titleElement = card.selectFirst(".ml-mask h3 a, .ml-mask h2 a, h2 a, h3 a")
        val title = titleElement?.text()?.trim() ?: return null
        val url = titleElement.attr("href") ?: return null

        val imgElement = card.selectFirst("img")
        val posterRaw = imgElement?.attr("data-src")?.ifBlank { imgElement.attr("src") } ?: ""
        val posterUrl = fixUrl(posterRaw)

        val genres = card.select(".ml-cat a").map { it.text().lowercase() }
        val isSerie = genres.contains("serie tv") || card.selectFirst(".se_num") != null || url.contains("-streaming-community")
        val type = if (isSerie) TvType.TvSeries else TvType.Movie

        val ratingRaw = card.selectFirst(".ml-imdb b")?.text()?.trim()
        val calculatedScore = ratingRaw?.toFloatOrNull()?.let { (it * 10).toInt() } 

        val qualityRaw = card.selectFirst(".trdublaj")?.text()?.trim() ?: "HD"
        val quality = getQualityFromString(qualityRaw)

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }
    }
    
    // 2. RICERCA
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select("div.boxgrid").mapNotNull {
            parseCard(it)
        }
    }

    // 3. DETTAGLI DELLA PAGINA
    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url)
        val document = response.document

        // Estrae il titolo pulito isolandolo da diciture tra parentesi
        val rawTitle = document.selectFirst("div.single_head h1[itemprop=name], div.single_head h1")?.text()?.trim() ?: return null
        val title = rawTitle.substringBefore("(").trim()
        
        // Recupera il poster dai tag Open Graph
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.ifBlank { document.selectFirst(".poster img, .movie-poster img")?.attr("src") }?.let { fixUrl(it) }
        
        // Estrae la sinossi
        val plot = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: document.selectFirst("#main-player p")?.text()?.trim()
        
        // Cerchiamo l'ID IMDb all'interno dei tag script della pagina principale
        val scripts = document.select("script").map { it.html() }
        var imdbId: String? = null
        for (script in scripts) {
            val match = Regex("""var\s+imdb\s*=\s*['"]tt(\d+)['"]""").find(script)
            if (match != null) {
                imdbId = "tt" + match.groupValues[1]
                break
            }
        }

        // Se troviamo l'ID IMDb e la pagina dichiara di contenere una serie o un player vidxgo-player, la trattiamo come Serie TV
        val hasVidxgoIframe = document.selectFirst("iframe#vidxgo-player, iframe[src*='vidxgo']") != null
        val isTvSeries = url.contains("-streaming-community") || !imdbId.isNullOrBlank() && hasVidxgoIframe

        return if (isTvSeries && !imdbId.isNullOrBlank()) {
            val episodes = mutableListOf<Episode>()
            
            // Definiamo i parametri standard richiesti dal server di VidxGo
            val refererUrl = url
            val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            
            // Cicliamo su un numero massimo di stagioni verosimili (es. 1 fino a 30)
            for (seasonNumber in 1..30) {
                try {
                    // Interroghiamo direttamente l'endpoint JSON interno di VidxGo copiando la logica di Streamflix
                    val jsonResponse = app.get(
                        url = "https://v.vidxgo.co/seasons.php?imdb=$imdbId&season=$seasonNumber",
                        headers = mapOf(
                            "Referer" to refererUrl,
                            "sec-fetch-dest" to "empty",
                            "User-Agent" to userAgent
                        )
                    ).text

                    val json = JSONObject(jsonResponse)
                    if (json.optInt("ok") == 1) {
                        val episodesArray = json.getJSONArray("episodes")
                        for (i in 0 until episodesArray.length()) {
                            val epObject = episodesArray.getJSONObject(i)
                            val episodeNumber = epObject.getInt("number")
                            val epName = epObject.optString("name").ifBlank { "Episodio $episodeNumber" }
                            val epPlot = epObject.optString("overview")
                            val epThumb = epObject.optString("still")

                            episodes.add(
                                newEpisode("$url#$imdbId#$seasonNumber#$episodeNumber") {
                                    this.name = epName
                                    this.season = seasonNumber
                                    this.episode = episodeNumber
                                    this.description = epPlot
                                    this.posterUrl = epThumb
                                }
                            )
                        }
                    } else {
                        // Se il server risponde "ok": 0 o non ci sono più episodi, interrompiamo il ciclo delle stagioni
                        break
                    }
                } catch (e: Exception) {
                    // Se una stagione fallisce o non esiste, interrompe la ricerca sequenziale
                    break
                }
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            // Logica standard per i Film (se non è una serie o non ha IMDb seriale, invia l'url di base a loadLinks)
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

   override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // Riceve l'URL della pagina di visualizzazione (passato dal metodo load)
            val response = app.get(data).text
            val document = Jsoup.parse(response)

            // Estraggo l'ID IMDb nascosto nell'attributo di stile del player-cover
            val playerCover = document.selectFirst("#player-cover") ?: return false
            val styleAttr = playerCover.attr("style")
            
            val imdbRegex = Regex("(tt\\d+)")
            val imdbId = imdbRegex.find(styleAttr)?.groupValues?.get(1) ?: return false

            // Chiamata all'endpoint delle stagioni identificato nell'HAR
            val seasonsApiUrl = "https://v.vidxgo.co/seasons.php?imdb=$imdbId&season=1"
            val apiResponse = app.get(
                url = seasonsApiUrl,
                headers = mapOf(
                    "Referer" to data,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            ).text

            val vidxGo = VidxGoExtractor()

            // Verifichiamo se l'API risponde con un dizionario JSON (Tipico delle Serie TV)
            if (apiResponse.trim().startsWith("{")) {
                val jsonObject = JSONObject(apiResponse)
                val keys = jsonObject.keys()
                
                while (keys.hasNext()) {
                    val episodeKey = keys.next() // ID numerico dell'episodio
                    val episodeUrl = "https://v.vidxgo.co/t/$imdbId/1/$episodeKey"
                    
                    // Invocazione dell'estrattore con il formato URL API di VidxGo (/t/)
                    vidxGo.getUrl(
                        url = episodeUrl,
                        referer = data,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }
            } else {
                // Se non è un JSON, si tratta di un Film singolo. Usiamo l'embed classico
                // per far attivare la decodifica XOR (Ramo 'else' dell'estrattore)
                val movieEmbedUrl = "https://v.vidxgo.co/embed/$imdbId"
                
                vidxGo.getUrl(
                    url = movieEmbedUrl,
                    referer = data,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}
