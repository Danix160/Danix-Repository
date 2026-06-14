package com.multixtream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

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

    // LOAD(server) → mostra tutti i canali (senza categorie)
    override suspend fun load(url: String): LoadResponse {
        val m3u = app.get(url).text

        val channels = mutableListOf<LiveSearchResponse>()

        val lines = m3u.lines()
        var name = ""
        var logo = ""
        var stream = ""

        for (i in lines.indices) {
            val line = lines[i]

            if (line.startsWith("#EXTINF")) {
                logo = Regex("""tvg-logo="(.*?)"""").find(line)?.groupValues?.get(1) ?: ""
                name = line.substringAfter(",").trim()
            }

            if (line.startsWith("http")) {
                stream = line.trim()

                channels += newLiveSearchResponse(name, stream, TvType.Live) {
                    this.posterUrl = logo
                }
            }
        }

        return newHomePageResponse(
            listOf(HomePageList("Canali", channels)),
            false
        )
    }

    // LOAD LINKS → stream diretto
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
            ) {
                this.referer = mainUrl
                this.headers = mapOf("User-Agent" to USER_AGENT)
            }
        )

        return true
    }
}
