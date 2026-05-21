package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor 
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

    private fun cleanTitle(title: String): String {
        val isSubIta = title.contains("(?i)\\bSUB[- ]?ITA\\b".toRegex())

        var cleaned = title
            .replace(" in streaming - OnlineSerieTv", "")
            .replace("(?i)\\bSUB[- ]?ITA\\b".toRegex(), "")
            .replace("(?i)\\b(ITA|STAGIONE \\d+|STAGIONE)\\b".toRegex(), "")
            .replace("""\s*[\(\[-]?\s*(19|20)\d{2}\s*[\)\]-]?\s*""".toRegex(), " ")
            .replace("""\s*[-–—:|]+\s*$""".toRegex(), "") 
            .replace("""^\s*[-–—:|]+\s*""".toRegex(), "")
            .replace("""\s+""".toRegex(), " ")
            .trim()

        if (isSubIta) {
            cleaned = "$cleaned SUB ITA"
        }

        return cleaned
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(request.data).document
        val homeResults = mutableListOf<SearchResponse>()

        document.select(".uagb-post__inner-wrap").forEach { element ->
            val titleEl = element.selectFirst(".uagb-post__title a")
            val rawTitle = titleEl?.text() ?: return@forEach
            val title = cleanTitle(rawTitle)
            val url = titleEl.attr("href")
            val poster = element.selectFirst(".uagb-post__image img")?.attr("src")
            val type = if (url.contains("/film/")) TvType.Movie else TvType.TvSeries

            homeResults.add(
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                    this.type = type
                }
            )
        }

        document.select(".movie").forEach { element ->
            val rawTitle = element.selectFirst("h2")?.text() ?: return@forEach
            val title = cleanTitle(rawTitle)
            val linkEl = element.selectFirst(".imagen a") ?: element.selectFirst("a")
            val url = linkEl?.attr("href") ?: return@forEach
            val poster = element.selectFirst("img")?.attr("src")
            val type = if (url.contains("/film/")) TvType.Movie else TvType.TvSeries

            homeResults.add(
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                    this.type = type
                }
            )
        }

        return newHomePageResponse(request.name, homeResults)
    }

   override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val maxPagesToSearch = 5

        for (page in 1..maxPagesToSearch) {
            try {
                val url = if (page == 1) "$mainUrl/?s=$query" else "$mainUrl/page/$page/?s=$query"
                val response = app.get(url)
                if (response.code != 200) break
                
                val document = response.document
                val initialCount = results.size

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

                if (results.size == initialCount) break
            } catch (e: Exception) {
                break
            }
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val rawTitle = document.selectFirst("h1")?.text() ?: "Senza Titolo"
        val title = cleanTitle(rawTitle)
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")

        return if (url.contains("/serietv/")) {
            val episodesList = mutableListOf<Episode>()
            var currentSeason = 1
            
            // Analisi righe tabella per stagioni e link MaxStream (msf)
            document.select("table tr").forEach { row ->
                // Check cambio stagione
                val header = row.selectFirst("td[colspan=4] b")
                if (header != null) {
                    val seasonMatch = "Stagione (\\d+)".toRegex().find(header.text())
                    if (seasonMatch != null) {
                        currentSeason = seasonMatch.groupValues[1].toIntOrNull() ?: 1
                    }
                }

                // Cerca link MaxStream (msf)
                val maxStreamLink = row.select("a[href*=/msf/]").firstOrNull()
                if (maxStreamLink != null) {
                    val fullText = row.selectFirst("td")?.text() ?: ""
                    val epMatch = "(\\d+)x(\\d+)".toRegex().find(fullText)
                    val episodeNumber = epMatch?.groupValues?.get(2)?.toIntOrNull() ?: (episodesList.size + 1)

                    episodesList.add(newEpisode(maxStreamLink.attr("href")) {
                        this.name = "Episodio $episodeNumber"
                        this.season = currentSeason
                        this.episode = episodeNumber
                        this.posterUrl = poster
                    })
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
