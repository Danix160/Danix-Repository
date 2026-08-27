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

        // Backend IMDb episodes. Leave blank until configured.
        private const val IMDB_EPISODE_API =
            "https://universal-imdb-episodes.onrender.com/imdb/episodes"
        
        private const val IMDB_EPISODE_KEY =
            "84549bd65b0bce0785d71dd6193ef001"
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


    private data class ImdbEpisode(
        val imdbId: String?,
        val season: Int,
        val episode: Int,
        val title: String?
    )

    private suspend fun loadImdbEpisodeList(
        imdbId: String
    ): List<ImdbEpisode> {

        if (imdbId.isBlank() || IMDB_EPISODE_API.isBlank()) {
            return emptyList()
        }

        val url =
            IMDB_EPISODE_API.trimEnd('/') + "/" + imdbId

        Log.d(TAG, "IMDb EPISODES = $url")

        val response =
            try {
                app.get(
                    url,
                    headers = mapOf(
                        "X-Universal-Key" to IMDB_EPISODE_KEY
                    )
                )
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "IMDb episode request error: ${e.message}"
                )
                return emptyList()
            }

        if (response.code !in 200..299) {
            Log.e(TAG, "IMDb episode HTTP ${response.code}")
            return emptyList()
        }

        return try {
            val root = JSONObject(response.text)
            val array = root.optJSONArray("episodes")
                ?: return emptyList()

            val result = mutableListOf<ImdbEpisode>()

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue

                val season = item.optInt("season", 0)
                val episode = item.optInt("episode", 0)

                if (season <= 0 || episode <= 0) {
                    continue
                }

                result.add(
                    ImdbEpisode(
                        imdbId =
                            item.optString("id")
                                .takeIf {
                                    it.isNotBlank() && it != "null"
                                },
                        season = season,
                        episode = episode,
                        title =
                            item.optString("title")
                                .takeIf {
                                    it.isNotBlank() && it != "null"
                                }
                    )
                )
            }

            result
                .distinctBy { it.season to it.episode }
                .sortedWith(
                    compareBy<ImdbEpisode> { it.season }
                        .thenBy { it.episode }
                )

        } catch (e: Exception) {
            Log.e(TAG, "IMDb episode JSON error: ${e.message}")
            emptyList()
        }
    }

    private suspend fun loadTmdbEpisodeMetadata(
        tmdbId: Int,
        seasonNumber: Int
    ): Map<Int, Pair<String?, String?>> {

        val url =
            "$mainUrl/tv/$tmdbId/season/$seasonNumber" +
                "?api_key=$TMDB_API_KEY" +
                "&language=it-IT"

        val response =
            try {
                app.get(url)
            } catch (e: Exception) {
                Log.e(TAG, "TMDB season metadata error: ${e.message}")
                return emptyMap()
            }

        if (response.code !in 200..299) {
            return emptyMap()
        }

        return try {
            val json = JSONObject(response.text)
            val array = json.optJSONArray("episodes")
                ?: return emptyMap()

            val result =
                mutableMapOf<Int, Pair<String?, String?>>()

            for (i in 0 until array.length()) {
                val ep = array.optJSONObject(i) ?: continue

                val episodeNumber =
                    ep.optInt("episode_number", 0)

                if (episodeNumber <= 0) continue

                val episodeTitle =
                    ep.optString("name")
                        .takeIf {
                            it.isNotBlank() && it != "null"
                        }

                val still =
                    ep.optString("still_path")
                        .takeIf {
                            it.isNotBlank() && it != "null"
                        }
                        ?.let {
                            IMAGE_BASE + it
                        }

                result[episodeNumber] =
                    episodeTitle to still
            }

            result

        } catch (e: Exception) {
            Log.e(TAG, "TMDB metadata JSON error: ${e.message}")
            emptyMap()
        }
    }

    private suspend fun buildEpisodesFromImdb(
        imdbEpisodes: List<ImdbEpisode>,
        tmdbId: Int,
        title: String,
        originalTitle: String?,
        year: Int?,
        imdbId: String?,
        poster: String?
    ): List<Episode> {

        val result = mutableListOf<Episode>()

        val metadataCache =
            mutableMapOf<Int, Map<Int, Pair<String?, String?>>>()

        var absolute = 0

        for (imdbEpisode in imdbEpisodes) {
            absolute++

            val seasonMetadata =
                metadataCache[imdbEpisode.season]
                    ?: loadTmdbEpisodeMetadata(
                        tmdbId = tmdbId,
                        seasonNumber = imdbEpisode.season
                    ).also {
                        metadataCache[imdbEpisode.season] = it
                    }

            val tmdbMetadata =
                seasonMetadata[imdbEpisode.episode]

            val episodeTitle =
                imdbEpisode.title
                    ?: tmdbMetadata?.first
                    ?: "Episodio ${imdbEpisode.episode}"

            val still =
                tmdbMetadata?.second ?: poster

            val media =
                UniversalMedia(
                    title = title,
                    originalTitle = originalTitle,
                    year = year,
                    tmdbId = tmdbId,
                    imdbId = imdbId,
                    season = imdbEpisode.season,
                    episode = imdbEpisode.episode,
                    absoluteEpisode = absolute,
                    episodeTitle = episodeTitle,
                    isMovie = false
                )

            result.add(
                newEpisode(
                    encodeMedia(media)
                ) {
                    this.name = episodeTitle
                    this.season = imdbEpisode.season
                    this.episode = imdbEpisode.episode
                    this.posterUrl = still
                }
            )
        }

        return result
    }

    private suspend fun loadTmdbEpisodeCatalog(
        tmdbId: Int,
        title: String,
        originalTitle: String?,
        year: Int?,
        imdbId: String?,
        seasons: org.json.JSONArray?
    ): List<Episode> {

        val result = mutableListOf<Episode>()
        var absoluteOffset = 0

        if (seasons == null) {
            return result
        }

        for (i in 0 until seasons.length()) {
            val season =
                seasons.optJSONObject(i)
                    ?: continue

            val seasonNumber =
                season.optInt("season_number", -1)

            if (seasonNumber <= 0) {
                continue
            }

            val episodeCount =
                season.optInt("episode_count", 0)

            result.addAll(
                loadSeasonEpisodes(
                    tmdbId = tmdbId,
                    title = title,
                    originalTitle = originalTitle,
                    year = year,
                    imdbId = imdbId,
                    seasonNumber = seasonNumber,
                    absoluteOffset = absoluteOffset
                )
            )

            absoluteOffset += episodeCount
        }

        return result
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

        /*
         * IMDb decide la struttura stagione/episodio.
         * TMDB resta il fallback e la fonte grafica/metadati.
         */
        val imdbEpisodes =
            if (!imdbId.isNullOrBlank()) {
                loadImdbEpisodeList(imdbId)
            } else {
                emptyList()
            }

        Log.d(
            TAG,
            "IMDb EPISODES trovati = ${imdbEpisodes.size}"
        )

        val catalogEpisodes: List<Episode> =
            if (imdbEpisodes.isNotEmpty()) {

                Log.d(
                    TAG,
                    "Uso IMDb come struttura episodi"
                )

                buildEpisodesFromImdb(
                    imdbEpisodes = imdbEpisodes,
                    tmdbId = tmdbId,
                    title = title,
                    originalTitle = originalTitle,
                    year = year,
                    imdbId = imdbId,
                    poster = poster
                )

            } else {

                Log.d(
                    TAG,
                    "IMDb non disponibile: fallback TMDB"
                )

                loadTmdbEpisodeCatalog(
                    tmdbId = tmdbId,
                    title = title,
                    originalTitle = originalTitle,
                    year = year,
                    imdbId = imdbId,
                    seasons = seasons
                )
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

        /*
         * ============================================================
         * STRUTTURA STAGIONI DI RIFERIMENTO
         * ============================================================
         *
         * Se Altadefinizione01 possiede la serie,
         * preferiamo la sua suddivisione reale in stagioni.
         *
         * TMDB continua a fornire titoli, immagini, trama ecc.,
         * ma non decide quanti episodi mostrare.
         */
        
        val referenceInventory =
    inventories
        .groupBy {
            Triple(
                it.season,
                it.episode,
                it.part
            )
        }
        .mapNotNull { (_, versions) ->

            val preferred =
                versions.firstOrNull {
                    it.source.equals(
                        "Altadefinizione01",
                        ignoreCase = true
                    )
                }
                    ?: versions.firstOrNull {
                        it.source.equals(
                            "OnlineSerieTV",
                            ignoreCase = true
                        )
                    }
                    ?: versions.firstOrNull()
                    ?: return@mapNotNull null

            val mergedUrls =
                versions
                    .flatMap {
                        it.urls
                    }
                    .filter {
                        it.isNotBlank()
                    }
                    .distinct()

            preferred.copy(
                urls = mergedUrls,
                title =
                    preferred.title
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: versions
                            .mapNotNull {
                                it.title
                            }
                            .firstOrNull {
                                it.isNotBlank()
                            }
            )
        }
        .sortedWith(
            compareBy<ProviderEpisode> {
                it.season ?: 0
            }
                .thenBy {
                    it.episode ?: 0
                }
                .thenBy {
                    it.part ?: 0
                }
        )

                    val filteredCatalogEpisodes =
                if (inventories.isEmpty()) {
            
                    /*
                     * Se per qualche motivo gli inventari
                     * non vengono caricati, manteniamo TMDB
                     * come fallback.
                     */
                    catalogEpisodes
            
                } else {
            
                    catalogEpisodes.filter { episode ->
            
                        val media =
                            decodeMedia(
                                episode.data
                            )
            
                        if (media == null) {
            
                            false
            
                        } else {
            
                            val available =
                                EpisodeMapper.hasMatch(
                                    media,
                                    referenceInventory
                                )
                            
                            Log.d(
                                TAG,
                                "TMDB UI " +
                                    "S${media.season}E${media.episode} " +
                                    "ABS=${media.absoluteEpisode} " +
                                    "available=$available"
                            )
            
                            available
                        }
                    }
                }
            
            
            /*
             * ============================================================
             * EPISODI PRESENTI NEI PROVIDER MA ASSENTI SU TMDB
             * ============================================================
             */
            
            val tmdbKeys =
                catalogEpisodes
                    .mapNotNull { episode ->
            
                        val media =
                            decodeMedia(
                                episode.data
                            )
                            ?: return@mapNotNull null
            
                        val season =
                            media.season
                                ?: return@mapNotNull null
            
                        val episodeNumber =
                            media.episode
                                ?: return@mapNotNull null
            
                        season to
                            episodeNumber
                    }
                    .toSet()
            
            
            /*
 * ============================================================
 * EPISODI EXTRA DEI PROVIDER
 * ============================================================
 *
 * Alcune serie animate vengono divise dai siti italiani
 * in singoli segmenti:
 *
 * 1x01
 * 1x01.1
 * 1x01.2
 *
 * IMDb può considerarli un solo episodio televisivo,
 * mentre AD01/OSTV li espongono come episodi separati.
 *
 * Se nell'inventario sono presenti "part",
 * usiamo quindi absoluteEpisode per creare una
 * numerazione progressiva per stagione.
 */

val hasEpisodeParts =
    referenceInventory.any {
        it.part != null
    }

val providerOnlyEpisodes =
    if (hasEpisodeParts) {

        Log.d(
            TAG,
            "Rilevata numerazione a segmenti/parti"
        )

        /*
         * Raggruppiamo prima per stagione.
         */
        referenceInventory
            .groupBy {
                it.season
            }
            .flatMap { (seasonNumber, seasonEpisodes) ->

                if (seasonNumber == null) {
                    return@flatMap emptyList()
                }

                /*
                 * Manteniamo ogni combinazione
                 * episodio + parte separata.
                 */
                val uniqueEpisodes =
                    seasonEpisodes
                        .groupBy {
                            it.episode to
                                it.part
                        }
                        .mapNotNull { (_, versions) ->

                            versions.firstOrNull()
                        }
                        .sortedWith(
                            compareBy<ProviderEpisode> {
                                it.episode ?: 0
                            }
                                .thenBy {
                                    it.part ?: 0
                                }
                        )

                /*
                 * Ogni segmento diventa un vero
                 * episodio progressivo Cloudstream:
                 *
                 * 1x01   → E1
                 * 1x01.1 → E2
                 * 1x01.2 → E3
                 * ...
                 */
                uniqueEpisodes
                    .mapIndexed { index, providerEpisode ->

                        val newEpisodeNumber =
                            index + 1

                        val providerMedia =
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
                                    newEpisodeNumber,

                                absoluteEpisode =
                                    providerEpisode.absoluteEpisode,

                                episodeTitle =
                                    providerEpisode.title,

                                isMovie =
                                    false
                            )

                        newEpisode(
                            encodeMedia(
                                providerMedia
                            )
                        ) {

                            this.name =
                                providerEpisode.title
                                    ?.takeIf {
                                        it.isNotBlank()
                                    }
                                    ?: "Episodio $newEpisodeNumber"

                            this.season =
                                seasonNumber

                            this.episode =
                                newEpisodeNumber

                            this.posterUrl =
                                poster
                        }
                    }
            }

    } else {

        /*
         * Serie normali:
         * manteniamo il comportamento precedente.
         */

        referenceInventory
            .groupBy {
                it.season to
                    it.episode
            }
            .mapNotNull { (_, providerVersions) ->

                val providerEpisode =
                    providerVersions
                        .firstOrNull()
                        ?: return@mapNotNull null

                val season =
                    providerEpisode.season
                        ?: return@mapNotNull null

                val episodeNumber =
                    providerEpisode.episode
                        ?: return@mapNotNull null

                if (
                    tmdbKeys.contains(
                        season to
                            episodeNumber
                    )
                ) {

                    return@mapNotNull null
                }

                val providerMedia =
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
                            season,

                        episode =
                            episodeNumber,

                        absoluteEpisode =
                            providerEpisode.absoluteEpisode,

                        episodeTitle =
                            providerEpisode.title,

                        isMovie =
                            false
                    )

                newEpisode(
                    encodeMedia(
                        providerMedia
                    )
                ) {

                    this.name =
                        providerEpisode.title
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?: "Episodio $episodeNumber"

                    this.season =
                        season

                    this.episode =
                        episodeNumber

                    this.posterUrl =
                        poster
                }
            }
    }
    val filteredEpisodes =
    (
        if (hasEpisodeParts) {

            /*
             * Se i provider espongono segmenti/parti,
             * la loro struttura episodica ha precedenza
             * su IMDb.
             */
            providerOnlyEpisodes

        } else {

            /*
             * Serie normali:
             * IMDb come base + eventuali episodi extra
             * trovati sui provider.
             */
            filteredCatalogEpisodes +
                providerOnlyEpisodes
        }
    )
        .distinctBy {
            (it.season ?: 0) to
                (it.episode ?: 0)
        }
        .sortedWith(
            compareBy<Episode> {
                it.season ?: 0
            }
                .thenBy {
                    it.episode ?: 0
                }
        )

Log.d(
    TAG,
    "UI EPISODI: " +
        "${catalogEpisodes.size} catalogo, " +
        "${inventories.size} provider, " +
        "${providerOnlyEpisodes.size} provider finali, " +
        "${filteredEpisodes.size} finali, " +
        "parts=$hasEpisodeParts"
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
