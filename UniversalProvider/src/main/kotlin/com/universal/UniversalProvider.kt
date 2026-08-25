package com.universal

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.universal.models.UniversalMedia
import com.universal.sources.Altadefinizione01Source
import com.universal.sources.OnlineSerieTvSource
import com.universal.sources.SourceAdapter
import com.universal.models.ProviderEpisode
import com.universal.utils.EpisodeMapper
import org.json.JSONObject
import java.net.URLEncoder

class UniversalProvider : MainAPI() {

    override var name =
        "Universal"

    override var mainUrl =
        "https://api.themoviedb.org/3"

    override var lang =
        "it"

    override val supportedTypes =
        setOf(
            TvType.Movie,
            TvType.TvSeries
        )

    override val hasMainPage =
        true

    override val hasQuickSearch =
        true

    companion object {

        private const val TAG =
            "UNIVERSAL"

        /*
         * Inserisci qui la tua API key TMDB.
         *
         * Non pubblicare una chiave personale
         * in un repository pubblico.
         */
        private const val TMDB_API_KEY =
            "e541cb159df14ce70fc51ab75703a1a2"

        private const val IMAGE_BASE =
            "https://image.tmdb.org/t/p/w500"

        private const val BACKDROP_BASE =
            "https://image.tmdb.org/t/p/original"
    }

    /*
     * Ordine automatico:
     *
     * 1 Altadefinizione01
     * 2 OnlineSerieTV
     * 3 CB01
     */
    private val sources:
    List<SourceAdapter> =
    listOf(
        Altadefinizione01Source(),
        OnlineSerieTvSource()
    )
        .sortedBy {
            it.priority
        }

    override val mainPage =
        mainPageOf(
            "$mainUrl/trending/all/week" to
                "Di tendenza",

            "$mainUrl/movie/popular" to
                "Film popolari",

            "$mainUrl/tv/popular" to
                "Serie popolari",

            "$mainUrl/movie/now_playing" to
                "Film del momento",

            "$mainUrl/tv/on_the_air" to
                "Serie in onda"
        )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val separator =
            if (
                request.data.contains("?")
            ) {
                "&"
            } else {
                "?"
            }

        val url =
            request.data +
                separator +
                "api_key=$TMDB_API_KEY" +
                "&language=it-IT" +
                "&page=$page"

        Log.d(
            TAG,
            "TMDB HOME = $url"
        )

        val response =
            app.get(
                url
            )

        if (
            response.code !in
            200..299
        ) {

            return newHomePageResponse(
                request.name,
                emptyList()
            )
        }

        val json =
            JSONObject(
                response.text
            )

        val results =
            json.optJSONArray(
                "results"
            )

        val items =
            mutableListOf<SearchResponse>()

        if (results != null) {

            for (
                i in 0 until
                    results.length()
            ) {

                val item =
                    results.optJSONObject(
                        i
                    )
                        ?: continue

                parseSearchResult(
                    item
                )
                    ?.let {
                        items.add(it)
                    }
            }
        }

        return newHomePageResponse(
            request.name,
            items
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encoded =
            URLEncoder.encode(
                query,
                "UTF-8"
            )

        val url =
            "$mainUrl/search/multi" +
                "?api_key=$TMDB_API_KEY" +
                "&language=it-IT" +
                "&query=$encoded" +
                "&include_adult=false"

        val response =
            app.get(
                url
            )

        if (
            response.code !in
            200..299
        ) {

            return emptyList()
        }

        val json =
            JSONObject(
                response.text
            )

        val array =
            json.optJSONArray(
                "results"
            )
                ?: return emptyList()

        val output =
            mutableListOf<SearchResponse>()

        for (
            i in 0 until
                array.length()
        ) {

            val item =
                array.optJSONObject(
                    i
                )
                    ?: continue

            parseSearchResult(
                item
            )
                ?.let {
                    output.add(it)
                }
        }

        return output
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {

        return search(
            query
        )
            .take(
                10
            )
    }

    private fun parseSearchResult(
        item: JSONObject
    ): SearchResponse? {

        val mediaType =
            item.optString(
                "media_type"
            )

        /*
         * Nelle sezioni movie/tv il campo
         * media_type può non esserci.
         */
        val isMovie =
            when {

                mediaType == "movie" ->
                    true

                mediaType == "tv" ->
                    false

                item.has(
                    "title"
                ) ->
                    true

                item.has(
                    "name"
                ) ->
                    false

                else ->
                    return null
            }

        val id =
            item.optInt(
                "id",
                0
            )

        if (
            id <= 0
        ) {
            return null
        }

        val title =
            if (isMovie) {

                item.optString(
                    "title"
                )

            } else {

                item.optString(
                    "name"
                )
            }

        if (
            title.isBlank()
        ) {
            return null
        }

        val posterPath =
            item.optString(
                "poster_path"
            )

        val poster =
            posterPath
                .takeIf {
                    it.isNotBlank() &&
                    it != "null"
                }
                ?.let {
                    IMAGE_BASE + it
                }

        val year =
            extractYear(
                if (isMovie) {
                    item.optString(
                        "release_date"
                    )
                } else {
                    item.optString(
                        "first_air_date"
                    )
                }
            )

        val data =
            buildLoadData(
                tmdbId = id,
                isMovie = isMovie
            )

        return if (isMovie) {

            newMovieSearchResponse(
                title,
                data,
                TvType.Movie
            ) {

                this.posterUrl =
                    poster

                this.year =
                    year
            }

        } else {

            newTvSeriesSearchResponse(
                title,
                data,
                TvType.TvSeries
            ) {

                this.posterUrl =
                    poster

                this.year =
                    year
            }
        }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val data =
            parseLoadData(
                url
            )
                ?: return null

        val tmdbId =
            data.first

        val isMovie =
            data.second

        return if (isMovie) {

            loadMovie(
                tmdbId
            )

        } else {

            loadTv(
                tmdbId
            )
        }
    }

    private suspend fun loadMovie(
        tmdbId: Int
    ): LoadResponse? {

        val url =
            "$mainUrl/movie/$tmdbId" +
                "?api_key=$TMDB_API_KEY" +
                "&language=it-IT" +
                "&append_to_response=external_ids"

        val response =
            app.get(
                url
            )

        if (
            response.code !in
            200..299
        ) {
            return null
        }

        val json =
            JSONObject(
                response.text
            )

        val title =
            json.optString(
                "title"
            )

        val originalTitle =
            json.optString(
                "original_title"
            )
                .takeIf {
                    it.isNotBlank()
                }

        val year =
            extractYear(
                json.optString(
                    "release_date"
                )
            )

        val poster =
            json.optString(
                "poster_path"
            )
                .takeIf {
                    it.isNotBlank() &&
                    it != "null"
                }
                ?.let {
                    IMAGE_BASE + it
                }

        val backdrop =
            json.optString(
                "backdrop_path"
            )
                .takeIf {
                    it.isNotBlank() &&
                    it != "null"
                }
                ?.let {
                    BACKDROP_BASE + it
                }

        val plot =
            json.optString(
                "overview"
            )

        val imdbId =
            json
                .optJSONObject(
                    "external_ids"
                )
                ?.optString(
                    "imdb_id"
                )
                ?.takeIf {
                    it.isNotBlank()
                }

        val media =
            UniversalMedia(
                title = title,
                originalTitle =
                    originalTitle,
                year = year,
                tmdbId = tmdbId,
                imdbId = imdbId,
                isMovie = true
            )

        val playData =
            encodeMedia(
                media
            )

        return newMovieLoadResponse(
            title,
            buildLoadData(
                tmdbId,
                true
            ),
            TvType.Movie,
            playData
        ) {

            this.posterUrl =
                poster

            this.backgroundPosterUrl =
                backdrop

            this.plot =
                plot

            this.year =
                year
        }
    }

    private suspend fun loadTv(
        tmdbId: Int
    ): LoadResponse? {

        val url =
            "$mainUrl/tv/$tmdbId" +
                "?api_key=$TMDB_API_KEY" +
                "&language=it-IT" +
                "&append_to_response=external_ids"

        val response =
            app.get(
                url
            )

        if (
            response.code !in
            200..299
        ) {
            return null
        }

        val json =
            JSONObject(
                response.text
            )

        val title =
            json.optString(
                "name"
            )

        val originalTitle =
            json.optString(
                "original_name"
            )
                .takeIf {
                    it.isNotBlank()
                }

        val year =
            extractYear(
                json.optString(
                    "first_air_date"
                )
            )

        val imdbId =
            json
                .optJSONObject(
                    "external_ids"
                )
                ?.optString(
                    "imdb_id"
                )
                ?.takeIf {
                    it.isNotBlank()
                }

        val poster =
            json.optString(
                "poster_path"
            )
                .takeIf {
                    it.isNotBlank() &&
                    it != "null"
                }
                ?.let {
                    IMAGE_BASE + it
                }

        val backdrop =
            json.optString(
                "backdrop_path"
            )
                .takeIf {
                    it.isNotBlank() &&
                    it != "null"
                }
                ?.let {
                    BACKDROP_BASE + it
                }

        val plot =
            json.optString(
                "overview"
            )

        val seasons =
            json.optJSONArray(
                "seasons"
            )

        val tmdbEpisodes =
        mutableListOf<Episode>()
        var absoluteOffset = 0
        
        if (seasons != null) {

            for (
                i in 0 until seasons.length()
            ) {
        
                val season =
                    seasons.optJSONObject(i)
                        ?: continue
        
                val seasonNumber =
                    season.optInt(
                        "season_number",
                        -1
                    )
        
                if (
                    seasonNumber <= 0
                ) {
                    continue
                }
        
                val episodeCount =
                    season.optInt(
                        "episode_count",
                        0
                    )
        
                tmdbEpisodes.addAll(
                    loadSeasonEpisodes(
                        tmdbId =
                            tmdbId,
                
                        title =
                            title,
                
                        originalTitle =
                            originalTitle,
                
                        year =
                            year,
                
                        imdbId =
                            imdbId,
                
                        seasonNumber =
                            seasonNumber,
                
                        absoluteOffset =
                            absoluteOffset
                    )
                )
        
                absoluteOffset +=
                    episodeCount
            }
        }
        val seriesMedia =
            UniversalMedia(
                title = title,
                originalTitle = originalTitle,
                year = year,
                tmdbId = tmdbId,
                imdbId = imdbId,
                isMovie = false
            )
        
        val inventories =
            mutableListOf<ProviderEpisode>()
        
        sources.forEach { source ->
        
            try {
        
                val providerEpisodes =
                    source.getEpisodeInventory(
                        seriesMedia
                    )
        
                Log.d(
                    TAG,
                    "INVENTORY ${source.name} = ${providerEpisodes.size}"
                )
        
                inventories.addAll(
                    providerEpisodes
                )
        
            } catch (e: Exception) {
        
                Log.e(
                    TAG,
                    "Errore inventory ${source.name}: ${e.message}"
                )
            }
        }

        val filteredEpisodes =
    if (inventories.isEmpty()) {

        Log.d(
            TAG,
            "Nessun inventario provider: " +
                "non mostro episodi per ${title}"
        )

        emptyList()

    } else {

        tmdbEpisodes.filter { episode ->

            val media =
                decodeMedia(
                    episode.data
                )

            if (media == null) {

                Log.d(
                    TAG,
                    "Episodio TMDB scartato: " +
                        "impossibile decodificare i dati"
                )

                false

            } else {

                val available =
                                EpisodeMapper.hasMatch(
                                    media,
                                    inventories
                                )
            
                            Log.d(
                                TAG,
                                "UI S${media.season}E${media.episode} " +
                                    "abs=${media.absoluteEpisode} " +
                                    "\"${media.episodeTitle}\" " +
                                    "available=$available"
                            )
            
                            available
                        }
                    }
                }
            
            Log.d(
                TAG,
                "UI EPISODI: " +
                    "${tmdbEpisodes.size} TMDB -> " +
                    "${filteredEpisodes.size} disponibili"
            )

        return newTvSeriesLoadResponse(
            title,
            buildLoadData(
                tmdbId,
                false
            ),
            TvType.TvSeries,
            filteredEpisodes
        ) {

            this.posterUrl =
                poster

            this.backgroundPosterUrl =
                backdrop

            this.plot =
                plot

            this.year =
                year
        }
    }

    private suspend fun loadSeasonEpisodes(
        tmdbId: Int,
        title: String,
        originalTitle: String?,
        year: Int?,
        imdbId: String?,
        seasonNumber: Int,
        absoluteOffset: Int
    ): List<com.lagradost.cloudstream3.Episode> {

        val url =
            "$mainUrl/tv/$tmdbId/season/$seasonNumber" +
                "?api_key=$TMDB_API_KEY" +
                "&language=it-IT"

        val response =
            app.get(
                url
            )

        if (
            response.code !in
            200..299
        ) {

            return emptyList()
        }

        val json =
            JSONObject(
                response.text
            )

        val array =
            json.optJSONArray(
                "episodes"
            )
                ?: return emptyList()

        val result =
            mutableListOf<
                com.lagradost.cloudstream3.Episode
            >()

        for (
            i in 0 until
                array.length()
        ) {

            val episode =
                array.optJSONObject(
                    i
                )
                    ?: continue

            val episodeNumber =
                episode.optInt(
                    "episode_number",
                    0
                )

            if (
                episodeNumber <= 0
            ) {
                continue
            }

            val episodeTitle =
                episode.optString(
                    "name"
                )
                    .takeIf {
                        it.isNotBlank()
                    }

            val still =
                episode.optString(
                    "still_path"
                )
                    .takeIf {
                        it.isNotBlank() &&
                        it != "null"
                    }
                    ?.let {
                        IMAGE_BASE + it
                    }

            val media =
                UniversalMedia(
                    title =
                        title,
            
                    originalTitle =
                        originalTitle,
            
                    year =
                        year,
            
                    tmdbId =
                        tmdbId,
            
                    imdbId =
                        imdbId,
            
                    season =
                        seasonNumber,
            
                    episode =
                        episodeNumber,
            
                    absoluteEpisode =
                        absoluteOffset +
                            episodeNumber,
            
                    episodeTitle =
                        episodeTitle,
            
                    isMovie =
                        false
                )

            result.add(
                newEpisode(
                    encodeMedia(
                        media
                    )
                ) {
            
                    this.name =
                        episodeTitle
                            ?: "Episodio $episodeNumber"
            
                    this.season =
                        seasonNumber
            
                    this.episode =
                        episodeNumber
            
                    this.posterUrl =
                        still
                }
            )
        }

        return result
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val media =
            decodeMedia(
                data
            )
                ?: return false

        Log.d(
            TAG,
            "=============================="
        )

        Log.d(
            TAG,
            "SMART LOAD: ${media.title}"
        )

        Log.d(
            TAG,
            "S${media.season} E${media.episode}"
        )

        /*
         * ==========================
         * FASE 1
         * ==========================
         *
         * Proviamo tutte le sorgenti
         * che NON richiedono CAPTCHA.
         */
        val silentSources =
            sources.filter {
                !it.requiresInteraction
            }

        var silentLinks =
            0

        for (
            source in silentSources
        ) {

            try {

                Log.d(
                    TAG,
                    "Provo sorgente silenziosa: ${source.name}"
                )

                silentLinks +=
                    source.loadLinks(
                        media,
                        subtitleCallback,
                        callback
                    )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Errore ${source.name}: ${e.message}"
                )
            }
        }

        /*
         * Se troviamo almeno un video,
         * NON tocchiamo Uprot.
         */
        if (
            silentLinks > 0
        ) {

            Log.d(
                TAG,
                "Trovati $silentLinks link senza interazione"
            )

            return true
        }

        /*
         * ==========================
         * FASE 2
         * ==========================
         *
         * Soltanto adesso proviamo
         * i provider con CAPTCHA.
         */
        val interactiveSources =
            sources.filter {
                it.requiresInteraction
            }

        for (
            source in interactiveSources
        ) {

            try {

                Log.d(
                    TAG,
                    "Provo sorgente interattiva: ${source.name}"
                )

                val found =
                    source.loadLinks(
                        media,
                        subtitleCallback,
                        callback
                    )

                /*
                 * OnlineSerieTV trovato?
                 *
                 * Non apriamo anche CB01.
                 */
                if (
                    found > 0
                ) {

                    Log.d(
                        TAG,
                        "${source.name}: $found link trovati"
                    )

                    return true
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Errore ${source.name}: ${e.message}"
                )
            }
        }

        Log.d(
            TAG,
            "Nessuna sorgente disponibile"
        )

        return false
    }

    /*
     * ==========================
     * SERIALIZZAZIONE
     * ==========================
     */

    private fun encodeMedia(
        media: UniversalMedia
    ): String {

        return JSONObject()
            .put(
                "title",
                media.title
            )
            .put(
                "originalTitle",
                media.originalTitle
            )
            .put(
                "year",
                media.year
            )
            .put(
                "tmdbId",
                media.tmdbId
            )
            .put(
                "imdbId",
                media.imdbId
            )
            .put(
                "season",
                media.season
            )
            .put(
                "episode",
                media.episode
            )
            .put(
                "absoluteEpisode",
                media.absoluteEpisode
            )
            .put(
                "episodeTitle",
                media.episodeTitle
            )
            .put(
                "isMovie",
                media.isMovie
            )
            .toString()
    }

    private fun decodeMedia(
        data: String
    ): UniversalMedia? {

        return try {

            val json =
                JSONObject(
                    data
                )

            UniversalMedia(
                title =
                    json.optString(
                        "title"
                    ),

                originalTitle =
                    json
                        .optString(
                            "originalTitle"
                        )
                        .takeIf {
                            it.isNotBlank() &&
                            it != "null"
                        },

                year =
                    json
                        .optInt(
                            "year",
                            0
                        )
                        .takeIf {
                            it > 0
                        },

                tmdbId =
                    json
                        .optInt(
                            "tmdbId",
                            0
                        )
                        .takeIf {
                            it > 0
                        },

                imdbId =
                    json
                        .optString(
                            "imdbId"
                        )
                        .takeIf {
                            it.isNotBlank() &&
                            it != "null"
                        },

                season =
                    json
                        .optInt(
                            "season",
                            0
                        )
                        .takeIf {
                            it > 0
                        },

                episode =
                    json
                        .optInt(
                            "episode",
                            0
                        )
                        .takeIf {
                            it > 0
                        },

                absoluteEpisode =
                    json
                        .optInt(
                            "absoluteEpisode",
                            0
                        )
                        .takeIf {
                            it > 0
                        },

                episodeTitle =
                    json
                        .optString(
                            "episodeTitle"
                        )
                        .takeIf {
                            it.isNotBlank() &&
                            it != "null"
                        },

                isMovie =
                    json.optBoolean(
                        "isMovie",
                        false
                    )
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "decodeMedia errore: ${e.message}"
            )

            null
        }
    }

    private fun buildLoadData(
        tmdbId: Int,
        isMovie: Boolean
    ): String {
    
        val type =
            if (isMovie) {
                "movie"
            } else {
                "tv"
            }
    
        return "https://universal.local/$type/$tmdbId"
    }

    private fun parseLoadData(
        data: String
    ): Pair<Int, Boolean>? {
    
        if (
            !data.startsWith(
                "https://universal.local/"
            )
        ) {
            return null
        }
    
        val clean =
            data.substringAfter(
                "https://universal.local/"
            )
    
        val type =
            clean.substringBefore("/")
    
        val id =
            clean
                .substringAfter("/")
                .substringBefore("?")
                .toIntOrNull()
                ?: return null
    
        if (
            type != "movie" &&
            type != "tv"
        ) {
            return null
        }
    
        return Pair(
            id,
            type == "movie"
        )
    }

    private fun extractYear(
        date: String?
    ): Int? {

        if (
            date.isNullOrBlank() ||
            date.length < 4
        ) {

            return null
        }

        return date
            .take(
                4
            )
            .toIntOrNull()
    }
}
