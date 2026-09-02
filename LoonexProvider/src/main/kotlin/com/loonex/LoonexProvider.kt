package com.loonex

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder
import java.net.URI

class LoonexProvider : MainAPI() {

    override var mainUrl = "https://loonex.eu"
    override var name = "Loonex"
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

        val episodes = doc.select(".episode-row")
            .mapNotNull { row ->

                val label = row.attr("data-ep-label").trim()

                val playUrl = row
                    .selectFirst("a.btn-play-sm")
                    ?.attr("href")
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val seasonName = row
                    .attr("data-season")
                    .trim()

                val numbers = Regex(
                    """(\d+)x(\d+)"""
                ).find(label)

                val season = numbers
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

                val episode = numbers
                    ?.groupValues
                    ?.getOrNull(2)
                    ?.toIntOrNull()

                newEpisode(fixUrl(playUrl)) {
                    name = label
                    this.season = season
                    this.episode = episode

                    if (seasonName.isNotBlank()) {
                        description = seasonName
                    }
                }
            }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.Cartoon,
            episodes
        ) {
            posterUrl = poster
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
