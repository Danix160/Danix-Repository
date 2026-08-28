package com.rivestream

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
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
    val popular: Boolean? = null,

    @JsonProperty("sources")
    val sources: Array<RiveSportSource>? = null
)

data class RiveSportStream(
    @JsonProperty("id")
    val id: String? = null,

    @JsonProperty("streamNo")
    val streamNo: Int? = null,

    @JsonProperty("language")
    val language: String? = null,

    @JsonProperty("hd")
    val hd: Boolean? = null,

    @JsonProperty("embedUrl")
    val embedUrl: String? = null,

    @JsonProperty("source")
    val source: String? = null
)

data class RiveSportSource(
    @JsonProperty("source")
    val source: String? = null,

    @JsonProperty("id")
    val id: String? = null
)

data class RiveTvChannel(
    @JsonProperty("id")
    val id: String? = null,

    @JsonProperty("name")
    val name: String? = null,

    @JsonProperty("country")
    val country: String? = null,

    @JsonProperty("categories")
    val categories: Array<String>? = null,

    @JsonProperty("logo")
    val logo: String? = null,

    @JsonProperty("streamUrl")
    val streamUrl: String? = null,

    @JsonProperty("website")
    val website: String? = null
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
    }
    

    override val mainPage = mainPageOf(
    "italian-tv" to "TV Italiana",
    "sports-live" to "Eventi sportivi",
    "football" to "Calcio",
    "basketball" to "Basket"
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
                loadItalianTv(request.name)


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

        val raw = response.parsedSafe<Array<RiveSportEvent>>()
    
        raw?.toList() ?: emptyList()
    
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
                        category = category,
                        sources = event.sources
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
        
            Log.d(TAG, "TV: loading IPTV page")
        
            val page = try {
                app.get("$mainUrl/iptv")
            } catch (e: Exception) {
                Log.e(TAG, "TV PAGE ERROR = ${e.message}")
                return newHomePageResponse(
                    sectionName,
                    emptyList()
                )
            }
        
            val scriptSrc = page.document
                .select("script[src]")
                .mapNotNull {
                    it.attr("src")
                        .takeIf { src ->
                            src.contains("/pages/iptv-") &&
                            src.contains(".js")
                        }
                }
                .firstOrNull()
        
            if (scriptSrc == null) {
                Log.e(TAG, "TV: IPTV bundle not found")
                return newHomePageResponse(
                    sectionName,
                    emptyList()
                )
            }
        
            val bundleUrl = when {
                scriptSrc.startsWith("http") ->
                    scriptSrc
        
                scriptSrc.startsWith("/") ->
                    "$mainUrl$scriptSrc"
        
                else ->
                    "$mainUrl/$scriptSrc"
            }
        
            Log.d(
                TAG,
                "TV BUNDLE = $bundleUrl"
            )
        
            val js = try {
                app.get(bundleUrl).text
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "TV BUNDLE ERROR = ${e.message}"
                )
        
                return newHomePageResponse(
                    sectionName,
                    emptyList()
                )
            }
        
            Log.d(
                TAG,
                "TV BUNDLE SIZE = ${js.length}"
            )
        
            val channels =
                extractItalianChannels(js)
        
            Log.d(
                TAG,
                "TV ITALIAN CHANNELS = ${channels.size}"
            )
        
            val cards =
                channels
                    .mapNotNull { channel ->
        
                        val id =
                            channel.id
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                                ?: return@mapNotNull null
        
                        val name =
                            channel.name
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                                ?: return@mapNotNull null
        
                        val streamUrl =
                            channel.streamUrl
                                ?.takeIf {
                                    it.startsWith("http")
                                }
                                ?: return@mapNotNull null
        
                        Log.d(
                            TAG,
                            "TV CHANNEL = " +
                                "$name | " +
                                "id=$id | " +
                                "url=$streamUrl"
                        )
        
                        newLiveSearchResponse(
                            name,
                            buildTvData(
                                id = id,
                                title = name,
                                streamUrl = streamUrl,
                                category =
                                    channel.categories
                                        ?.joinToString(", ")
                                        .orEmpty()
                            ),
                            TvType.Live
                        ) {
                            posterUrl =
                                channel.logo
                                    ?.takeIf {
                                        it.startsWith("http")
                                    }
                        }
                    }
                    .distinctBy {
                        it.url
                    }
        
            Log.d(
                TAG,
                "TV CARDS = ${cards.size}"
            )
        
            return newHomePageResponse(
                sectionName,
                cards
            )
        }

    private fun extractItalianChannels(
    js: String
): List<RiveTvChannel> {

    val results =
        mutableListOf<RiveTvChannel>()

    var position = 0

    while (true) {

        val countryIndex =
            js.indexOf(
                "\"country\":\"IT\"",
                position
            )

        if (countryIndex == -1) {
            break
        }

        val start =
            js.lastIndexOf(
                "{\"id\":",
                countryIndex
            )

        if (start == -1) {
            position =
                countryIndex + 1

            continue
        }

        var end =
            js.indexOf(
                "},{\"id\":",
                countryIndex
            )

        if (end == -1) {
            end =
                js.indexOf(
                    "}]",
                    countryIndex
                )
        }

        if (end == -1) {
            position =
                countryIndex + 1

            continue
        }

        val objectText =
            js.substring(
                start,
                end + 1
            )

        try {

            val channel =
                AppUtils.parseJson<
                    RiveTvChannel
                >(objectText)

            if (
                channel.country
                    ?.equals(
                        "IT",
                        ignoreCase = true
                    ) == true
            ) {

                results.add(channel)
            }

        } catch (e: Exception) {

            Log.d(
                TAG,
                "TV JSON SKIP = ${e.message}"
            )
        }

        position =
            end + 1
    }

    return results
        .distinctBy {
            it.id
        }
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

            response
                .parsedSafe<Array<RiveSportEvent>>()
                ?.toList()
                ?: emptyList()
        
        } catch (e: Exception) {
        
            Log.e(
                TAG,
                "SEARCH JSON ERROR = ${e.message}"
            )
        
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
                        category = event.category.orEmpty(),
                        sources = event.sources
                    ),
                    TvType.Live
                ) {
                    posterUrl =
                        event.poster?.let {
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
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean {
    
        val parsed = parseData(data)
            ?: return false
    
        val type = parsed["type"]
            ?: return false
    
        // ============================================================
        // TV
        // ============================================================
    
        if (type == "tv") {
    
            val title =
                parsed["title"]
                    ?.takeIf { it.isNotBlank() }
                    ?: "TV Italiana"
    
            val streamUrl =
                parsed["stream"]
                    ?.takeIf { it.startsWith("http") }
                    ?: return false
    
            Log.d(
                TAG,
                "TV LOADLINKS title=$title url=$streamUrl"
            )
    
            val linkType =
                when {
                    streamUrl
                        .substringBefore("?")
                        .endsWith(
                            ".mpd",
                            ignoreCase = true
                        ) ->
                        com.lagradost.cloudstream3.utils.ExtractorLinkType.DASH
    
                    else ->
                        com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
                }
    
            callback(
                com.lagradost.cloudstream3.utils.newExtractorLink(
                    source = name,
                    name = title,
                    url = streamUrl,
                    type = linkType
                ) {
                    referer = mainUrl
                }
            )
    
            return true
        }
    
        // ============================================================
        // SPORT
        // ============================================================
    
        if (type != "sport") {
            return false
        }
    
        val eventId =
            parsed["id"]
                ?: return false
    
        val rawSources =
            parsed["sources"]
                .orEmpty()
    
        Log.d(
            TAG,
            "LOADLINKS event=$eventId sources=$rawSources"
        )
    
        if (rawSources.isBlank()) {
    
            Log.d(
                TAG,
                "LOADLINKS nessuna source disponibile"
            )
    
            return false
        }
    
        var foundSomething = false
    
        val sources =
            rawSources
                .split(";")
                .mapNotNull { raw ->
    
                    val parts =
                        raw.split(
                            "|",
                            limit = 2
                        )
    
                    if (parts.size != 2)
                        return@mapNotNull null
    
                    RiveSportSource(
                        source = parts[0],
                        id = parts[1]
                    )
                }
    
        Log.d(
            TAG,
            "LOADLINKS parsed sources=${sources.size}"
        )
    
        for (source in sources) {
    
            val sourceName =
                source.source
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: continue
    
            val sourceId =
                source.id
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: continue
    
            val apiUrl =
                "$SPORTS_API/stream/" +
                    "${encodePath(sourceName)}/" +
                    encodePath(sourceId)
    
            Log.d(
                TAG,
                "STREAM API = $apiUrl"
            )
    
            val response =
                try {
    
                    app.get(
                        apiUrl,
                        headers = mapOf(
                            "Accept" to "application/json"
                        )
                    )
    
                } catch (e: Exception) {
    
                    Log.e(
                        TAG,
                        "STREAM REQUEST ERROR " +
                            "source=$sourceName " +
                            "message=${e.message}"
                    )
    
                    continue
                }
    
            Log.d(
                TAG,
                "STREAM STATUS source=$sourceName = ${response.code}"
            )
    
            val streams =
                try {
    
                    response
                        .parsedSafe<Array<RiveSportStream>>()
                        ?.toList()
                        ?: emptyList()
    
                } catch (e: Exception) {
    
                    Log.e(
                        TAG,
                        "STREAM JSON ERROR " +
                            "source=$sourceName " +
                            "message=${e.message}"
                    )
    
                    emptyList()
                }
    
            Log.d(
                TAG,
                "STREAMS source=$sourceName count=${streams.size}"
            )
    
            streams.forEach { stream ->
    
                val embedUrl =
                    stream.embedUrl
                        ?.takeIf {
                            it.startsWith("http")
                        }
                        ?: return@forEach
    
                Log.d(
                    TAG,
                    "STREAM FOUND " +
                        "source=$sourceName " +
                        "no=${stream.streamNo} " +
                        "lang=${stream.language} " +
                        "hd=${stream.hd} " +
                        "url=$embedUrl"
                )
    
                foundSomething = true
            }
        }
    
        return foundSomething
    }

    // ============================================================
    // DATA
    // ============================================================

    private fun buildData(
        type: String,
        id: String,
        title: String,
        category: String,
        sources: Array<RiveSportSource>? = null
    ): String {
    
        val encodedSources = sources
            ?.mapNotNull { source ->
    
                val sourceName =
                    source.source
                        ?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
    
                val sourceId =
                    source.id
                        ?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
    
                "${encode(sourceName)}|${encode(sourceId)}"
            }
            ?.joinToString(";")
            .orEmpty()
    
        return "https://rivestream.local/item?" +
            "type=${encode(type)}" +
            "&id=${encode(id)}" +
            "&title=${encode(title)}" +
            "&category=${encode(category)}" +
            "&sources=$encodedSources"
    }

    private fun buildTvData(
    id: String,
    title: String,
    streamUrl: String,
    category: String
): String {

    return "https://rivestream.local/item?" +
        "type=${encode("tv")}" +
        "&id=${encode(id)}" +
        "&title=${encode(title)}" +
        "&category=${encode(category)}" +
        "&stream=${encode(streamUrl)}"
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
    private fun encodePath(value: String): String =
    URLEncoder
        .encode(value, "UTF-8")
        .replace("+", "%20")

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
