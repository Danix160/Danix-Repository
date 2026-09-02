package com.loonex

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URLDecoder
import java.net.URI

class LoonexProvider : MainAPI() {

    override var mainUrl = "https://loonex.eu"
    override var name = "Loonex"
    override var lang = "it"
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.Anime,
        TvType.TvSeries
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/cartoni/" to "Cartoni"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page <= 1) {
            request.data
        } else {
            "${request.data}?page=$page"
        }

        val doc = app.get(
            url,
            headers = headers
        ).document

        val items = doc.select("""a[href*="?cartone="]""")
            .mapNotNull { a ->

                val title = a.selectFirst(".card-title-cine")
                    ?.text()
                    ?.trim()
                    ?: return@mapNotNull null

                val href = a.attr("href")

                val poster = a.selectFirst("img.card-img-bg")
                    ?.attr("src")
                    ?.let(::fixUrl)

                newMovieSearchResponse(
                    title,
                    fixUrl(href),
                    TvType.Cartoon
                ) {
                    posterUrl = poster
                }
            }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val url = "$mainUrl/cartoni/?search=${java.net.URLEncoder.encode(query, "UTF-8")}"

        val doc = app.get(
            url,
            headers = headers
        ).document

        return doc.select("""a[href*="?cartone="]""")
            .mapNotNull { a ->

                val title = a.selectFirst(".card-title-cine")
                    ?.text()
                    ?.trim()
                    ?: return@mapNotNull null

                val href = a.attr("href")

                val poster = a.selectFirst("img.card-img-bg")
                    ?.attr("src")
                    ?.let(::fixUrl)

                newMovieSearchResponse(
                    title,
                    fixUrl(href),
                    TvType.Cartoon
                ) {
                    posterUrl = poster
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {

    val doc = app.get(
        url,
        headers = headers
    ).document

    val html = doc.toString()

    val title = Regex(
        """"title"\s*:\s*"([^"]+)""""
    ).find(html)
        ?.groupValues
        ?.get(1)
        ?.replace("\\/", "/")
        ?: doc.selectFirst("h1,h2,.cartoon-title")
            ?.text()
            ?.trim()
        ?: "Loonex"

    val poster = Regex(
        """"image"\s*:\s*"([^"]+)""""
    ).find(html)
        ?.groupValues
        ?.get(1)
        ?.replace("\\/", "/")
        ?.let(::fixUrl)

    val episodes = mutableListOf<Episode>()
    val seasonsData = mutableListOf<SeasonData>()

    val seasonButtons = doc.select(
    """#season-tabs button[data-bs-target][data-season-name]"""
)

seasonButtons.forEachIndexed { tabIndex, button ->

    /*
     * Ogni TAB Loonex diventa una "stagione"
     * nel selettore Cloudstream.
     */
    val cloudSeason = tabIndex + 1

    val tabName = button
        .attr("data-season-name")
        .trim()
        .ifBlank {
            "Parte $cloudSeason"
        }

    val targetId = button
        .attr("data-bs-target")
        .trim()
        .removePrefix("#")

    if (targetId.isBlank()) {
        return@forEachIndexed
    }

    val tabContainer = doc.getElementById(targetId)
        ?: return@forEachIndexed

    /*
     * Il nome visualizzato nel selettore Cloudstream
     * è esattamente il nome del TAB Loonex.
     */
    seasonsData.add(
        SeasonData(
            cloudSeason,
            tabName
        )
    )

    /*
     * Prendiamo TUTTI gli episodi presenti
     * esclusivamente dentro questo tab.
     */
    val rows = tabContainer.select(".episode-row")

    rows.forEachIndexed episodeLoop@ { index, row ->

        val label = row
            .attr("data-ep-label")
            .trim()

        val playUrl = row
            .selectFirst("a.btn-play-sm[href]")
            ?.attr("href")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: return@episodeLoop

        /*
         * Recuperiamo la numerazione originale:
         *
         * 1x01
         * 2x04
         * 3x12
         *
         * Serve per il NOME, non per il
         * raggruppamento Cloudstream.
         */
        val xMatch = Regex(
            """(?i)(\d+)\s*[x×]\s*0*(\d+)"""
        ).find(label)

        val originalSeason = xMatch
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val originalEpisode = xMatch
            ?.groupValues
            ?.getOrNull(2)
            ?.toIntOrNull()

        /*
         * Cloudstream deve avere episodi progressivi
         * all'interno del TAB.
         *
         * Questo evita collisioni:
         *
         * 1x01
         * 2x01
         * 3x01
         *
         * non possono essere tutti episode = 1.
         */
        val cloudEpisode = index + 1

        val displayName =
            if (
                originalSeason != null &&
                originalEpisode != null
            ) {
                "%02dx%02d".format(
                    originalSeason,
                    originalEpisode
                )
            } else {
                label.ifBlank {
                    "Episodio $cloudEpisode"
                }
            }

        episodes.add(
            newEpisode(
                fixUrl(playUrl)
            ) {
                /*
                 * Il TAB decide il raggruppamento.
                 */
                this.season = cloudSeason

                /*
                 * Progressivo dentro quel tab.
                 */
                this.episode = cloudEpisode

                /*
                 * Manteniamo la numerazione originale
                 * visibile all'utente.
                 *
                 * Es:
                 * 01x01
                 * 01x02
                 * 02x01
                 */
                this.name = displayName
            }
        )
    }
}

    /*
     * Fallback per eventuali pagine Loonex
     * che non usano i tab delle stagioni.
     */
    if (episodes.isEmpty()) {

        doc.select(".episode-row")
            .forEachIndexed { index, row ->

                val label = row
                    .attr("data-ep-label")
                    .trim()

                val playUrl = row
                    .selectFirst("a.btn-play-sm")
                    ?.attr("href")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@forEachIndexed

                val numbers = Regex(
                    """(?i)(\d+)\s*[x×]\s*0*(\d+)"""
                ).find(label)

                val seasonNumber =
                    numbers
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: 1

                val episodeNumber =
                    numbers
                        ?.groupValues
                        ?.getOrNull(2)
                        ?.toIntOrNull()
                        ?: (index + 1)

                if (
                    seasonsData.none {
                        it.season == seasonNumber
                    }
                ) {
                    seasonsData.add(
                        SeasonData(
                            seasonNumber,
                            "Stagione $seasonNumber"
                        )
                    )
                }

                episodes.add(
                    newEpisode(
                        fixUrl(playUrl)
                    ) {
                        this.name = label.ifBlank {
                            "Episodio $episodeNumber"
                        }

                        this.season = seasonNumber
                        this.episode = episodeNumber
                    }
                )
            }
    }

    return newTvSeriesLoadResponse(
        title,
        url,
        TvType.Cartoon,
        episodes
    ) {
        posterUrl = poster

        addSeasonNames(seasonsData)
    }
}
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val response = app.get(
            data,
            headers = headers,
            referer = "$mainUrl/"
        )

        val html = response.text

        val encoded = Regex(
            """var\s+encodedStr\s*=\s*["']([^"']+)["']"""
        ).find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: return false

        val key = Regex(
            """var\s+decryptionKey\s*=\s*["']([^"']+)["']"""
        ).find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: return false

        val decoded = decryptLoonexUrl(
            encoded,
            key
        )

        if (decoded.isBlank()) {
            return false
        }

        val videoUrl = encodeUrlPath(decoded)

        callback(
            newExtractorLink(
                source = "Loonex",
                name = "Loonex",
                url = videoUrl,
                type = if (videoUrl.contains(".m3u8", true)) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
            ) {
                referer = "$mainUrl/"
            }
        )

        return true
    }

    private fun decryptLoonexUrl(
        hex: String,
        key: String
    ): String {

        if (key.isBlank()) return ""

        val decoded = buildString {

            var i = 0

            while (i + 1 < hex.length) {

                val value = hex
                    .substring(i, i + 2)
                    .toIntOrNull(16)
                    ?: break

                val keyChar = key[
                    (i / 2) % key.length
                ].code

                append(
                    (value xor keyChar).toChar()
                )

                i += 2
            }
        }

        return try {
            URLDecoder.decode(
                decoded,
                "UTF-8"
            )
        } catch (_: Exception) {
            decoded
        }
    }

    private fun encodeUrlPath(url: String): String {
        return try {

            val uri = URI(url)

            URI(
                uri.scheme,
                uri.userInfo,
                uri.host,
                uri.port,
                uri.path,
                uri.query,
                uri.fragment
            ).toASCIIString()

        } catch (_: Exception) {
            url
                .replace(" ", "%20")
                .replace("[", "%5B")
                .replace("]", "%5D")
        }
    }

    private fun fixUrl(url: String): String {

        if (url.startsWith("http")) {
            return url
        }

        if (url.startsWith("//")) {
            return "https:$url"
        }

        return if (url.startsWith("/")) {
            "$mainUrl$url"
        } else {
            "$mainUrl/cartoni/$url"
        }
    }
}
