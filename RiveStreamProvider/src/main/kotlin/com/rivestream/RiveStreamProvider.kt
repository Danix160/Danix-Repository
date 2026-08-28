package com.rivestream

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
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

data class RivePrivateChannel(
@JsonProperty("id")
val id: String? = null,

@JsonProperty("title")
val title: String? = null

)

data class IptvOrgChannel(
@JsonProperty("id")
val id: String? = null,

@JsonProperty("name")
val name: String? = null,

@JsonProperty("alt_names")
val altNames: Array<String>? = null,

@JsonProperty("country")
val country: String? = null

)

data class IptvOrgLogo(
@JsonProperty("channel")
val channel: String? = null,

@JsonProperty("feed")
val feed: String? = null,

@JsonProperty("in_use")
val inUse: Boolean? = null,

@JsonProperty("tags")
val tags: Array<String>? = null,

@JsonProperty("width")
val width: Int? = null,

@JsonProperty("height")
val height: Int? = null,

@JsonProperty("url")
val url: String? = null

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

private var italianLogoCache: Map<String, String> = emptyMap()
private var italianLogosLoaded = false


override val mainPage = mainPageOf(
"football" to "Calcio",
"italian-private-tv" to "TV Italiana Privata",
"sports-live" to "Eventi Live",
"italian-tv" to "TV Italiana",
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

        "italian-private-tv" ->
            loadItalianPrivateTv(request.name)

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

private fun horizontalHomePage(
sectionName: String,
cards: List<SearchResponse>

): HomePageResponse {
return newHomePageResponse(
HomePageList(
name = sectionName,
list = cards,
isHorizontalImages = true
),
hasNext = false
)
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

    return horizontalHomePage(
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
    
        return horizontalHomePage(
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

private suspend fun loadItalianPrivateTv(
sectionName: String

): HomePageResponse {

Log.d(TAG, "PRIVATE TV: loading IPTV page")

val page = try {
    app.get("$mainUrl/iptv")
} catch (e: Exception) {
    Log.e(TAG, "PRIVATE TV PAGE ERROR = ${e.message}")

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
    Log.e(TAG, "PRIVATE TV: IPTV bundle not found")

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

val js = try {
    app.get(bundleUrl).text
} catch (e: Exception) {
    Log.e(
        TAG,
        "PRIVATE TV BUNDLE ERROR = ${e.message}"
    )

    return newHomePageResponse(
        sectionName,
        emptyList()
    )
}

val channels =
    extractItalianPrivateChannels(js)

val logos =
    loadItalianLogoMap()

Log.d(
    TAG,
    "PRIVATE ITALIAN CHANNELS = ${channels.size}"
)

val cards =
    channels
        .mapNotNull { channel ->

            val id =
                channel.id
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

            val title =
                channel.title
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                    val poster =
                        findItalianChannelLogo(
                            title,
                            logos
                        )
                    
                    Log.d(
                        TAG,
                        "PRIVATE LOGO = $title -> $poster"
                    )
                    
                    newLiveSearchResponse(
                        title,
                        buildPrivateTvData(
                            id = id,
                            title = title
                        ),
                        TvType.Live
                    ) {
                        posterUrl = poster
                    }
        }
        .distinctBy { it.url }

Log.d(
    TAG,
    "PRIVATE TV CARDS = ${cards.size}"
)

return horizontalHomePage(
sectionName,
cards

)
}

private fun extractItalianPrivateChannels(
js: String

): List<RivePrivateChannel> {

val results =
    mutableListOf<RivePrivateChannel>()

val regex = Regex(
    """\{"id":"([^"]+)","title":"([^"]+)"\}"""
)

regex.findAll(js).forEach { match ->

    val id =
        match.groupValues[1]

    val title =
        match.groupValues[2]

    if (
        title.contains(
            "Italy",
            ignoreCase = true
        )
    ) {

        results.add(
            RivePrivateChannel(
                id = id,
                title = title
            )
        )
    }
}

Log.d(
    TAG,
    "PRIVATE TV REGEX RESULTS = ${results.size}"
)

return results
    .distinctBy { it.id }

}

private suspend fun loadItalianLogoMap(): Map<String, String> {

if (italianLogosLoaded && italianLogoCache.isNotEmpty()) {
    return italianLogoCache
}

try {

    Log.d(TAG, "LOGOS: caricamento IPTV-org")

    // ============================================================
    // CHANNELS
    // ============================================================

    val channelsResponse = app.get(
        "https://iptv-org.github.io/api/channels.json",
        headers = mapOf(
            "Accept" to "application/json"
        )
    )

    Log.d(
        TAG,
        "LOGOS CHANNELS HTTP = ${channelsResponse.code}"
    )

    val channelsJson = channelsResponse.textLarge

     Log.d(
     TAG,
    "LOGOS CHANNELS SIZE = ${channelsJson.length}"

)

    // ============================================================
    // LOGOS
    // ============================================================

    val logosResponse = app.get(
        "https://iptv-org.github.io/api/logos.json",
        headers = mapOf(
            "Accept" to "application/json"
        )
    )

    Log.d(
        TAG,
        "LOGOS LOGOS HTTP = ${logosResponse.code}"
    )

    val logosJson = logosResponse.textLarge

    Log.d(
    TAG,
    "LOGOS LOGOS SIZE = ${logosJson.length}"

)

    // ============================================================
    // PARSING
    // ============================================================

    val channels = try {

        AppUtils.parseJson<Array<IptvOrgChannel>>(
        channelsJson
        ).toList()

    } catch (e: Exception) {

        Log.e(
            TAG,
            "LOGOS CHANNELS PARSE ERROR = ${e.message}",
            e
        )

        emptyList()
    }

    val logos = try {

        AppUtils.parseJson<Array<IptvOrgLogo>>(
        logosJson
        ).toList()

    } catch (e: Exception) {

        Log.e(
            TAG,
            "LOGOS LOGOS PARSE ERROR = ${e.message}",
            e
        )

        emptyList()
    }

    logos
.filter { logo ->
    logo.channel
        ?.contains(
            "euro",
            ignoreCase = true
        ) == true
}
.forEach { logo ->

    Log.d(
        TAG,
        "EUROSPORT LOGO DEBUG: " +
            "channel=${logo.channel} | " +
            "url=${logo.url} | " +
            "inUse=${logo.inUse} | " +
            "size=${logo.width}x${logo.height}"
    )
}

    Log.d(
        TAG,
        "LOGOS: channels=${channels.size} logos=${logos.size}"
    )

    if (channels.isEmpty() || logos.isEmpty()) {

        Log.e(
            TAG,
            "LOGOS: API vuota, cache non marcata come caricata"
        )

        return italianLogoCache
    }

    // ============================================================
    // SOLO CANALI ITALIANI
    // ============================================================

    val italianChannels =
channels.filter { channel ->

    val isItalian =
        channel.country.equals(
            "IT",
            ignoreCase = true
        )

    val isEurosport =
        channel.name
            ?.contains(
                "Eurosport",
                ignoreCase = true
            ) == true ||
        channel.altNames
            ?.any {
                it.contains(
                    "Eurosport",
                    ignoreCase = true
                )
            } == true

    isItalian || isEurosport
}

    Log.d(
        TAG,
        "LOGOS: Italian channels=${italianChannels.size}"
    )

    italianChannels
.filter { channel ->
    channel.name
        ?.contains(
            "euro",
            ignoreCase = true
        ) == true ||
    channel.altNames
        ?.any {
            it.contains(
                "euro",
                ignoreCase = true
            )
        } == true
}
.forEach { channel ->

    Log.d(
        TAG,
        "EUROSPORT DEBUG: " +
            "id=${channel.id} | " +
            "name=${channel.name} | " +
            "alt=${channel.altNames?.joinToString()}"
    )
}

    // ============================================================
    // LOGO PER CHANNEL ID
    // ============================================================

    val logosByChannel =
logos
    .filter {
        !it.channel.isNullOrBlank() &&
        !it.url.isNullOrBlank()
    }
    .groupBy {
        it.channel!!
    }
    .mapValues { (_, channelLogos) ->

        val selectedLogo =
            channelLogos
                .filter { logo ->
                    !logo.url.isNullOrBlank()
                }
                .sortedWith(
                    compareByDescending<IptvOrgLogo> { logo ->

                        val url =
                            logo.url.orEmpty().lowercase()

                        when {
                            "i.imgur.com" in url -> 5

                            "wikimedia.org" !in url &&
                            "wikipedia.org" !in url -> 4

                            url.endsWith(".svg") -> 3

                            "upload.wikimedia.org" in url -> 2

                            else -> 1
                        }

                    }.thenByDescending { logo ->

                        logo.inUse == true

                    }.thenByDescending { logo ->

                        logo.feed == null

                    }.thenByDescending { logo ->

                        when {
                            logo.tags?.any {
                                it.equals(
                                    "horizontal",
                                    ignoreCase = true
                                )
                            } == true -> 3

                            (logo.width ?: 0) >
                                (logo.height ?: 0) -> 2

                            else -> 1
                        }

                    }.thenByDescending { logo ->

                        val width =
                            logo.width ?: 0

                        val height =
                            logo.height ?: 0

                        if (width > 0 && height > 0) {
                            width.toDouble() /
                                height.toDouble()
                        } else {
                            0.0
                        }

                    }.thenByDescending { logo ->

                        val url =
                            logo.url.orEmpty().lowercase()

                        when {
                            url.endsWith(".png") -> 4
                            url.endsWith(".webp") -> 3

                            url.endsWith(".jpg") ||
                                url.endsWith(".jpeg") -> 2

                            url.endsWith(".svg") -> 1

                            else -> 0
                        }
                    }
                )
                .firstOrNull()

        selectedLogo?.url
    }

// ============================================================
// DEBUG SKY CINEMA
// ============================================================

italianChannels
.filter { channel ->

    channel.name
        ?.contains(
            "Sky Cinema",
            ignoreCase = true
        ) == true
}
.forEach { channel ->

    val channelLogos =
        logos.filter {
            it.channel == channel.id
        }

    Log.d(
        TAG,
        "SKY CINEMA DEBUG: " +
            "id=${channel.id} | " +
            "name=${channel.name} | " +
            "logos=${channelLogos.size}"
    )

    channelLogos.forEach { logo ->

        Log.d(
            TAG,
            "SKY CINEMA LOGO: " +
                "channel=${channel.id} | " +
                "url=${logo.url} | " +
                "inUse=${logo.inUse} | " +
                "size=${logo.width}x${logo.height}"
        )
    }
}

    // ============================================================
    // NAME -> LOGO
    // ============================================================

    val result =
        mutableMapOf<String, String>()

    italianChannels.forEach { channel ->

        val channelId =
            channel.id
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: return@forEach

        val logo =
            logosByChannel[channelId]
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: return@forEach

        val names =
            mutableListOf<String>()

        channel.name
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                names.add(it)
            }

        channel.altNames
            ?.filter {
                it.isNotBlank()
            }
            ?.let {
                names.addAll(it)
            }

        names.forEach { channelName ->

            val normalized =
                normalizeChannelName(
                    channelName
                )

            if (normalized.isNotBlank()) {

                result.putIfAbsent(
                    normalized,
                    logo
                )
            }
        }
    }

    italianLogoCache = result

    // IMPORTANTE:
    // true solo se abbiamo realmente caricato qualcosa
    italianLogosLoaded =
        result.isNotEmpty()

    Log.d(
        TAG,
        "LOGOS: Italian map=${result.size}"
    )

} catch (e: Exception) {

    Log.e(
        TAG,
        "LOGOS ERROR = ${e.message}",
        e
    )
}

return italianLogoCache

}

private fun normalizeChannelName(
value: String
): String {

return value
    .lowercase()

    // Suffisso usato da RiveStream
    .replace(
        Regex(
            """\s+italy\s*$""",
            RegexOption.IGNORE_CASE
        ),
        ""
    )

    // Qualità
    .replace(
        Regex(
            """\b(?:hd|uhd|4k)\b""",
            RegexOption.IGNORE_CASE
        ),
        ""
    )

    // HD+
    .replace(
        Regex(
            """\bhd\+\b""",
            RegexOption.IGNORE_CASE
        ),
        ""
    )

    .replace("&", " and ")

    // Manteniamo lettere/numeri/spazi
    .replace(
        Regex("""[^a-z0-9]+"""),
        " "
    )

    .trim()

    .replace(
        Regex("""\s+"""),
        " "
    )

}

private fun findItalianChannelLogo(
title: String,
logos: Map<String, String>
): String? {

var normalized = normalizeChannelName(title)

// Correzioni specifiche dei nomi usati
// dalla lista privata di RiveStream
normalized = when {

    normalized == "20 mediaset" ->
        "20"

    normalized == "sky cinema uno 24" ->
        "sky cinema uno"

    normalized == "sky cinema due 24" ->
        "sky cinema due"

    normalized.startsWith("8sky cinema ") ->
        normalized.removePrefix("8")

    Regex("""^sky calcio [1-7] \d+$""")
        .matches(normalized) ->
        "sky sport calcio"

    else ->
        normalized
}

// Alias RiveStream -> IPTV-org

normalized = when (normalized) {

"eurosport 1" ->
    listOf(
        "eurosport 1",
        "eurosport1",
        "eurosport"
    ).firstOrNull { logos.containsKey(it) }
        ?: normalized

"eurosport 2" ->
    listOf(
        "eurosport 2",
        "eurosport2",
        "eurosport"
    ).firstOrNull { logos.containsKey(it) }
        ?: normalized

else ->
    normalized

}

// Match esatto
logos[normalized]?.let {
    return it
}

/*
 * Eurosport:
 * proviamo anche varianti presenti
 * nella banca dati.
 */
if (normalized == "eurosport 1") {

    listOf(
        "eurosport 1",
        "eurosport1",
        "eurosport"
    ).forEach { alias ->

        logos[alias]?.let {
            return it
        }
    }
}

if (normalized == "eurosport 2") {

    listOf(
        "eurosport 2",
        "eurosport2",
        "eurosport"
    ).forEach { alias ->

        logos[alias]?.let {
            return it
        }
    }
}

// Fuzzy match controllato
if (normalized.length >= 6) {

    val candidates =
        logos.filterKeys { key ->

            key.length >= 6 &&
                (
                    key == normalized ||
                    key.contains(normalized) ||
                    normalized.contains(key)
                )
        }

    if (candidates.size == 1) {
        return candidates.values.first()
    }
}

Log.d(
    TAG,
    "LOGO NOT FOUND = $title -> $normalized"
)

return null

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
                
                "tv-private" ->
                    "Canale TV Private italiano"
                
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
// ============================================================

// TV PRIVATE
// ============================================================

if (type == "tv-private") {
val id = parsed["id"]?.takeIf { it.isNotBlank() } ?: return false
val title = parsed["title"]?.takeIf { it.isNotBlank() } ?: "TV Private"

Log.d(TAG, "PRIVATE LOADLINKS id=$id title=$title")

try {
// 1. Pagina Alpha specifica del canale
val alphaUrl = "https://dlhd.pk/stream/stream-$id.php"

val alphaResponse = app.get(
    alphaUrl,
    referer = "$mainUrl/"
)

Log.d(TAG, "PRIVATE ALPHA HTTP = ${alphaResponse.code}")

// 2. Trova automaticamente daddy.php, daddy2.php,
// daddy5.php ecc.
val alphaHtml = alphaResponse.text
.replace("\\/", "/")

val playerUrl = Regex(
    """https?://[^"'<>]+/premiumtv/daddy\d*\.php\?id=\d+""",
    RegexOption.IGNORE_CASE
).find(alphaHtml)
    ?.value

if (playerUrl.isNullOrBlank()) {
    Log.e(TAG, "PRIVATE: player daddy non trovato per id=$id")
    return false
}

Log.d(TAG, "PRIVATE PLAYER URL = $playerUrl")

// 3. Apri il player corretto
val response = app.get(
    playerUrl,
    referer = alphaUrl
)

Log.d(TAG, "PRIVATE PLAYER HTTP = ${response.code}")

val html = response.text

    val base64 = Regex(
        """(?:window\.)?atob\(\s*['"]([^'"]+)['"]\s*\)"""
    ).find(html)?.groupValues?.getOrNull(1)

    if (base64.isNullOrBlank()) {
        Log.e(TAG, "PRIVATE: Base64 source non trovato")
        return false
    }

    val streamUrl = try {
        String(
            android.util.Base64.decode(
                base64,
                android.util.Base64.DEFAULT
            ),
            Charsets.UTF_8
        )
    } catch (e: Exception) {
        Log.e(TAG, "PRIVATE BASE64 ERROR = ${e.message}")
        return false
    }

    Log.d(TAG, "PRIVATE STREAM = $streamUrl")

    if (!streamUrl.contains(".m3u8")) {
        Log.e(TAG, "PRIVATE: URL non HLS = $streamUrl")
        return false
    }

    val playerOrigin = Regex(
        """^(https?://[^/]+)"""
    ).find(playerUrl)?.groupValues?.getOrNull(1)
        ?: return false

val streamHeaders = mapOf(
"Origin" to playerOrigin,
"Referer" to "$playerOrigin/"
)

var finalStreamUrl = streamUrl

try {
val test = app.get(
streamUrl,
headers = streamHeaders,
referer = "$playerOrigin/"
)

Log.d(TAG, "PRIVATE INDEX HTTP = ${test.code}")

test.text.lines().forEach { line ->
if (
    line.contains("#EXT-X-STREAM-INF") ||
    line.contains("RESOLUTION=")
) {
    Log.d(TAG, "PRIVATE QUALITY = $line")
}

}

if (!test.text.trimStart().startsWith("#EXTM3U")) {

    val monoUrl = streamUrl
        .substringBeforeLast("/") +
        "/tracks-v1a1/mono.m3u8"

    Log.d(TAG, "PRIVATE TRY MONO = $monoUrl")

    val monoTest = app.get(
        monoUrl,
        headers = streamHeaders,
        referer = "$playerOrigin/"
    )

    Log.d(TAG, "PRIVATE MONO HTTP = ${monoTest.code}")
    Log.d(TAG, "PRIVATE MONO BODY = ${monoTest.text.take(200)}")

    if (monoTest.text.trimStart().startsWith("#EXTM3U")) {
        finalStreamUrl = monoUrl
        Log.d(TAG, "PRIVATE MONO OK")
    } else {
        Log.e(TAG, "PRIVATE: nessun manifest HLS valido")
        return false
    }
}

} catch (e: Exception) {
Log.e(TAG, "PRIVATE STREAM TEST ERROR = ${e.message}")
return false
}

    callback.invoke(
        newExtractorLink(
            source = "RiveStream",
            name = "$title - Alpha",
            url = finalStreamUrl,
            type = ExtractorLinkType.M3U8
        ) {
            referer = "$playerOrigin/"
            headers = mapOf(
                "Origin" to playerOrigin,
                "Referer" to "$playerOrigin/"
            )
        }
    )

    return true

} catch (e: Exception) {
    Log.e(TAG, "PRIVATE ERROR = ${e.message}", e)
    return false
}

}
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

    try {

        val embedResponse =
            app.get(
                embedUrl,
                headers = mapOf(
                    "Accept" to
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )

        Log.d(
            TAG,
            "SPORT EMBED HTTP = ${embedResponse.code}"
        )

        val embedHtml =
            embedResponse.text

        Log.d(
            TAG,
            "SPORT EMBED SIZE = ${embedHtml.length}"
        )

        Log.d(
            TAG,
            "SPORT EMBED BODY = ${
                embedHtml
                    .take(3000)
                    .replace("\n", " ")
            }"
        )

        // Cerchiamo già eventuali URL HLS visibili
        val hlsUrls =
            Regex(
                """https?://[^"'\\\s<>]+\.m3u8[^"'\\\s<>]*""",
                RegexOption.IGNORE_CASE
            )
                .findAll(
                    embedHtml.replace("\\/", "/")
                )
                .map {
                    it.value
                }
                .distinct()
                .toList()

        Log.d(
            TAG,
            "SPORT HLS FOUND = ${hlsUrls.size}"
        )

        hlsUrls.forEach { hls ->

            Log.d(
                TAG,
                "SPORT HLS = $hls"
            )
        }

    } catch (e: Exception) {

        Log.e(
            TAG,
            "SPORT EMBED ERROR " +
                "url=$embedUrl " +
                "message=${e.message}",
            e
        )
    }

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

private fun buildPrivateTvData(
id: String,
title: String

): String {

return "https://rivestream.local/item?" +
    "type=${encode("tv-private")}" +
    "&id=${encode(id)}" +
    "&title=${encode(title)}" +
    "&category=${encode("Private IPTV")}"

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
