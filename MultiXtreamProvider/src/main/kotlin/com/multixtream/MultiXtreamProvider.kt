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

    // HOME → categorie + canali
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val lists = mutableListOf<HomePageList>()

        for (srv in servers) {

            val m3u = app.get(srv.url).text

            val lines = m3u.lines()
            var name = ""
            var logo = ""
            var group = ""

            val channels = mutableListOf<LiveSearchResponse>()

            for (i in lines.indices) {
                val line = lines[i]

                if (line.startsWith("#EXTINF")) {
                    logo = Regex("""tvg-logo="(.*?)"""").find(line)?.groupValues?.get(1) ?: ""
                    group = Regex("""group-title="(.*?)"""").find(line)?.groupValues?.get(1) ?: "Altro"
                    name = line.substringAfter(",").trim()
                }

                if (line.startsWith("http")) {
                    val stream = line.trim()

                    channels += newLiveSearchResponse(name, stream, TvType.Live) {
                        this.posterUrl = logo
                    }
                }
            }

            // Raggruppa per categoria
            val grouped = channels.groupBy {
                it.name.substringAfterLast("[", "").substringBefore("]").trim()
                    .ifEmpty { "Altro" }
            }

            // Aggiungi ogni categoria alla Home
            grouped.forEach { (cat, list) ->
                lists += HomePageList("$cat - ${srv.name}", list)
            }
        }

        return newHomePageResponse(lists, false)
    }

    // LOAD(server) → pagina fittizia
    override suspend fun load(url: String): LoadResponse {
        return newLiveStreamLoadResponse("Xtream", url, url)
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
