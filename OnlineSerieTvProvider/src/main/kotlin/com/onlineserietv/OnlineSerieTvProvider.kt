package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addEpisodes
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class OnlineSerieTvProvider : MainAPI() {

    override var mainUrl = "https://onlineserietv.lol"

    override var name = "OnlineSerieTv"

    override val hasMainPage = true

    override var lang = "it"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val tmdb = TmdbProvider()

    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Film",
        "$mainUrl/serietv/" to "Serie TV"
    )

    private fun cleanTitle(title: String): String {
        return title
            .replace("Streaming", "", true)
            .replace("Gratis", "", true)
            .replace("ITA", "", true)
            .replace("HD", "", true)
            .replace("\\(.*?\\)".toRegex(), "")
            .trim()
    }

    private fun Element.toSearchResult(): SearchResponse? {

        val href = fixUrl(
            this.selectFirst("a")?.attr("href")
                ?: return null
        )

        val title = cleanTitle(
            this.selectFirst("img")?.attr("alt")
                ?: this.selectFirst(".title")
                    ?.text()
                ?: this.selectFirst("h2")
                    ?.text()
                ?: return null
        )

        val poster = this.selectFirst("img")
            ?.let {
                it.attr("data-src")
                    .ifBlank {
                        it.attr("src")
                    }
            }

        val isTv =
            href.contains("/serietv/")
                    || href.contains("/serie-tv/")

        return if (isTv) {

            newTvSeriesSearchResponse(
                title,
                href,
                TvType.TvSeries
            ) {
                this.posterUrl = poster
            }

        } else {

            newMovieSearchResponse(
                title,
                href,
                TvType.Movie
            ) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url =
            if (page == 1) {
                request.data
            } else {
                "${request.data}page/$page/"
            }

        val document = app.get(url).document

        val home = document.select(
            "article, .post, .items article, .ml-item"
        ).mapNotNull {
            it.toSearchResult()
        }.distinctBy {
            it.url
        }

        return newHomePageResponse(
            request.name,
            home
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val document = app.get(
            "$mainUrl/?s=${query}"
        ).document

        return document.select(
            "article, .post, .items article, .ml-item"
        ).mapNotNull {
            it.toSearchResult()
        }.distinctBy {
            it.url
        }
    }

    override suspend fun load(url: String): LoadResponse {

        val document = app.get(url).document

        val rawTitle = document.selectFirst("h1")
            ?.text()
            ?: document.selectFirst(
                "meta[property=og:title]"
            )?.attr("content")
            ?: "Senza Titolo"

        val title = cleanTitle(rawTitle)

        val tmdbData =
            tmdb.search(title)
                ?.firstOrNull()

        val poster =
            tmdbData?.posterUrl
                ?: document.selectFirst(
                    "meta[property=og:image]"
                )?.attr("content")
                ?: document.selectFirst(
                    ".imagen img"
                )?.attr("src")

        var description: String? = null

        val tramaElement = document.select(
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

            description = document.select(
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
            tmdbData?.overview
                ?: description
                ?: document.selectFirst(
                    "meta[property=og:description]"
                )?.attr("content")

        /**
         * SERIE TV
         */
        if (
            url.contains("/serietv/")
            || url.contains("/serie-tv/")
        ) {

            val episodes = mutableListOf<Episode>()

            var epCount = 1

            document.select(
                "table tr, div.data-content a, td a"
            ).forEach { element ->

                val link = element.attr("href")

                if (
                    link.isNotBlank() &&
                    (
                        link.contains("uprot") ||
                        link.contains("stream") ||
                        link.contains("tape") ||
                        link.contains("mixdrop") ||
                        link.contains("vidoza") ||
                        link.contains("streamwish")
                    )
                ) {

                    val rowText =
                        element.parents()
                            .select("tr")
                            .first()
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

                    episodes.add(
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
                episodes.distinctBy {
                    "${it.season}-${it.episode}"
                }
            ) {

                this.posterUrl = poster

                this.plot = finalDescription
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

            this.plot = finalDescription
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: SubtitleFileCallback,
        callback: ExtractorLinkCallback
    ): Boolean {

        val document = app.get(data).document

        val links = mutableSetOf<String>()

        document.select("iframe").forEach {

            val src = fixUrl(
                it.attr("src")
            )

            if (src.isNotBlank()) {
                links.add(src)
            }
        }

        document.select("a").forEach {

            val href = fixUrl(
                it.attr("href")
            )

            if (
                href.contains("mixdrop")
                || href.contains("streamwish")
                || href.contains("vidoza")
                || href.contains("dood")
                || href.contains("streamtape")
                || href.contains("filelions")
                || href.contains("uqload")
            ) {

                links.add(href)
            }
        }

        links.distinct().forEach { link ->

            loadExtractor(
                link,
                data,
                subtitleCallback,
                callback
            )
        }

        return true
    }
}
