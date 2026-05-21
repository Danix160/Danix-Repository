package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
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

    override val mainPage = mainPageOf(
        "$mainUrl/serie-tv/" to "Serie TV",
        "$mainUrl/film/" to "Film",
        "$mainUrl/animazione/" to "Animazione"
    )

    private fun Element.toSearchResult(): SearchResponse? {

        val title =
            this.selectFirst("h2, h3, .title")?.text()?.trim()
                ?: return null

        val href =
            fixUrl(
                this.selectFirst("a")?.attr("href")
                    ?: return null
            )

        val poster =
            this.selectFirst("img")?.let { img ->

                img.attr("data-src").ifBlank {
                    img.attr("src")
                }
            }

        val isMovie =
            href.contains("/film/")

        return if (isMovie) {

            newMovieSearchResponse(
                title,
                href,
                TvType.Movie
            ) {
                this.posterUrl = poster
            }

        } else {

            newTvSeriesSearchResponse(
                title,
                href,
                TvType.TvSeries
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
            "article, .items article, .post"
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

    override suspend fun search(query: String): List<SearchResponse> {

        val url =
            "$mainUrl/?s=${query.replace(" ", "+")}"

        val document = app.get(url).document

        return document.select(
            "article, .items article, .post"
        ).mapNotNull {
            it.toSearchResult()
        }.distinctBy {
            it.url
        }
    }

    override suspend fun load(url: String): LoadResponse {

        val document = app.get(url).document

        val title =
            document.selectFirst(
                "h1"
            )?.text()?.trim()
                ?: "No Title"

        val poster =
            document.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank {
                    img.attr("src")
                }
            }

        val description =
            document.selectFirst(
                "meta[property=og:description]"
            )?.attr("content")
                ?: document.selectFirst(
                    ".wp-content p"
                )?.text()

        val year =
            document.selectFirst(
                ".date, .year"
            )?.text()
                ?.filter {
                    it.isDigit()
                }
                ?.take(4)
                ?.toIntOrNull()

        val tags =
            document.select(
                ".sgeneros a, .genres a"
            ).map {
                it.text()
            }

        val actors =
            document.select(
                ".persons a, .cast a"
            ).map {
                Actor(it.text())
            }

        val isMovie =
            url.contains("/film/")

        return if (!isMovie) {

            val episodes = mutableListOf<Episode>()

            document.select(
                ".se-c, .episodios li, li.mark-episode"
            ).forEachIndexed { index, element ->

                val epTitle =
                    element.selectFirst(
                        "a"
                    )?.text()
                        ?: "Episodio ${index + 1}"

                val epLink =
                    fixUrl(
                        element.selectFirst(
                            "a"
                        )?.attr("href")
                            ?: url
                    )

                episodes.add(
                    Episode(
                        epLink,
                        epTitle
                    )
                )
            }

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {

                this.posterUrl = poster

                this.plot = description

                this.year = year

                this.tags = tags

                addActors(actors)
            }

        } else {

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {

                this.posterUrl = poster

                this.plot = description

                this.year = year

                this.tags = tags

                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        document.select("iframe").forEach { iframe ->

            val src =
                iframe.attr("src")

            if (src.isNotBlank()) {

                loadExtractor(
                    fixUrl(src),
                    data,
                    subtitleCallback,
                    callback
                )
            }
        }

        document.select("a").forEach { link ->

            val href =
                link.attr("href")

            if (
                href.contains("mixdrop") ||
                href.contains("streamtape") ||
                href.contains("dood") ||
                href.contains("filemoon") ||
                href.contains("supervideo")
            ) {

                loadExtractor(
                    fixUrl(href),
                    data,
                    subtitleCallback,
                    callback
                )
            }
        }

        return true
    }
}
