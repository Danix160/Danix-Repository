package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class OnlineSerieTvProvider : MainAPI() {

    override var mainUrl = "https://onlineserietv.lol"

    override var name = "OnlineSerieTv"

    override val hasMainPage = true

    override var lang = "it"

    override val hasChromecastSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val tmdb = TmdbProvider()

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Film",
        "$mainUrl/serie-tv/" to "Serie TV",
        "$mainUrl/serie-tv-generi/animazione/" to "Cartoni & Anime"
    )

    /**
     * CLEAN TITLE
     */
    private fun cleanTitle(title: String): String {

        val isSubIta =
            title.contains(
                "(?i)\\bSUB[- ]?ITA\\b".toRegex()
            )

        var cleaned = title
            .replace(
                " in streaming - OnlineSerieTv",
                ""
            )
            .replace(
                "(?i)\\bSUB[- ]?ITA\\b".toRegex(),
                ""
            )
            .replace(
                "(?i)\\b(ITA|STAGIONE \\d+|STAGIONE)\\b".toRegex(),
                ""
            )
            .replace(
                """\s*[\(\[-]?\s*(19|20)\d{2}\s*[\)\]-]?\s*""".toRegex(),
                " "
            )
            .replace(
                """\s*[-–—:|]+\s*$""".toRegex(),
                ""
            )
            .replace(
                """^\s*[-–—:|]+\s*""".toRegex(),
                ""
            )
            .replace(
                """\s+""".toRegex(),
                " "
            )
            .trim()

        if (isSubIta) {
            cleaned = "$cleaned SUB ITA"
        }

        return cleaned
    }

    /**
     * MAIN PAGE
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {

        val document =
            app.get(request.data).document

        val homeResults =
            mutableListOf<SearchResponse>()

        /**
         * UAGB POSTS
         */
        document.select(
            ".uagb-post__inner-wrap"
        ).forEach { element ->

            val titleEl =
                element.selectFirst(
                    ".uagb-post__title a"
                )

            val rawTitle =
                titleEl?.text()
                    ?: return@forEach

            val title = cleanTitle(rawTitle)

            val url =
                titleEl.attr("href")

            val poster =
                element.selectFirst(
                    ".uagb-post__image img"
                )?.attr("src")

            val type =
                if (url.contains("/film/")) {
                    TvType.Movie
                } else {
                    TvType.TvSeries
                }

            homeResults.add(
                newMovieSearchResponse(
                    title,
                    url,
                    TvType.Movie
                ) {

                    this.posterUrl = poster

                    this.type = type
                }
            )
        }

        /**
         * CLASSIC POSTS
         */
        document.select(".movie")
            .forEach { element ->

                val rawTitle =
                    element.selectFirst("h2")
                        ?.text()
                        ?: return@forEach

                val title =
                    cleanTitle(rawTitle)

                val linkEl =
                    element.selectFirst(
                        ".imagen a"
                    )
                        ?: element.selectFirst("a")

                val url =
                    linkEl?.attr("href")
                        ?: return@forEach

                val poster =
                    element.selectFirst("img")
                        ?.attr("src")

                val type =
                    if (url.contains("/film/")) {
                        TvType.Movie
                    } else {
                        TvType.TvSeries
                    }

                homeResults.add(
                    newMovieSearchResponse(
                        title,
                        url,
                        TvType.Movie
                    ) {

                        this.posterUrl = poster

                        this.type = type
                    }
                )
            }

        return newHomePageResponse(
            request.name,
            homeResults
        )
    }

    /**
     * SEARCH
     */
    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val results =
            mutableListOf<SearchResponse>()

        val maxPagesToSearch = 10

        for (page in 1..maxPagesToSearch) {

            try {

                val url =
                    if (page == 1) {
                        "$mainUrl/?s=$query"
                    } else {
                        "$mainUrl/page/$page/?s=$query"
                    }

                val response =
                    app.get(url)

                if (response.code != 200) {
                    break
                }

                val document =
                    response.document

                val initialCount =
                    results.size

                /**
                 * MOVIES
                 */
                document.select(".movie")
                    .forEach { element ->

                        val rawTitle =
                            element.selectFirst("h2")
                                ?.text()
                                ?: return@forEach

                        val title =
                            cleanTitle(rawTitle)

                        val targetUrl =
                            element.selectFirst(
                                ".imagen a"
                            )?.attr("href")
                                ?: element.selectFirst("a")
                                    ?.attr("href")
                                ?: return@forEach

                        val poster =
                            element.selectFirst("img")
                                ?.attr("src")

                        val type =
                            if (
                                targetUrl.contains("/film/")
                            ) {
                                TvType.Movie
                            } else {
                                TvType.TvSeries
                            }

                        results.add(
                            newMovieSearchResponse(
                                title,
                                targetUrl,
                                TvType.Movie
                            ) {

                                this.posterUrl = poster

                                this.type = type
                            }
                        )
                    }

                /**
                 * UAGB POSTS
                 */
                document.select(
                    ".uagb-post__inner-wrap"
                ).forEach { element ->

                    val titleEl =
                        element.selectFirst(
                            ".uagb-post__title a"
                        )

                    val rawTitle =
                        titleEl?.text()
                            ?: return@forEach

                    val title =
                        cleanTitle(rawTitle)

                    val targetUrl =
                        titleEl.attr("href")

                    val poster =
                        element.selectFirst(
                            ".uagb-post__image img"
                        )?.attr("src")

                    val type =
                        if (
                            targetUrl.contains("/film/")
                        ) {
                            TvType.Movie
                        } else {
                            TvType.TvSeries
                        }

                    results.add(
                        newMovieSearchResponse(
                            title,
                            targetUrl,
                            TvType.Movie
                        ) {

                            this.posterUrl = poster

                            this.type = type
                        }
                    )
                }

                if (results.size == initialCount) {
                    break
                }

            } catch (_: Exception) {
                break
            }
        }

        return results.distinctBy {
            it.url
        }
    }

    /**
     * LOAD
     */
    override suspend fun load(
        url: String
    ): LoadResponse {

        val document =
            app.get(url).document

        val rawTitle =
            document.selectFirst("h1")
                ?.text()
                ?: document.selectFirst(
                    "meta[property=og:title]"
                )?.attr("content")
                ?: "Senza Titolo"

        val title =
            cleanTitle(rawTitle)

        /**
         * TMDB
         */
        val tmdbData =
            tmdb.search(title)
                ?.firstOrNull()

        /**
         * POSTER
         */
        val poster =
            tmdbData?.posterUrl
                ?: document.selectFirst(
                    "meta[property=og:image]"
                )?.attr("content")
                ?: document.selectFirst(
                    ".imagen img"
                )?.attr("src")

        /**
         * DESCRIPTION
         */
        var description: String? = null

        val tramaElement =
            document.select(
                "b:contains(Trama), strong:contains(Trama)"
            ).firstOrNull()

        if (tramaElement != null) {

            description =
                tramaElement.nextElementSibling()
                    ?.selectFirst("p")
                    ?.text()
                    ?: tramaElement
                        .nextElementSiblings()
                        .firstOrNull {
                            it.tagName() == "p"
                        }
                        ?.text()
        }

        if (description.isNullOrBlank()) {

            description =
                document.select(
                    "div.tsll p, .entry-content p, .post-content p"
                )
                    .map {
                        it.text().trim()
                    }
                    .firstOrNull {
                        it.length > 30
                    }
        }

        val finalDescription =
            tmdbData?.plot
                ?: description
                ?: document.selectFirst(
                    "meta[property=og:description]"
                )?.attr("content")

        /**
         * SCORE
         */
        val score =
            tmdbData?.rating?.toFloat()

        /**
         * SERIES
         */
        if (
            url.contains("/serietv/")
            || url.contains("/serie-tv/")
        ) {

            val episodesList =
                mutableListOf<Episode>()

            var epCount = 1

            document.select(
                "table tr, div.data-content a, td a"
            ).forEach { element ->

                val link =
                    element.attr("href")

                if (
                    link.isNotBlank()
                    && (
                        link.contains("uprot")
                                || link.contains("stream")
                                || link.contains("tape")
                                || link.contains("flexy")
                        )
                ) {

                    val rowText =
                        element.parents()
                            .select("tr")
                            .first()
                            ?.selectFirst("td")
                            ?.text()
                            ?: element.text()

                    val match =
                        "(\\d+)x(\\d+)"
                            .toRegex()
                            .find(rowText)

                    val season =
                        match?.groupValues
                            ?.get(1)
                            ?.toIntOrNull()
                            ?: 1

                    val episode =
                        match?.groupValues
                            ?.get(2)
                            ?.toIntOrNull()
                            ?: epCount++

                    episodesList.add(

                        newEpisode(link) {

                            this.name =
                                "Episodio $episode"

                            this.posterUrl =
                                poster

                            this.season =
                                season

                            this.episode =
                                episode
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodesList.distinctBy {
                    "${it.season}-${it.episode}"
                }
            ) {

                this.posterUrl =
                    poster

                this.plot =
                    finalDescription

                this.score =
                    score
            }
        }

        /**
         * MOVIE
         */
        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {

            this.posterUrl =
                poster

            this.plot =
                finalDescription

            this.score =
                score
        }
    }

    /**
     * LOAD LINKS
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        if (data.contains("/film/")) {

            val document =
                app.get(data).document

            document.select("a")
                .forEach { element ->

                    val link =
                        element.attr("href")

                    if (
                        link.contains("uprot")
                        || link.contains("stream")
                        || link.contains("tape")
                        || link.contains("flexy")
                    ) {

                        loadExtractor(
                            link,
                            mainUrl,
                            subtitleCallback,
                            callback
                        )
                    }
                }

        } else {

            loadExtractor(
                data,
                mainUrl,
                subtitleCallback,
                callback
            )
        }

        return true
    }
}
