package com.rivestream

import android.util.Log
import com.lagradost.cloudstream3.*
import java.net.URLDecoder
import java.net.URLEncoder

class RiveStreamProvider : MainAPI() {

    override var name =
        "RiveStream"

    override var mainUrl =
        "https://rivestream.ru"

    override var lang =
        "it"

    override val supportedTypes =
        setOf(
            TvType.Live
        )

    override val hasMainPage =
        true

    override val hasQuickSearch =
        true

    companion object {
        private const val TAG =
            "RIVESTREAM_DEBUG"
    }

    override val mainPage =
        mainPageOf(
            "$mainUrl/iptv" to
                "TV Italiana",

            "$mainUrl/livesports" to
                "Eventi sportivi"
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

        return when {

            request.data.contains(
                "/iptv"
            ) ->
                loadItalianTv(
                    request.name
                )

            request.data.contains(
                "/livesports"
            ) ->
                loadSportsEvents(
                    request.name
                )

            else ->
                newHomePageResponse(
                    request.name,
                    emptyList()
                )
        }
    }

    // ============================================================
    // TV ITALIANA
    // ============================================================

    private suspend fun loadItalianTv(
        sectionName: String
    ): HomePageResponse {

        val document =
            try {

                app.get(
                    "$mainUrl/iptv"
                ).document

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "IPTV ERROR = ${e.message}"
                )

                return newHomePageResponse(
                    sectionName,
                    emptyList()
                )
            }

        val cards =
            document.select(
                "div[class*=MovieCardSmall]"
            )

        Log.d(
            TAG,
            "IPTV CARDS = ${cards.size}"
        )

        val channels =
            cards
                .mapNotNull { card ->

                    val title =
                        card
                            .selectFirst("h4")
                            ?.text()
                            ?.trim()
                            .orEmpty()

                    if (
                        title.isBlank() ||
                        !isItalianChannel(
                            title
                        )
                    ) {
                        return@mapNotNull null
                    }

                    val category =
                        card
                            .selectFirst("p")
                            ?.text()
                            ?.trim()
                            .orEmpty()

                    val cleanTitle =
                        cleanItalianName(
                            title
                        )

                    val image =
                        card
                            .selectFirst("img")
                            ?.attr("src")
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?.let {
                                fixUrl(it)
                            }

                    Log.d(
                        TAG,
                        "ITALIAN CHANNEL = $title"
                    )

                    newLiveSearchResponse(
                        cleanTitle,
                        buildData(
                            type =
                                "iptv",

                            title =
                                title,

                            category =
                                category,

                            time =
                                ""
                        ),
                        TvType.Live
                    ) {

                        posterUrl =
                            image
                    }
                }
                .distinctBy {
                    it.name
                        .lowercase()
                }
                .sortedBy {
                    italianOrder(
                        it.name
                    )
                }

        Log.d(
            TAG,
            "ITALIAN CHANNELS = ${channels.size}"
        )

        return newHomePageResponse(
            sectionName,
            channels
        )
    }

    // ============================================================
    // EVENTI SPORTIVI
    // ============================================================

    private suspend fun loadSportsEvents(
        sectionName: String
    ): HomePageResponse {

        val document =
            try {

                app.get(
                    "$mainUrl/livesports"
                ).document

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "SPORT ERROR = ${e.message}"
                )

                return newHomePageResponse(
                    sectionName,
                    emptyList()
                )
            }

        val cards =
            document.select(
                "div[class*=MovieCardSmall]"
            )

        Log.d(
            TAG,
            "SPORT CARDS = ${cards.size}"
        )

        val events =
            cards
                .mapNotNull { card ->

                    val title =
                        card
                            .selectFirst("h4")
                            ?.text()
                            ?.trim()
                            .orEmpty()

                    if (
                        title.isBlank()
                    ) {
                        return@mapNotNull null
                    }

                    val paragraphs =
                        card.select("p")

                    val sport =
                        paragraphs
                            .getOrNull(0)
                            ?.text()
                            ?.trim()
                            .orEmpty()

                    val time =
                        paragraphs
                            .getOrNull(1)
                            ?.text()
                            ?.trim()
                            .orEmpty()

                    /*
                     * Le categorie come
                     * Basketball / Football / Hockey
                     * hanno p = "sports".
                     * Non sono eventi.
                     */
                    if (
                        sport.equals(
                            "sports",
                            ignoreCase = true
                        )
                    ) {
                        return@mapNotNull null
                    }

                    /*
                     * Una card evento reale normalmente
                     * ha almeno sport o data/orario.
                     */
                    if (
                        sport.isBlank() &&
                        time.isBlank()
                    ) {
                        return@mapNotNull null
                    }

                    val image =
                        card
                            .selectFirst("img")
                            ?.attr("src")
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?.let {
                                fixUrl(it)
                            }

                    Log.d(
                        TAG,
                        "SPORT EVENT = " +
                            "$title | $sport | $time"
                    )

                    newLiveSearchResponse(
                        title,
                        buildData(
                            type =
                                "sport",

                            title =
                                title,

                            category =
                                sport,

                            time =
                                time
                        ),
                        TvType.Live
                    ) {

                        posterUrl =
                            image
                    }
                }
                .distinctBy {
                    buildString {

                        append(
                            it.name.lowercase()
                        )
                    }
                }

        Log.d(
            TAG,
            "SPORT EVENTS = ${events.size}"
        )

        return newHomePageResponse(
            sectionName,
            events
        )
    }

    // ============================================================
    // SEARCH
    // ============================================================

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        if (
            query.length < 2
        ) {
            return emptyList()
        }

        val normalized =
            query
                .trim()
                .lowercase()

        val result =
            mutableListOf<SearchResponse>()

        /*
         * Cerca prima nei canali IPTV.
         */
        try {

            val iptv =
                app.get(
                    "$mainUrl/iptv"
                ).document

            iptv
                .select(
                    "div[class*=MovieCardSmall]"
                )
                .forEach { card ->

                    val title =
                        card
                            .selectFirst("h4")
                            ?.text()
                            ?.trim()
                            .orEmpty()

                    if (
                        title.isBlank() ||
                        !isItalianChannel(title)
                    ) {
                        return@forEach
                    }

                    if (
                        !title.lowercase()
                            .contains(normalized)
                    ) {
                        return@forEach
                    }

                    val category =
                        card
                            .selectFirst("p")
                            ?.text()
                            ?.trim()
                            .orEmpty()

                    result.add(
                        newLiveSearchResponse(
                            cleanItalianName(
                                title
                            ),
                            buildData(
                                type =
                                    "iptv",

                                title =
                                    title,

                                category =
                                    category,

                                time =
                                    ""
                            ),
                            TvType.Live
                        )
                    )
                }

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "SEARCH IPTV ERROR = ${e.message}"
            )
        }

        /*
         * Cerca anche negli eventi.
         */
        try {

            val sports =
                app.get(
                    "$mainUrl/livesports"
                ).document

            sports
                .select(
                    "div[class*=MovieCardSmall]"
                )
                .forEach { card ->

                    val title =
                        card
                            .selectFirst("h4")
                            ?.text()
                            ?.trim()
                            .orEmpty()

                    val p =
                        card.select("p")

                    val sport =
                        p.getOrNull(0)
                            ?.text()
                            ?.trim()
                            .orEmpty()

                    val time =
                        p.getOrNull(1)
                            ?.text()
                            ?.trim()
                            .orEmpty()

                    if (
                        title.isBlank() ||
                        sport.equals(
                            "sports",
                            ignoreCase = true
                        )
                    ) {
                        return@forEach
                    }

                    if (
                        !title.lowercase()
                            .contains(normalized) &&
                        !sport.lowercase()
                            .contains(normalized)
                    ) {
                        return@forEach
                    }

                    result.add(
                        newLiveSearchResponse(
                            title,
                            buildData(
                                type =
                                    "sport",

                                title =
                                    title,

                                category =
                                    sport,

                                time =
                                    time
                            ),
                            TvType.Live
                        )
                    )
                }

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "SEARCH SPORT ERROR = ${e.message}"
            )
        }

        return result
            .distinctBy {
                it.name.lowercase()
            }
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {

        return search(
            query
        )
            .take(
                25
            )
    }

    // ============================================================
    // LOAD
    // ============================================================

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val data =
            parseData(
                url
            )
                ?: return null

        val type =
            data["type"]
                ?: return null

        val title =
            data["title"]
                ?: return null

        val category =
            data["category"]
                .orEmpty()

        val time =
            data["time"]
                .orEmpty()

        val displayTitle =
            if (
                type == "iptv"
            ) {

                cleanItalianName(
                    title
                )

            } else {
                title
            }

        val playData =
            buildData(
                type =
                    type,

                title =
                    title,

                category =
                    category,

                time =
                    time
            )

        return newLiveStreamLoadResponse(
            displayTitle,
            url,
            playData
        ) {

            plot =
                buildString {

                    when (type) {

                        "iptv" -> {

                            append(
                                "Canale TV italiano"
                            )

                            if (
                                category.isNotBlank()
                            ) {

                                append(
                                    "\n$category"
                                )
                            }
                        }

                        "sport" -> {

                            if (
                                category.isNotBlank()
                            ) {
                                append(
                                    category
                                )
                            }

                            if (
                                time.isNotBlank()
                            ) {

                                if (
                                    isNotEmpty()
                                ) {
                                    append("\n")
                                }

                                append(
                                    time
                                )
                            }
                        }
                    }
                }

            tags =
                when (type) {

                    "iptv" ->
                        listOf(
                            "TV Italiana",
                            "Live"
                        )

                    else ->
                        listOfNotNull(
                            category.takeIf {
                                it.isNotBlank()
                            },
                            "Live"
                        )
                }
        }
    }

    // ============================================================
    // LOAD LINKS
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
            parseData(
                data
            )
                ?: return false

        Log.d(
            TAG,
            "LOADLINKS " +
                "type=${parsed["type"]} " +
                "title=${parsed["title"]}"
        )

        /*
         * Catalogo pronto.
         *
         * Qui collegheremo successivamente
         * la risoluzione della pagina/player.
         */

        return false
    }

    // ============================================================
    // FILTRO ITALIA
    // ============================================================

    private val italianKeywords =
        listOf(
            "rai ",
            "rai1",
            "rai2",
            "rai3",
            "rai sport",
            "mediaset",
            "canale 5",
            "italia 1",
            "italia1",
            "rete 4",
            "rete4",
            "la7",
            "tv8",
            "nove",
            "cielo",
            "iris",
            "cine34",
            "italia 2",
            "italia2",
            "top crime",
            "tgcom24",
            "focus",
            "boing",
            "cartoonito",
            "real time",
            "dmax",
            "giallo",
            "food network",
            "motor trend",
            "warner tv",
            "super!"
        )

    private fun isItalianChannel(
        title: String
    ): Boolean {

        val name =
            title
                .lowercase()
                .trim()

        if (
            name.contains(
                " italy"
            ) ||
            name.endsWith(
                "italy"
            )
        ) {
            return true
        }

        return italianKeywords.any {
            keyword ->

            name.contains(
                keyword
            )
        }
    }

    private fun cleanItalianName(
        title: String
    ): String {

        return title
            .replace(
                Regex(
                    """\s+Italy$""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .trim()
    }

    /*
     * Solo per avere Rai/Mediaset
     * in testa alla lista.
     */
    private fun italianOrder(
        name: String
    ): String {

        val n =
            name.lowercase()

        val prefix =
            when {

                n == "rai 1" ->
                    "001"

                n == "rai 2" ->
                    "002"

                n == "rai 3" ->
                    "003"

                n.contains(
                    "rete 4"
                ) ->
                    "004"

                n.contains(
                    "canale 5"
                ) ->
                    "005"

                n.contains(
                    "italia 1"
                ) ->
                    "006"

                n.contains(
                    "la7"
                ) ->
                    "007"

                n.contains(
                    "tv8"
                ) ->
                    "008"

                n == "nove" ->
                    "009"

                else ->
                    "100"
            }

        return "$prefix-$n"
    }

    // ============================================================
    // SERIALIZZAZIONE
    // ============================================================

    private fun buildData(
        type: String,
        title: String,
        category: String,
        time: String
    ): String {

        return "https://rivestream.local/item?" +
            "type=${encode(type)}" +
            "&title=${encode(title)}" +
            "&category=${encode(category)}" +
            "&time=${encode(time)}"
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

    private fun fixUrl(
        url: String
    ): String {

        return when {

            url.startsWith(
                "//"
            ) ->
                "https:$url"

            url.startsWith(
                "/"
            ) ->
                "$mainUrl$url"

            else ->
                url
        }
    }
}
