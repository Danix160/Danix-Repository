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

    // UNA SOLA IMMAGINE LOCALE PER TUTTI I CANALI
    private val defaultIcon =
        "https://raw.githubusercontent.com/Danix160/Danix-Repository/refs/heads/master/MultiXtreamProvider/src/main/kotlin/com/multixtream/images.jpg"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val lists = mutableListOf<HomePageList>()

        for (srv in servers) {

            val m3u = app.get(srv.url).body.string()
            val lines = m3u.lines()

            var pendingName = ""
            var pendingGroup = ""

            val channels = mutableListOf<LiveSearchResponse>()

            for (line in lines) {

                // --- EXTINF ---
                if (line.startsWith("#EXTINF")) {
                    pendingGroup = Regex("""group-title="(.*?)"""").find(line)?.groupValues?.get(1)
                        ?: "Altro"
                    pendingName = line.substringAfter(",").trim()
                    continue
                }

                // --- STREAM ---
                if (line.startsWith("http")) {
                    val stream = line.trim()

                    // SOLO LIVE REALI
                    if (!stream.contains("/live/")) continue
                    if (stream.contains("/movie/")) continue
                    if (stream.contains("/series/")) continue
                    if (pendingName.isBlank()) continue

                    channels += newLiveSearchResponse(
                        "${pendingName} [${pendingGroup}]",
                        stream,
                        TvType.Live
                    ) {
                        this.posterUrl = defaultIcon
                    }

                    pendingName = ""
                    pendingGroup = ""
                }
            }

            // --- RAGGRUPPA PER CATEGORIA ---
            val grouped = channels.groupBy { ch ->
                ch.name.substringAfterLast("[", "").substringBefore("]").trim()
                    .ifEmpty { "Altro" }
            }

            // --- CREA LISTE HOME ---
            grouped.forEach { (cat, list) ->
                lists += HomePageList("$cat - ${srv.name}", list)
            }
        }

        return newHomePageResponse(lists, false)
    }

    override suspend fun load(url: String): LoadResponse {
        return newLiveStreamLoadResponse("Xtream", url, url)
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
        ): Boolean {
        
            XtreamExtractor().getUrl(
                data,
                null,
                subtitleCallback,
                callback
            )
        
            return true
        }

}
