package com.altadefinizione

import org.jsoup.nodes.Element
import org.json.JSONObject
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class AltaDefinizioneProvider : MainAPI() {
    override var mainUrl = "https://altadefinizione.you"
    override var name = "AltaDefinizione"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true
    
    // 1. HOME PAGE
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page > 1) "$mainUrl/page/$page/" else mainUrl
        val document = app.get(url).document
        val homePages = mutableListOf<HomePageList>()

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
            newHomePageResponse(homePages, hasNext = true)
        } else {
            null
        }
    }
    
    // 2. RICERCA
    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/?story=$query" 
        val document = app.get(searchUrl).document

        val searchElements = document.select(".movie")

        if (searchElements.isEmpty()) return null

        return searchElements.mapNotNull { element ->
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
    }

    // 3. DETTAGLI DELLA PAGINA (LOAD)
    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url)
        val document = response.document
        val htmlContent = response.text

        val title = document.selectFirst("meta[property='og:title']")?.attr("content")?.trim() 
            ?: document.selectFirst("h1")?.text()?.trim() ?: return null
            
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
        val plot = document.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val imdbRegex = Regex("""tt\d{7,8}""")
        val imdbId = imdbRegex.find(htmlContent)?.value

        val isTvSeries = url.contains("/serie-tv/") || document.selectFirst(".series-start") != null

        return if (isTvSeries && !imdbId.isNullOrBlank()) {
            val episodes = mutableListOf<Episode>()
            var consecutiveErrors = 0

            for (seasonNumber in 1..30) {
                if (consecutiveErrors > 3) break 

                try {
                    val jsonResponse = app.get(
                        url = "https://v.vidxgo.co/seasons.php?imdb=$imdbId&season=$seasonNumber",
                        headers = mapOf(
                            "Referer" to url,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
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

                            // Payload solido per l'url dell'episodio
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
            val movieData = if (!imdbId.isNullOrBlank()) "$url#$imdbId" else url
            newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // 4. ESTRAZIONE LINK VIDEO (LOADLINKS)
   // 4. ESTRAZIONE LINK VIDEO (LOADLINKS)
    // 4. ESTRAZIONE LINK VIDEO (LOADLINKS)
    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            if (data.contains("#")) {
                val parts = data.split("#")
                
                if (parts.size >= 4) {
                    // -------------------------------------------------------------
                    // LOGICA RISOLUTIVA PER LE SERIE TV (Risposta JSON di VidxGo)
                    // -------------------------------------------------------------
                    val baseUrl = parts[0]
                    val imdbId = parts[1]
                    val season = parts[2]
                    val episode = parts[3]

                    val targetUrl = "https://v.vidxgo.co/t/$imdbId/$season/$episode"
                    
                    val response = app.get(
                        url = targetUrl,
                        headers = mapOf(
                            "Referer" to baseUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        )
                    ).text

                    if (response.isNotBlank() && response.startsWith("{")) {
                        val json = JSONObject(response)
                        val videoUrl = json.optString("url")
                        
                        if (!videoUrl.isNullOrBlank()) {
                            // Inizializzazione pulita e nativa compatibile con tutte le versioni SDK
                            callback.invoke(
                                ExtractorLink(
                                    source = "VidxGo (API)",
                                    name = "VidxGo Serie TV",
                                    url = videoUrl,
                                    referer = "https://vidxgo.co/",
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = true,
                                    headers = mapOf()
                                )
                            )
                            return true
                        }
                    }
                    return false

                } else if (parts.size == 2) {
                    // -------------------------------------------------------------
                    // LOGICA PER I FILM (Estrattore standard sul player Web)
                    // -------------------------------------------------------------
                    val baseUrl = parts[0]
                    val imdbId = parts[1]
                    val movieEmbedUrl = "https://v.vidxgo.co/embed/$imdbId"

                    val vidxGo = VidxGoExtractor()
                    vidxGo.getUrl(
                        url = movieEmbedUrl,
                        referer = baseUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }
            } else {
                // FALLBACK PER URL PULITO (FILM)
                val response = app.get(data).text
                val imdbRegex = Regex("""tt\d{7,8}""")
                val imdbId = imdbRegex.find(response)?.value ?: return false

                val movieEmbedUrl = "https://v.vidxgo.co/embed/$imdbId"
                val vidxGo = VidxGoExtractor()
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
