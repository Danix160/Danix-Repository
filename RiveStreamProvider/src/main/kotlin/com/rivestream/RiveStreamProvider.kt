package com.rivestream

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import java.net.URLDecoder
import java.net.URLEncoder

// ============================================================
// MODELLI SPORT
// ============================================================

data class RiveSportEvent(
    @JsonProperty("id")
    val id: String? = null,

    @JsonProperty("title")
    val title: String? = null,

    @JsonProperty("category")
    val category: String? = null,

    @JsonProperty("date")
    val date: Long? = null,

    @JsonProperty("poster")
    val poster: String? = null,

    @JsonProperty("popular")
    val popular: Boolean? = null
)

// ============================================================
// PROVIDER
// ============================================================

class RiveStreamProvider : MainAPI() {

    override var name = "RiveStream"
    override var mainUrl = "https://rivestream.ru"
    override var lang = "it"

    override val supportedTypes = setOf(
        TvType.Live
    )

    override val hasMainPage = true
    override val hasQuickSearch = true

    companion object {
        private const val TAG = "RIVESTREAM_DEBUG"

        private const val SPORTS_API =
            "https://streamed.pk/api"

        private const val LIVE_TV_API =
            "https://api.cdn-live.tv/api/v1"
    }

    override val mainPage = mainPageOf(

        "italian-tv" to
            "TV Italiana",

        "sports-live" to
            "Eventi sportivi",

        "football" to
            "Calcio",

        "basketball" to
            "Basket"
    )

    // ============================================================
    // HOME
    // ============================================================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        if (page > 1) {
            return newHomePageResponse(
                request.name,
                emptyList()
            )
        }

        Log.d(
            TAG,
            "GET MAIN PAGE = ${request.data}"
        )

        return when (request.data) {

            "italian-tv" ->
                loadItalianTv(
                    request.name
                )

            "sports-live" ->
                loadSports(
                    sectionName = request.name,
                    query = "live"
                )

            "football" ->
                loadSports(
                    sectionName = request.name,
                    query = "football"
                )

            "basketball" ->
                loadSports(
                    sectionName = request.name,
                    query = "basketball"
                )

            else ->
                newHomePageResponse(
                    request.name,
                    emptyList()
                )
        }
    }

    // ============================================================
    // EVENTI SPORTIVI
    // ============================================================

    private suspend fun loadSports(
        sectionName: String,
        query: String
    ): HomePageResponse {

        val url =
            "$SPORTS_API/matches/$query/popular"

        Log.d(
            TAG,
            "SPORT API = $url"
        )

        val response = try {

            app.get(
                url,
                headers = mapOf(
                    "Accept" to
                        "application/json"
                )
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "SPORT REQUEST ERROR = ${e.message}"
            )

            return newHomePageResponse(
                sectionName,
                emptyList()
            )
        }

        Log.d(
            TAG,
            "SPORT STATUS = ${response.code}"
        )

        Log.d(
            TAG,
            "SPORT BODY = ${
                response.text.take(1000)
            }"
        )

        val events = try {

            response.parsedSafe<
                List<RiveSportEvent>
            >()
                ?: emptyList()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "SPORT JSON ERROR = ${e.message}"
            )

            emptyList()
        }

        Log.d(
            TAG,
            "SPORT EVENTS = ${events.size}"
        )

        val cards =
            events.mapNotNull { event ->

                val title =
                    event.title
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: return@mapNotNull null

                val id =
                    event.id
                        ?: return@mapNotNull null

                val category =
                    event.category
                        ?.trim()
                        .orEmpty()

                val poster =
                    event.poster
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            fixImageUrl(it)
                        }

                Log.d(
                    TAG,
                    "EVENT = " +
                        "$title | " +
                        "id=$id | " +
                        "category=$category"
                )

                newLiveSearchResponse(
                    title,
                    buildData(
                        type = "sport",
                        id = id,
                        title = title,
                        category = category
                    ),
                    TvType.Live
                ) {

                    posterUrl = poster
                }
            }
                .distinctBy {
                    it.url
                }

        Log.d(
            TAG,
            "SPORT CARDS = ${cards.size}"
        )

        return newHomePageResponse(
            sectionName,
            cards
        )
    }

    // ============================================================
    // TV ITALIANA
    // ============================================================

    private suspend fun loadItalianTv(
        sectionName: String
    ): HomePageResponse {

        val url =
            "$LIVE_TV_API/channels/" +
                "?user=cdnlivetv&plan=free"

        Log.d(
            TAG,
            "TV API = $url"
        )

        val response = try {

            app.get(
                url,
                headers = mapOf(
                    "Accept" to
                        "application/json"
                )
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "TV REQUEST ERROR = ${e.message}"
            )

            return newHomePageResponse(
                sectionName,
                emptyList()
            )
        }

        Log.d(
            TAG,
            "TV STATUS = ${response.code}"
        )

        /*
         * Per il primo test non assumiamo
         * ancora la struttura JSON.
         *
         * Stampiamo una parte della risposta
         * così vediamo esattamente come
         * cdn-live.tv restituisce i canali.
         */

        Log.d(
            TAG,
            "TV BODY = ${
                response.text.take(4000)
            }"
        )

        /*
         * Per ora la TV rimane vuota.
         *
         * Appena vediamo TV BODY nel log,
         * modelliamo correttamente il JSON.
         */

        return newHomePageResponse(
            sectionName,
            emptyList()
        )
    }

    // ============================================================
    // SEARCH
    // ============================================================

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        if (query.length < 2) {
            return emptyList()
        }

        val normalized =
            query
                .trim()
                .lowercase()

        val url =
            "$SPORTS_API/matches/all/popular"

        val response = try {

            app.get(
                url,
                headers = mapOf(
                    "Accept" to
                        "application/json"
                )
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "SEARCH ERROR = ${e.message}"
            )

            return emptyList()
        }

        val events = try {

            response.parsedSafe<
                List<RiveSportEvent>
            >()
                ?: emptyList()

        } catch (e: Exception) {

            emptyList()
        }

        return events
            .filter {

                val title =
                    it.title
                        ?.lowercase()
                        .orEmpty()

                val category =
                    it.category
                        ?.lowercase()
                        .orEmpty()

                title.contains(
                    normalized
                ) ||
                    category.contains(
                        normalized
                    )
            }
            .mapNotNull { event ->

                val title =
                    event.title
                        ?: return@mapNotNull null

                val id =
                    event.id
                        ?: return@mapNotNull null

                newLiveSearchResponse(
                    title,
                    buildData(
                        type = "sport",
                        id = id,
                        title = title,
                        category =
                            event.category
                                .orEmpty()
                    ),
                    TvType.Live
                ) {

                    posterUrl =
                        event.poster
                            ?.let {
                                fixImageUrl(it)
                            }
                }
            }
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {

        return search(query)
            .take(30)
    }

    // ============================================================
    // LOAD
    // ============================================================

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val data =
            parseData(url)
                ?: return null

        val type =
            data["type"]
                ?: return null

        val id =
            data["id"]
                ?: return null

        val title =
            data["title"]
                ?: return null

        val category =
            data["category"]
                .orEmpty()

        Log.d(
            TAG,
            "LOAD type=$type id=$id title=$title"
        )

        return newLiveStreamLoadResponse(
            title,
            url,
            url
        ) {

            plot =
                when (type) {

                    "sport" -> {

                        buildString {

                            if (
                                category.isNotBlank()
                            ) {
                                append(
                                    category
                                )
                            }

                            if (
                                isNotEmpty()
                            ) {
                                append("\n")
                            }

                            append(
                                "Evento sportivo"
                            )
                        }
                    }

                    "tv" ->
                        "Canale TV italiano"

                    else ->
                        "Live"
                }

            tags =
                listOfNotNull(
                    category.takeIf {
                        it.isNotBlank()
                    },
                    "Live"
                )
        }
    }

    // ============================================================
    // LINKS
    // ============================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback:
            (SubtitleFile) -> Unit,
        callback:
            (
                com.lagradost.cloudstream3
                    .utils.ExtractorLink
            ) -> Unit
    ): Boolean {

        val parsed =
            parseData(data)
                ?: return false

        Log.d(
            TAG,
            "LOADLINKS type=${parsed["type"]} " +
                "id=${parsed["id"]} " +
                "title=${parsed["title"]}"
        )

        /*
         * Non implementiamo ancora
         * la riproduzione.
         *
         * Prima verifichiamo catalogo/API.
         */

        return false
    }

    // ============================================================
    // DATA
    // ============================================================

    private fun buildData(
        type: String,
        id: String,
        title: String,
        category: String
    ): String {

        return "https://rivestream.local/item?" +
            "type=${encode(type)}" +
            "&id=${encode(id)}" +
            "&title=${encode(title)}" +
            "&category=${encode(category)}"
    }

    private fun parseData(
        url: String
    ): Map<String, String>? {

        if (
            !url.startsWith(
                "https://rivestream.local/item?"
            )
        ) {
            return null
        }

        return url
            .substringAfter("?")
            .split("&")
            .mapNotNull {

                val parts =
                    it.split(
                        "=",
                        limit = 2
                    )

                if (
                    parts.size != 2
                ) {
                    null
                } else {

                    decode(
                        parts[0]
                    ) to
                        decode(
                            parts[1]
                        )
                }
            }
            .toMap()
    }

    private fun encode(
        value: String
    ): String {

        return URLEncoder.encode(
            value,
            "UTF-8"
        )
    }

    private fun decode(
        value: String
    ): String {

        return URLDecoder.decode(
            value,
            "UTF-8"
        )
    }

    private fun fixImageUrl(
        url: String
    ): String {

        return when {

            url.startsWith("//") ->
                "https:$url"

            url.startsWith("/") ->
                "https://streamed.pk$url"

            else ->
                url
        }
    }
}
