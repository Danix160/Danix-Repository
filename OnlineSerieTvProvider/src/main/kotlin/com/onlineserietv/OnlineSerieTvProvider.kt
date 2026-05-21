package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document

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
     * Pulizia titolo avanzata
     */
    private fun cleanTitle(title: String): String {

        val isSubIta = title.contains(
            "(?i)\\bSUB[- ]?ITA\\b".toRegex()
        )

        var cleaned = title
            .replace(" in streaming - OnlineSerieTv", "")
            .replace("(?i)\\bSUB[- ]?ITA\\b".toRegex(), "")
            .replace(
                "(?i)\\b(ITA|HD|STREAMING|ALTADEFINIZIONE|STAGIONE \\d+|STAGIONE)\\b".toRegex(),
                ""
            )
            .replace(
                """\s*[\(\[-]?\s*(19|20)\d{2}\s*[\)\]-]?\s*""".toRegex(),
                " "
            )
            .replace("""\s*[-–—:|]+\s*$""".toRegex(), "")
            .replace("""^\s*[-–—:|]+\s*""".toRegex(), "")
            .replace("""\s+""".toRegex(), " ")
            .trim()

        if (isSubIta) {
            cleaned = "$cleaned SUB ITA"
        }

        return cleaned
    }

    /**
     * Homepage
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = app.get(request.data).document

        val home = mutableListOf<SearchResponse>()

        // Layout UAGB
        document.select(".uagb-post__inner-wrap").forEach { element ->

            val titleEl =
                element.selectFirst(".uagb-post__title a")
                    ?: return@forEach

            val rawTitle = titleEl.text()

            val title = cleanTitle(rawTitle)

            val url = fixUrl(titleEl.attr("href"))

            val poster =
                element.selectFirst(".uagb-post__image img")
                    ?.attr("src")

            val type =
                if (url.contains("/film/"))
                    TvType.Movie
                else
                    TvType.TvSeries

            home.add(
                newMovieSearchResponse(
                    title,
                    url,
                    type
                ) {
                    this.posterUrl = poster
                }
            )
        }

        // Layout classico
        document.select(".movie").forEach { element ->

            val rawTitle =
                element.selectFirst("h2")
                    ?.text()
                    ?: return@forEach

            val title = cleanTitle(rawTitle)

            val linkEl =
                element.selectFirst(".imagen a")
                    ?: element.selectFirst("a")
                    ?: return@forEach

            val url = fixUrl(linkEl.attr("href"))

            val poster =
                element.selectFirst("img")
                    ?.attr("src")

            val type =
                if (url.contains("/film/"))
                    TvType.Movie
                else
                    TvType.TvSeries

            home.add(
                newMovieSearchResponse(
                    title,
                    url,
                    type
                ) {
                    this.posterUrl = poster
                }
            )
        }

        return newHomePageResponse(
            request.name,
            home.distinctBy { it.url }
        )
    }

    /**
     * Search
     */
    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val results = mutableListOf<SearchResponse>()

        for (page in 1..10) {

            try {

                val url =
                    if (page == 1)
                        "$mainUrl/?s=$query"
                    else
                        "$mainUrl/page/$page/?s=$query"

                val document = app.get(url).document

                document.select(".movie, .uagb-post__inner-wrap")
                    .forEach { element ->

                        val titleEl =
                            element.selectFirst("h2")
                                ?: element.selectFirst(".uagb-post__title a")
                                ?: return@forEach

                        val rawTitle = titleEl.text()

                        val title = cleanTitle(rawTitle)

                        val targetUrl =
                            fixUrl(
                                element.selectFirst("a")
                                    ?.attr("href")
                                    ?: return@forEach
                            )

                        val poster =
                            element.selectFirst("img")
                                ?.attr("src")

                        val type =
                            if (targetUrl.contains("/film/"))
                                TvType.Movie
                            else
                                TvType.TvSeries

                        results.add(
                            newMovieSearchResponse(
                                title,
                                targetUrl,
                                type
                            ) {
                                this.posterUrl = poster
                            }
                        )
                    }

            } catch (_: Exception) {
                break
            }
        }

        return results.distinctBy { it.url }
    }

    /**
     * Load contenuti
     */
    override suspend fun load(
        url: String
    ): LoadResponse {

        val document = app.get(url).document

        val rawTitle =
            document.selectFirst("h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")
                    ?.attr("content")
                ?: "Senza Titolo"

        val title = cleanTitle(rawTitle)

        val isMovie = url.contains("/film/")

        /**
         * TMDB
         */
        val tmdbData = tmdb.search(title).firstOrNull()

        val poster =
            tmdbData?.posterUrl
                ?: document.selectFirst("meta[property=og:image]")
                    ?.attr("content")
                ?: document.selectFirst(".imagen img")
                    ?.attr("src")

        val background =
            tmdbData?.backgroundPosterUrl

        val rating =
            tmdbData?.rating

        val tags =
            tmdbData?.tags

        /**
         * Trama
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
                    ?: tramaElement.nextElementSiblings()
                        .firstOrNull {
                            it.tagName() == "p"
                        }
                        ?.text()
        }

        if (description.isNullOrBlank()) {

            description =
                document.select(
                    "div.tsll p, .entry-content p, .post-content p, div.post p"
                )
                    .map { it.text().trim() }
                    .firstOrNull {
                        it.length > 30 &&
                                !it.contains("generato") &&
                                !it.contains("creata da") &&
                                !it.contains("visto in streaming")
                    }
        }

        val finalDescription =
            tmdbData?.plot
                ?: description
                    ?.replace(
                        "(?i)^Trama:\\s*".toRegex(),
                        ""
                    )
                    ?.trim()

        /**
         * SERIE TV
         */
        if (!isMovie) {

            val episodes = mutableListOf<Episode>()

            var epCount = 1

            document.select(
                "table tr, div.data-content a, td a"
            ).forEach { element ->

                val link = fixUrl(element.attr("href"))

                if (
                    link.contains("uprot") ||
                    link.contains("stream") ||
                    link.contains("tape") ||
                    link.contains("flexy")
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

                    /**
                     * DATI EPISODIO TMDB
                     */
                    val episodeData =
                        try {
                            tmdb.loadEpisode(
                                season,
                                episode
                            )
                        } catch (_: Exception) {
                            null
                        }

                    episodes.add(
                        newEpisode(link) {

                            this.name =
                                episodeData?.name
                                    ?: "Episodio $episode"

                            this.description =
                                episodeData?.description

                            this.posterUrl =
                                episodeData?.posterUrl
                                    ?: poster

                            this.season = season
                            this.episode = episode
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes.distinctBy {
                    "${it.season}-${it.episode}"
                }
            ) {

                this.posterUrl = poster

                this.backgroundPosterUrl = background

                this.plot = finalDescription

                this.rating = rating

                this.tags = tags
            }
        }

        /**
         * FILM
         */
        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {

            this.posterUrl = poster

            this.backgroundPosterUrl = background

            this.plot = finalDescription

            this.rating = rating

            this.tags = tags
        }
    }

    /**
     * Load Links
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        if (data.contains("/film/")) {

            val document = app.get(data).document

            document.select("a").forEach { element ->

                val link = fixUrl(element.attr("href"))

                if (
                    link.contains("uprot") ||
                    link.contains("stream") ||
                    link.contains("tape") ||
                    link.contains("flexy")
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
