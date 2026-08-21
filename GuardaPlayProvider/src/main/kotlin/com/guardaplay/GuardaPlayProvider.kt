package com.guardaplay

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Element
import java.net.URLEncoder

class GuardaPlayProvider : MainAPI() {

    override var mainUrl = "https://guardaplay.online"
    override var name = "GuardaPlay"
    override var lang = "it"

    override val hasMainPage = true
    override val hasChromecastSupport = true

    override val supportedTypes =
        setOf(TvType.Movie)

    companion object {
        private const val TAG = "GUARDAPLAY_DEBUG"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"

        private const val TMDB_API_KEY =
            "e541cb159df14ce70fc51ab75703a1a2"
    }

    override val mainPage =
        mainPageOf(
            "$mainUrl/" to "Film"
        )

    private val headers =
        mapOf(
            "User-Agent" to USER_AGENT
        )

    private fun normalizeUrl(url: String): String {
        val value = url.trim()

        return when {
            value.startsWith("http", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> mainUrl.trimEnd('/') + value
            value.isBlank() -> ""
            else -> mainUrl.trimEnd('/') + "/" + value.trimStart('/')
        }
    }

    private fun parseCard(
        element: Element
    ): SearchResponse? {

        val title =
            element.selectFirst(".entry-title")
                ?.text()
                ?.trim()
                ?: return null

        val href =
            element.selectFirst("a.lnk-blk")
                ?.attr("href")
                ?.trim()
                ?: return null

        val poster =
            element.selectFirst("img")
                ?.attr("src")
                ?.let(::normalizeUrl)

        return newMovieSearchResponse(
            title,
            normalizeUrl(href),
            TvType.Movie
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse =
        withContext(Dispatchers.IO) {

            val url =
                if (page <= 1) {
                    mainUrl
                } else {
                    "$mainUrl/page/$page/"
                }

            Log.d(TAG, "HOME = $url")

            val document =
                app.get(
                    url,
                    headers = headers
                ).document

            val sections =
                mutableListOf<HomePageList>()

            document
                .select("section.section.movies")
                .forEach { section ->

                    val title =
                        section.selectFirst(
                            "header .section-title"
                        )
                            ?.text()
                            ?.trim()
                            ?: "Film"

                    val items =
                        section.select(".post-lst li")
                            .mapNotNull(::parseCard)

                    if (items.isNotEmpty()) {
                        sections +=
                            HomePageList(
                                title,
                                items
                            )
                    }
                }

            if (sections.isEmpty()) {
                val items =
                    document
                        .select(".post-lst li")
                        .mapNotNull(::parseCard)

                sections +=
                    HomePageList(
                        request.name,
                        items
                    )
            }

            newHomePageResponse(
                sections
            )
        }

    override suspend fun search(
        query: String
    ): List<SearchResponse> =
        withContext(Dispatchers.IO) {

            if (query.isBlank()) {
                return@withContext emptyList()
            }

            val encoded =
                URLEncoder.encode(
                    query,
                    "UTF-8"
                )

            val results =
                mutableListOf<SearchResponse>()

            for (page in 1..5) {

                val url =
                    if (page == 1) {
                        "$mainUrl/?s=$encoded"
                    } else {
                        "$mainUrl/page/$page/?s=$encoded"
                    }

                Log.d(
                    TAG,
                    "SEARCH PAGE $page = $url"
                )

                val response =
                    app.get(
                        url,
                        headers = headers
                    )

                if (response.code != 200) {
                    break
                }

                val document =
                    response.document

                val pageResults =
                    document
                        .select(".post-lst li")
                        .mapNotNull(::parseCard)

                results.addAll(pageResults)

                if (
                    page == 1 &&
                    document.selectFirst(
                        ".navigation.pagination .nav-links a.page-link"
                    ) == null
                ) {
                    break
                }

                if (pageResults.isEmpty()) {
                    break
                }
            }

            results.distinctBy {
                it.url
            }
        }

    private suspend fun getTmdbMovie(
        title: String
    ): Map<String, Any>? {

        val encoded =
            URLEncoder.encode(
                title,
                "UTF-8"
            )

        val search =
            app.get(
                "https://api.themoviedb.org/3/search/movie" +
                    "?api_key=$TMDB_API_KEY" +
                    "&language=it-IT" +
                    "&query=$encoded"
            )
                .parsedSafe<Map<String, Any>>()
                ?: return null

        val results =
            (search["results"] as? List<*>)
                ?.filterIsInstance<Map<String, Any>>()
                ?: return null

        val first =
            results.firstOrNull()
                ?: return null

        val id =
            (first["id"] as? Number)
                ?.toInt()
                ?: return null

        return app.get(
            "https://api.themoviedb.org/3/movie/$id" +
                "?api_key=$TMDB_API_KEY" +
                "&language=it-IT"
        )
            .parsedSafe()
    }

    override suspend fun load(
        url: String
    ): LoadResponse =
        withContext(Dispatchers.IO) {

            Log.d(
                TAG,
                "LOAD = $url"
            )

            val document =
                app.get(
                    url,
                    headers = headers
                ).document

            val title =
                document.selectFirst(
                    "h1.entry-title"
                )
                    ?.text()
                    ?.trim()
                    ?: "Senza titolo"

            val tmdb =
                runCatching {
                    getTmdbMovie(title)
                }.getOrNull()

            val tmdbPoster =
                (tmdb?.get("poster_path") as? String)
                    ?.let {
                        "https://image.tmdb.org/t/p/w780$it"
                    }

            val poster =
                tmdbPoster
                    ?: document.selectFirst(
                        ".post-thumbnail img"
                    )
                        ?.attr("src")
                        ?.let(::normalizeUrl)

            val plot =
                (tmdb?.get("overview") as? String)
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: document.selectFirst(
                        ".description p"
                    )
                        ?.text()
                        ?.trim()

            val runtime =
                (tmdb?.get("runtime") as? Number)
                    ?.toInt()
                    ?: document
                        .selectFirst(
                            "span.duration.fa-clock.far"
                        )
                        ?.text()
                        ?.trim()
                        ?.let { text ->

                            val hours =
                                Regex("""(\d+)h""")
                                    .find(text)
                                    ?.groupValues
                                    ?.getOrNull(1)
                                    ?.toIntOrNull()
                                    ?: 0

                            val minutes =
                                Regex("""(\d+)m""")
                                    .find(text)
                                    ?.groupValues
                                    ?.getOrNull(1)
                                    ?.toIntOrNull()
                                    ?: 0

                            if (
                                hours > 0 ||
                                minutes > 0
                            ) {
                                hours * 60 + minutes
                            } else {
                                null
                            }
                        }

            val year =
                (tmdb?.get("release_date") as? String)
                    ?.take(4)
                    ?.toIntOrNull()

            val imdb =
                tmdb?.get("imdb_id") as? String

            val genres =
                (tmdb?.get("genres") as? List<*>)
                    ?.filterIsInstance<Map<String, Any>>()
                    ?.mapNotNull {
                        it["name"]?.toString()
                    }

            return@withContext newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                "MOVIE|$url"
            ) {

                this.posterUrl =
                    poster

                this.plot =
                    plot

                this.year =
                    year

                if (
                    runtime != null &&
                    runtime > 0
                ) {
                    this.duration =
                        runtime
                }

                if (
                    !genres.isNullOrEmpty()
                ) {
                    this.tags =
                        genres
                }

                imdb?.let {
                    this.addImdbId(it)
                }
            }
        }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        Log.d(
            TAG,
            "LOADLINKS = $data"
        )

        val cleanData =
            data
                .removePrefix("$mainUrl/")
                .removePrefix(mainUrl)

        val parts =
            cleanData.split("|")

        if (
            parts.firstOrNull() != "MOVIE"
        ) {
            return false
        }

        val pageUrl =
            parts.getOrNull(1)
                ?: return false

        val document =
            app.get(
                pageUrl,
                headers = headers
            ).document

        val options =
            document.select(
                "#aa-options div[id^=options-]"
            )

        Log.d(
            TAG,
            "OPZIONI TROVATE = ${options.size}"
        )

        options.forEachIndexed {
                index,
                option ->

            val rawIframe =
                option.selectFirst(
                    "iframe[data-src]"
                )
                    ?.attr("data-src")
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: option.selectFirst(
                        "iframe[src]"
                    )
                        ?.attr("src")
                        ?.takeIf {
                            it.isNotBlank()
                        }

            if (
                rawIframe.isNullOrBlank()
            ) {
                return@forEachIndexed
            }

            val firstUrl =
                normalizeUrl(
                    rawIframe
                )

            Log.d(
                TAG,
                "OPZIONE ${index + 1} IFRAME1 = $firstUrl"
            )

            try {

                val embedDocument =
                    app.get(
                        firstUrl,
                        headers =
                            mapOf(
                                "User-Agent" to
                                    USER_AGENT,

                                "Referer" to
                                    pageUrl
                            )
                    ).document

                var finalUrl =
                    embedDocument
                        .selectFirst(
                            ".Video iframe[src]"
                        )
                        ?.attr("src")
                        ?.trim()

                if (
                    finalUrl.isNullOrBlank()
                ) {

                    /*
                     * Fallback nel caso il sito
                     * cambi leggermente wrapper.
                     */
                    finalUrl =
                        embedDocument
                            .selectFirst(
                                "iframe[src]"
                            )
                            ?.attr("src")
                            ?.trim()
                }

                if (
                    finalUrl.isNullOrBlank()
                ) {
                    Log.d(
                        TAG,
                        "OPZIONE ${index + 1}: iframe finale non trovato"
                    )

                    return@forEachIndexed
                }

                finalUrl =
                    normalizeUrl(
                        finalUrl
                    )

                Log.d(
                    TAG,
                    "OPZIONE ${index + 1} FINAL = $finalUrl"
                )

                loadExtractor(
                    finalUrl,
                    firstUrl,
                    subtitleCallback,
                    callback
                )

            } catch (
                e: Exception
            ) {

                Log.e(
                    TAG,
                    "Errore opzione ${index + 1}: ${e.message}",
                    e
                )
            }
        }

        return true
    }
}
