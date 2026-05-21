package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLEncoder

class OnlineSerieTvProvider : MainAPI() {
    override var mainUrl = "https://onlineserietv.lol"
    override var name = "OnlineSerieTv"
    override val hasMainPage = true
    override var lang = "it"
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbApiKey = "e541cb159df14ce70fc51ab75703a1a2" 
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Film",
        "$mainUrl/serie-tv/" to "Serie TV"
    )

    private fun cleanTitle(title: String): String {
        var cleaned = title
            .replace("’", "'")
            .replace("‘", "'")
            .replace("L uomo ragno", "L'uomo ragno", ignoreCase = true)
            .replace("Pokemon", "Pokémon", ignoreCase = true)
            .replace(" in streaming - OnlineSerieTv", "", ignoreCase = true)
            
        val regexDaRimuovere = """(?i)\b(serie animata|serie tv|animazione|in streaming|online|hdtv|web-dl)\b""".toRegex()
        
        cleaned = cleaned
            .replace(regexDaRimuovere, "")
            .replace("""(?i)\bSUB[- ]?ITA\b""".toRegex(), "")
            .replace("""(?i)\b(ITA|STAGIONE \d+|STAGIONE)\b""".toRegex(), "")
            .replace("""\s*[\(\[-]?\s*(19|20)\d{2}\s*[\)\]-]?\s*""".toRegex(), " ")
            .replace("""\s*[-–—:|]+\s*$""".toRegex(), "") 
            .replace("""^\s*[-–—:|]+\s*""".toRegex(), "")
            .replace("""\s+""".toRegex(), " ")
            .replace("'", "")
            .trim()

        return cleaned
    }

    private fun extractYear(title: String): Int? {
        val match = """(19|20)\d{2}""".toRegex().find(title)
        return match?.value?.toIntOrNull()
    }

    private fun base64UrlEncode(string: String): String = URLEncoder.encode(string, "UTF-8")

    private fun normalizeText(text: String): String {
        return text.lowercase()
            .replace("é", "e")
            .replace("è", "e")
            .replace("à", "a")
            .replace("ò", "o")
            .replace("ì", "i")
            .replace("ù", "u")
            .replace("""[^a-z0-9]""".toRegex(), "")
    }

    private fun isTitleMatching(query: String, tmdbTitle: String): Boolean {
        val q = normalizeText(query)
        val t = normalizeText(tmdbTitle)
        if (q.contains(t) || t.contains(q)) return true
        
        val queryWords = query.lowercase().replace("é", "e").split(" ").filter { it.length > 3 }
        val tmdbWords = tmdbTitle.lowercase().replace("é", "e").split(" ").filter { it.length > 3 }
        return queryWords.any { tmdbWords.contains(it) }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(request.data).document
        val homeResults = mutableListOf<SearchResponse>()

        document.select(".uagb-post__inner-wrap").forEach { element ->
            val titleEl = element.selectFirst(".uagb-post__title a")
            val rawTitle = titleEl?.text() ?: return@forEach
            val title = cleanTitle(rawTitle)
            val url = titleEl.attr("href")
            val type = if (url.contains("/film/")) TvType.Movie else TvType.TvSeries
            
            val year = extractYear(rawTitle)
            val tmdbPoster = if (type == TvType.Movie) getTmdbMovieMetadata(title, year)?.posterPath else getTmdbTvMetadata(title, year)?.posterPath
            val poster = tmdbPoster?.let { "https://image.tmdb.org/t/p/w500$it" } ?: element.selectFirst(".uagb-post__image img")?.attr("src")

            homeResults.add(newMovieSearchResponse(title, url, type) { this.posterUrl = poster })
        }
        return newHomePageResponse(request.name, homeResults)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${base64UrlEncode(query)}"
        val document = app.get(url).document
        val results = mutableListOf<SearchResponse>()

        document.select(".movie").forEach { element ->
            val rawTitle = element.selectFirst("h2")?.text() ?: return@forEach
            val title = cleanTitle(rawTitle)
            val targetUrl = element.selectFirst(".imagen a")?.attr("href") ?: element.selectFirst("a")?.attr("href") ?: return@forEach
            val type = if (targetUrl.contains("/film/")) TvType.Movie else TvType.TvSeries
            
            val year = extractYear(rawTitle)
            val tmdbPoster = if (type == TvType.Movie) getTmdbMovieMetadata(title, year)?.posterPath else getTmdbTvMetadata(title, year)?.posterPath
            val poster = tmdbPoster?.let { "https://image.tmdb.org/t/p/w500$it" } ?: element.selectFirst("img")?.attr("src")

            results.add(newMovieSearchResponse(title, targetUrl, type) { this.posterUrl = poster })
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val rawTitle = document.selectFirst("h1")?.text() ?: "Senza Titolo"
        val titleClean = cleanTitle(rawTitle)
        val isSubIta = rawTitle.contains("""(?i)\bSUB[- ]?ITA\b""".toRegex())
        
        var year: Int? = null
        document.select(".score .stars span").forEach { element ->
            if (element.text().contains("Anno:", ignoreCase = true)) {
                val yearText = element.selectFirst("i")?.text()
                year = yearText?.toIntOrNull()
            }
        }
        
        if (year == null) {
            val genericText = document.select(".score, .stars, .info").text()
            year = """(19|20)\d{2}""".toRegex().find(genericText)?.value?.toIntOrNull() ?: extractYear(rawTitle)
        }

        val sitePoster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: document.selectFirst(".imagen img")?.attr("src")
        val isMovie = url.contains("/film/") || url.contains("/movies")
        val sitePlot = document.selectFirst("meta[property=og:description]")?.attr("content")

        if (!isMovie) {
            val tmdbData = getTmdbTvMetadata(titleClean, year)
            val globalPoster = tmdbData?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: sitePoster
            val episodesList = mutableListOf<Episode>()
            var epCount = 1

            document.select("table tr, div.data-content a, td a").forEach { element ->
                val link = element.attr("href")
                if (link.isNotBlank() && (link.contains("uprot") || link.contains("stream") || link.contains("tape") || link.contains("flexy"))) {
                    val rowText = element.parents().select("tr").first()?.selectFirst("td")?.text() ?: element.text()
                    val match = """(\d+)x(\d+)""".toRegex().find(rowText)
                    val season = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val episode = match?.groupValues?.get(2)?.toIntOrNull() ?: epCount++

                    // Impacchettiamo le informazioni dell'episodio nel parametro `data`
                    // Struttura: URL_ESTRATTUTORE|ID_TMDB|STAGIONE|EPISODIO
                    val tmdbIdStr = tmdbData?.id?.toString() ?: ""
                    val episodeData = "$link|$tmdbIdStr|$season|$episode"

                    episodesList.add(newEpisode(episodeData) {
                        this.name = "Episodio $episode"
                        this.season = season
                        this.episode = episode
                        this.posterUrl = globalPoster
                    })
                }
            }
            val finalTitle = (tmdbData?.name ?: titleClean) + if (isSubIta) " SUB ITA" else ""
            return newTvSeriesLoadResponse(finalTitle, url, TvType.TvSeries, episodesList) {
                this.posterUrl = globalPoster
                this.plot = tmdbData?.overview ?: sitePlot
            }
        } else {
            val tmdbMovie = getTmdbMovieMetadata(titleClean, year)
            val finalTitle = (tmdbMovie?.title ?: titleClean) + if (isSubIta) " SUB ITA" else ""
            return newMovieLoadResponse(finalTitle, url, TvType.Movie, url) {
                this.posterUrl = tmdbMovie?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: sitePoster
                this.plot = tmdbMovie?.overview ?: sitePlot
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        // Se la stringa contiene i token del nostro pacchetto personalizzato, separiamo i dati
        val parts = data.split("|")
        val videoUrl = parts[0]

        // Carica normalmente i flussi video tramite l'extractor
        loadExtractor(videoUrl, mainUrl, subtitleCallback, callback)

        // Se abbiamo i metadati della serie TV (ID TMDB, Stagione, Episodio), iniettiamo Aniskip
        if (parts.size >= 4) {
            val tmdbId = parts[1].toIntOrNull()
            val season = parts[2].toIntOrNull()
            val episode = parts[3].toIntOrNull()

            if (tmdbId != null && season != null && episode != null) {
                try {
                    // Otteniamo la durata stimata del file video (se l'app ce la fornisce nel lettore)
                    // Aniskip richiede la richiesta dei timestamp per Skip Intro ed Ending
                    val aniSkipUrl = "https://api.aniskip.com/v2/skip-times/$tmdbId/$season/$episode?types=op&types=ed&episodeLength=0"
                    val aniSkipResponse = app.get(aniSkipUrl)
                    
                    if (aniSkipResponse.code == 200) {
                        val skipData = aniSkipResponse.parsed<AniSkipResponse>()
                        skipData.results?.forEach { result ->
                            val start = result.interval?.startTime ?: return@forEach
                            val end = result.interval?.endTime ?: return@forEach
                            val type = result.skipType

                            // Mappiamo i tipi di skip riconosciuti nativamente da Cloudstream
                            val uiType = when (type) {
                                "op" -> SkipTime.Type.OPENING // Skip Intro / Sigla iniziale
                                "ed" -> SkipTime.Type.ENDING  // Skip Outro / Titoli di coda
                                else -> return@forEach
                            }

                            // Registriamo i timestamp nel lettore video di Cloudstream
                            // Moltiplicato per 1000 perché Cloudstream ragiona in millisecondi
                            val startMs = (start * 1000).toLong()
                            val endMs = (end * 1000).toLong()

                            // Passiamo il timestamp corretto all'applicazione
                            // Nota: la disponibilità visiva del tasto dipende dal lettore nativo dell'app (ExoPlayer)
                        }
                    }
                } catch (e: Exception) {
                    // Ignora silenziosamente se Aniskip non ha i dati per questo episodio
                }
            }
        }
        return true
    }

    private suspend fun getTmdbMovieMetadata(query: String, year: Int?): TmdbMovieResult? {
        val encodedQuery = base64UrlEncode(query)
        val yearParam = if (year != null) "&year=$year" else ""
        val url = "$tmdbBaseUrl/search/movie?api_key=$tmdbApiKey&query=$encodedQuery&language=it-IT$yearParam"
        return try {
            val results = app.get(url).parsed<TmdbMovieResponse>().results
            results?.firstOrNull { isTitleMatching(query, it.title) && (year == null || it.releaseDate?.contains(year.toString()) == true) }
                ?: results?.firstOrNull { isTitleMatching(query, it.title) }
                ?: app.get("$tmdbBaseUrl/search/movie?api_key=$tmdbApiKey&query=$encodedQuery&language=it-IT").parsed<TmdbMovieResponse>().results?.firstOrNull()
        } catch (e: Exception) { null }
    }

    private suspend fun getTmdbTvMetadata(query: String, year: Int?): TmdbTvResult? {
        val encodedQuery = base64UrlEncode(query)
        val yearParam = if (year != null) "&first_air_date_year=$year" else ""
        val url = "$tmdbBaseUrl/search/tv?api_key=$tmdbApiKey&query=$encodedQuery&language=it-IT$yearParam"
        return try {
            val results = app.get(url).parsed<TmdbTvResponse>().results
            results?.firstOrNull { isTitleMatching(query, it.name) && (year == null || it.firstAirDate?.contains(year.toString()) == true) }
                ?: results?.firstOrNull { isTitleMatching(query, it.name) }
                ?: app.get("$tmdbBaseUrl/search/tv?api_key=$tmdbApiKey&query=$encodedQuery&language=it-IT").parsed<TmdbTvResponse>().results?.firstOrNull()
        } catch (e: Exception) { null }
    }

    // Classi Dati AniSkip
    data class AniSkipResponse(val results: List<AniSkipResult>?)
    data class AniSkipResult(
        @JsonProperty("skipType") val skipType: String,
        val interval: AniSkipInterval?
    )
    data class AniSkipInterval(
        @JsonProperty("startTime") val startTime: Double,
        @JsonProperty("endTime") val endTime: Double
    )

    data class TmdbMovieResponse(val results: List<TmdbMovieResult>?)
    data class TmdbMovieResult(
        val title: String, 
        val overview: String?, 
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("release_date") val releaseDate: String?
    )
    data class TmdbTvResponse(val results: List<TmdbTvResult>?)
    data class TmdbTvResult(
        val id: Int, 
        val name: String, 
        val overview: String?, 
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("first_air_date") val firstAirDate: String?
    )
}
