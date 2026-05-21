package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor // Import corretto del metodo di estensione
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import org.jsoup.nodes.Document

class OnlineSerieTvProvider : MainAPI() {
    override var mainUrl = "https://onlineserietv.lol"
    override var name = "OnlineSerieTv"
    override val hasMainPage = true
    override var lang = "it"
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Film",
        "$mainUrl/serie-tv/" to "Serie TV",
        "$mainUrl/serie-tv-generi/animazione/" to "Cartoni & Anime"
    )

    /**
     * Rimuove l'anno (es. "(2024)", "[2025]" o " 2026") dal titolo per renderlo pulito
     */
    private fun cleanTitle(title: String): String {
        return title
            .replace("""\s*[\(\[-]\s*\d{4}\s*[\)\]-]""".toRegex(), "") // Rimuove (2024) o [2024] o -2024-
            .replace("""\s+\d{4}\s*$""".toRegex(), "")                // Rimuove l'anno isolato alla fine
            .trim()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(request.data).document
        val homeResults = mutableListOf<SearchResponse>()

        // Selettore 1: Struttura dei blocchi in Home (uagb-post)
        document.select(".uagb-post__inner-wrap").forEach { element ->
            val titleEl = element.selectFirst(".uagb-post__title a")
            val rawTitle = titleEl?.text() ?: return@forEach
            val title = cleanTitle(rawTitle)
            val url = titleEl.attr("href")
            val poster = element.selectFirst(".uagb-post__image img")?.attr("src")

            // Logica originale basata sull'URL dell'elemento
            val type = if (url.contains("/film/")) TvType.Movie else TvType.TvSeries

            homeResults.add(
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                    this.type = type
                }
            )
        }

        // Selettore 2: Fallback struttura classica film (.movie)
        document.select(".movie").forEach { element ->
            val rawTitle = element.selectFirst("h2")?.text() ?: return@forEach
            val title = cleanTitle(rawTitle)
            val url = element.selectFirst(".imagen a")?.attr("href") ?: element.selectFirst("a")?.attr("href") ?: return@forEach
            val poster = element.selectFirst("img")?.attr("src")

            val type = if (url.contains("/film/")) TvType.Movie else TvType.TvSeries

            homeResults.add(
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                    this.type = type
                }
            )
        }

        return newHomePageResponse(request.name, homeResults.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val url = "$mainUrl/?s=$query"
        val response = app.get(url)
        val document = response.document

        // 1. Parsing dei risultati di ricerca (struttura .movie)
        document.select(".movie").forEach { element ->
            val rawTitle = element.selectFirst("h2")?.text() ?: return@forEach
            val title = cleanTitle(rawTitle)
            val targetUrl = element.selectFirst(".imagen a")?.attr("href") ?: element.selectFirst("a")?.attr("href") ?: return@forEach
            val poster = element.selectFirst("img")?.attr("src")

            val type = if (targetUrl.contains("/film/")) TvType.Movie else TvType.TvSeries

            results.add(
                newMovieSearchResponse(title, targetUrl, TvType.Movie) {
                    this.posterUrl = poster
                    this.type = type
                }
            )
        }

        // 2. Fallback per risultati strutturati come uagb-post
        document.select(".uagb-post__inner-wrap").forEach { element ->
            val titleEl = element.selectFirst(".uagb-post__title a")
            val rawTitle = titleEl?.text() ?: return@forEach
            val title = cleanTitle(rawTitle)
            val targetUrl = titleEl.attr("href")
            val poster = element.selectFirst(".uagb-post__image img")?.attr("src")

            val type = if (targetUrl.contains("/film/")) TvType.Movie else TvType.TvSeries

            results.add(
                newMovieSearchResponse(title, targetUrl, TvType.Movie) {
                    this.posterUrl = poster
                    this.type = type
                }
            )
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("h1")?.text() ?: document.selectFirst(".uagb-post__title")?.text() ?: "Nessun Titolo"
        val title = cleanTitle(rawTitle)

        val poster = document.selectFirst(".imagen img")?.attr("src") ?: document.selectFirst(".uagb-post__image img")?.attr("src")
        val description = document.selectFirst(".wp-block-post-content p")?.text() ?: document.selectFirst(".entry-content p")?.text()

        // Logica originale intatta per caricare le puntate
        return if (!url.contains("/film/")) {
            val episodesList = mutableListOf<Episode>()

            document.select(".wp-block-columns").forEach { column ->
                val seasonTitle = column.selectFirst("h3")?.text() ?: "Stagione 1"
                val season = seasonTitle.filter { it.isDigit() }.toIntOrNull() ?: 1

                column.select("a").forEachIndexed { index, element ->
                    val episodeUrl = element.attr("href")
                    val episode = index + 1
                    episodesList.add(
                        Episode(
                            data = episodeUrl,
                            name = "Episodio $episode",
                            season = season,
                            episode = episode
                        )
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains("/film/")) {
            val document = app.get(data).document
            document.select("a").forEach { element ->
                val link = element.attr("href")
                if (link.contains("uprot") || link.contains("stream") || link.contains("tape") || link.contains("flexy")) {
                    loadExtractor(link, mainUrl, subtitleCallback, callback)
                }
            }
        } else {
            loadExtractor(data, mainUrl, subtitleCallback, callback)
        }
        return true
    }
}
