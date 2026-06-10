package com.altadefinizione

import org.jsoup.nodes.Element
import org.json.JSONObject
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class AltaDefinizioneProvider : MainAPI() {
    override var mainUrl = "https://altadefinizione.you"
    override var name = "AltaDefinizione"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true
    
    // 1. HOME PAGE
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
    // Carichiamo la pagina corrente (gestendo la paginazione se page > 1)
    val url = if (page > 1) "$mainUrl/page/$page/" else mainUrl
    val document = app.get(url).document
    val homePages = mutableListOf<HomePageList>()

    // Estraiamo tutti i blocchi .movie presenti nella pagina
    val movieElements = document.select(".movie")

    if (movieElements.isNotEmpty()) {
        val list = movieElements.mapNotNull { element ->
            val titleElement = element.selectFirst(".movie-title a") ?: return@mapNotNull null
            val name = titleElement.text().trim()
            
            val link = element.attr("data-link").ifBlank { titleElement.attr("href") }
            val absoluteUrl = fixUrl(link)

            val posterElement = element.selectFirst(".movie-poster img, img.layer-image")
            val poster = posterElement?.attr("src")?.let { fixUrl(it) }

            val category = element.attr("data-category").lowercase()
            val isTv = category.contains("serie") || absoluteUrl.contains("/serie-tv/")

            if (isTv) {
                newTvSeriesSearchResponse(name, absoluteUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(name, absoluteUrl, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }.distinctBy { it.url }

        if (list.isNotEmpty()) {
            val titleSection = if (page > 1) "Pagina $page" else "Ultimi Aggiornamenti"
            homePages.add(HomePageList(titleSection, list))
        }
    }

    return if (homePages.isNotEmpty()) {
        newHomePageResponse(homePages, hasNext = true) // 'hasNext = true' permette a Cloudstream di caricare altre pagine scrollando
    } else {
        null
    }
}
    
    // 2. RICERCA

    
   override suspend fun search(query: String): List<SearchResponse>? {
    // Creiamo l'URL di ricerca (di solito passata come parametro GET o s=)
    // Se il sito usa il classico endpoint DLE/WordPress, adattalo (es. ?story=$query o ?s=$query)
    val searchUrl = "$mainUrl/?story=$query" 
    val document = app.get(searchUrl).document

    // Estraiamo tutte le card dei film/serie trovate
    val searchElements = document.select(".movie")

    if (searchElements.isEmpty()) return null

    return searchElements.mapNotNull { element ->
        val titleElement = element.selectFirst(".movie-title a") ?: return@mapNotNull null
        val name = titleElement.text().trim()
        
        // Recuperiamo il link (dall'attributo data-link o dall'href classico)
        val link = element.attr("data-link").ifBlank { titleElement.attr("href") }
        val absoluteUrl = fixUrl(link)

        // Recuperiamo la locandina
        val posterElement = element.selectFirst(".movie-poster img, img.layer-image")
        val poster = posterElement?.attr("src")?.let { fixUrl(it) }

        // Riconoscimento Categoria (Film o Serie)
        val category = element.attr("data-category").lowercase()
        val isTv = category.contains("serie") || absoluteUrl.contains("/serie-tv/")

        if (isTv) {
            newTvSeriesSearchResponse(name, absoluteUrl, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(name, absoluteUrl, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }.distinctBy { it.url }
}

    
    
    // 3. DETTAGLI DELLA PAGINA
    override suspend fun load(url: String): LoadResponse? {
    val response = app.get(url)
    val document = response.document
    val htmlContent = response.text

    // 1. Estrazione Dati Generici dai Meta Tag (Precisi e puliti)
    val title = document.selectFirst("meta[property='og:title']")?.attr("content")?.trim() 
        ?: document.selectFirst("h1")?.text()?.trim() ?: return null
        
    val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
    val plot = document.selectFirst("meta[name='description']")?.attr("content")?.trim()

    // 2. Estrazione ID IMDb (Cerca il pattern tt seguito da 7 o 8 cifre)
    // Nel tuo sorgente: /uploads/background/tt9813792.jpg
    val imdbRegex = Regex("""tt\d{7,8}""")
    val imdbId = imdbRegex.find(htmlContent)?.value

    // 3. Riconoscimento se è una Serie TV o un Film
    // Controlliamo se l'URL contiene "/serie-tv/" (come nel tuo file) o se ci sono indicatori specifici
    val isTvSeries = url.contains("/serie-tv/") || document.selectFirst(".series-start") != null

    // 4. Gestione Serie TV con l'API VidxGo
    return if (isTvSeries && !imdbId.isNullOrBlank()) {
        val episodes = mutableListOf<Episode>()
        var consecutiveErrors = 0

        // Ciclo flessibile sulle stagioni
        for (seasonNumber in 1..30) {
            if (consecutiveErrors > 3) break 

            try {
                // Interroghiamo l'endpoint di VidxGo usando l'IMDb estratto
                val jsonResponse = app.get(
                    url = "https://v.vidxgo.co/seasons.php?imdb=$imdbId&season=$seasonNumber",
                    headers = mapOf(
                        "Referer" to url,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                ).text

                if (jsonResponse.isBlank() || !jsonResponse.startsWith("{")) {
                    consecutiveErrors++
                    continue
                }

                val json = JSONObject(jsonResponse)
                if (json.optInt("ok") == 1) {
                    consecutiveErrors = 0 
                    val episodesArray = json.getJSONArray("episodes")
                    
                    for (i in 0 until episodesArray.length()) {
                        val epObject = episodesArray.getJSONObject(i)
                        val episodeNumber = epObject.getInt("number")
                        val epName = epObject.optString("name").ifBlank { "Episodio $episodeNumber" }
                        val epPlot = epObject.optString("overview")
                        val epThumb = epObject.optString("still")

                        // Assembliamo un data-payload custom nell'url dell'episodio per passarlo a loadLinks
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
                    consecutiveErrors++
                    continue
                }
            } catch (e: Exception) {
                consecutiveErrors++
                continue
            }
        }

        // Se VidxGo non ha restituito nulla per via dell'ID, facciamo fallback a Film
        if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    } else {
        // Gestione standard per i Film
        // Passiamo l'URL unito all'IMDb come data payload se disponibile per facilitare loadLinks
        val movieData = if (!imdbId.isNullOrBlank()) "$url#$imdbId" else url
        newMovieLoadResponse(title, url, TvType.Movie, movieData) {
            this.posterUrl = poster
            this.plot = plot
        }
    }
}

///////////////////////////
// LOADLINKS
//////////////////////////
override suspend fun loadLinks(
    data: String,
    isCdn: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    try {
        val vidxGo = VidxGoExtractor()

        // Verifichiamo se l'URL contiene il payload con i cancelletti (#) generato dal metodo load
        if (data.contains("#")) {
            val parts = data.split("#")
            
            if (parts.size >= 4) {
                // LOGICA SERIE TV
                // Struttura: [0] baseUrl, [1] imdbId, [2] seasonNumber, [3] episodeNumber
                val baseUrl = parts[0]
                val imdbId = parts[1]
                val season = parts[2]
                val episode = parts[3]

                // Generiamo l'URL esatto dell'episodio selezionato per l'estrattore VidxGo
                val episodeUrl = "https://v.vidxgo.co/t/$imdbId/$season/$episode"
                
                vidxGo.getUrl(
                    url = episodeUrl,
                    referer = baseUrl,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            } else if (parts.size == 2) {
                // LOGICA FILM (con payload IMDb)
                val baseUrl = parts[0]
                val imdbId = parts[1]
                val movieEmbedUrl = "https://v.vidxgo.co/embed/$imdbId"

                vidxGo.getUrl(
                    url = movieEmbedUrl,
                    referer = baseUrl,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }
        } else {
            // FALLBACK: Se l'url arriva pulito (es. da segnalibri o vecchie migrazioni), 
            // ricarichiamo la pagina ed estraiamo l'IMDb al volo usando una regex flessibile
            val response = app.get(data).text
            val imdbRegex = Regex("""tt\d{7,8}""")
            val imdbId = imdbRegex.find(response)?.value ?: return false

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
