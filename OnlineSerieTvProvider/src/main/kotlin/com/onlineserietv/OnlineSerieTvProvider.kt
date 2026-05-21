package com.onlineserietv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi.loadExtractor
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import java.util.regex.Pattern

class OnlineSerieTvProvider : MainAPI() {
    override var mainUrl = "https://onlineserietv.lol"
    override var name = "OnlineSerieTv"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    data class EpisodeData(
        val videoLinks: List<String>
    )

    // 1. GESTIONE HOME PAGE
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val homePages = mutableListOf<HomePageList>()

        val seriesElements = document.select("div.wp-block-uagb-post-grid article.uagb-post__inner-wrap")
            .filter { it.selectFirst("h3.uagb-post__title a")?.attr("href")?.contains("/serietv/") == true }
        
        val seriesList = seriesElements.mapNotNull { element ->
            element.toHomeSearchResult(TvType.TvSeries)
        }
        if (seriesList.isNotEmpty()) {
            homePages.add(HomePageList("Ultime Serie Tv aggiunte o aggiornate", seriesList))
        }

        val moviesElements = document.select("div.wp-block-uagb-post-grid article.uagb-post__inner-wrap")
            .filter { it.selectFirst("h3.uagb-post__title a")?.attr("href")?.contains("/film/") == true }

        val moviesList = moviesElements.mapNotNull { element ->
            element.toHomeSearchResult(TvType.Movie)
        }
        if (moviesList.isNotEmpty()) {
            homePages.add(HomePageList("Ultimi Film aggiunti", moviesList))
        }

        return newHomePageResponse(homePages, hasNext = false)
    }

    private fun Element.toHomeSearchResult(type: TvType): SearchResponse? {
        val titleElement = this.selectFirst("h3.uagb-post__title a") ?: return null
        val title = titleElement.text().trim()
        val url = titleElement.attr("href")
        val poster = this.selectFirst("div.uagb-post__image img")?.attr("src") ?: ""

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    // 2. GESTIONE DELLA RICERCA
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document
        
        return document.select("div#box_movies div.movie").mapNotNull { element ->
            val linkElement = element.selectFirst("div.imagen a") ?: return@mapNotNull null
            val url = linkElement.attr("href")
            val title = element.selectFirst("h2")?.text()?.trim() ?: ""
            val poster = element.selectFirst("div.imagen img")?.attr("src") ?: ""
            
            val type = if (url.contains("/serietv/")) TvType.TvSeries else TvType.Movie

            if (type == TvType.Movie) {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
        }
    }

    // 3. CARICAMENTO DEI METADATI
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.replace(" in streaming - OnlineSerieTv", "")?.trim() 
            ?: document.selectFirst("h2")?.text()?.trim() 
            ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        
        val description = document.selectFirst("b:contains(Trama)")?.nextElementSibling()?.text()?.trim()
            ?: document.selectFirst("div.tsll p")?.text()?.trim()
            ?: document.select("div.entry-content p").firstOrNull { it.text().contains("Trama") || it.text().length > 50 }?.text()?.trim()

        val episodeTable = document.selectFirst("table")
        
        if (episodeTable != null) {
            val episodesList = mutableListOf<Episode>()
            val rows = episodeTable.select("tr")

            for (row in rows) {
                val cols = row.select("td")
                if (cols.isEmpty()) continue

                val epText = cols[0].text().trim()
                val matcher = Pattern.compile("(\\d+)x(\\d+)").matcher(epText)
                if (matcher.find()) {
                    val seasonNumber = matcher.group(1).toIntOrNull() ?: 1
                    val episodeNumber = matcher.group(2).toIntOrNull() ?: 1

                    val links = cols.drop(1).mapNotNull { col ->
                        col.selectFirst("a")?.attr("href")
                    }.filter { it.isNotEmpty() }

                    if (links.isNotEmpty()) {
                        episodesList.add(newEpisode(EpisodeData(links).toJson()) {
                            this.name = epText
                            this.season = seasonNumber
                            this.episode = episodeNumber
                        })
                    }
                }
            }

            return newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.TvSeries,
                episodes = episodesList
            ) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val entryContent = document.selectFirst("div.entry-content") ?: document.body()
            val movieLinks = entryContent.select("a").mapNotNull { element ->
                val href = element.attr("href")
                if (href.isNotEmpty() && !href.contains(mainUrl) && !href.contains("facebook") && !href.contains("twitter")) {
                    href
                } else null
            }.distinct()

            if (movieLinks.isEmpty()) return null

            return newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = EpisodeData(movieLinks).toJson()
            ) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    // 4. ESTRAZIONE FINALE DEI LINK VIDEO
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeData = parseJson<EpisodeData>(data)
        
        for (link in episodeData.videoLinks) {
            loadExtractor(link, subtitleCallback, callback)
        }
        return true
    }
}
