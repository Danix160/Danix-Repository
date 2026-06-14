package com.multixtream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class MultiXtreamProvider : MainAPI() {

    override var name = "Multi Xtream"
    override var mainUrl = "http://localhost"
    override var lang = "it"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    data class XtreamServer(val name: String, val url: String)

    private val servers = listOf(
        XtreamServer(
            "Server 1",
            "http://kuku2018.ddns.net:25461/get.php?username=danifonta01&password=rJ9G2kw8yF&type=m3u_plus&output=m3u8"
        )
    )

    // HOME → mostra i server
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = servers.map { srv ->
            newLiveSearchResponse(srv.name, srv.url, TvType.Live)
        }

        return newHomePageResponse(
            listOf(HomePageList("Server Xtream", items)),
            false
        )
    }

    // LOAD(server) → pagina fittizia
    override suspend fun load(url: String): LoadResponse {
        return newLiveStreamLoadResponse("Xtream Server", url, url)
    }

    // SEARCH(server-url) → categorie + canali
    override suspend fun search(query: String): List<SearchResponse> {
        val m3u = app.get(query).text

        val channels = mutableListOf<SearchResponse>()

        val lines = m3u.lines()
        var name = ""
        var logo = ""
        var group = ""

        for (i in lines.indices) {
            val line = lines[i]

            if (line.startsWith("#EXTINF")) {
                logo = Regex("""tvg-logo="(.*?)"""").find(line)?.groupValues?.get(1) ?: ""
                group = Regex("""group-title="(.*?)"""").find(line)?.groupValues?.get(1) ?: "Altro"
                name = line.substringAfter(",").trim()
            }

            if (line.startsWith("http")) {
                val stream = line.trim()

                channels += newLiveSearchResponse("$name [$group]", stream, TvType.Live) {
                    this.posterUrl = logo
                }
            }
        }

        return channels
    }

    // STREAM DIRETTO
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        callback(
            newExtractorLink(
                source = name,
                name = "Xtream",
                url = data,
                type = ExtractorLinkType.M3U8
            )
        )

        return true
    }
}
