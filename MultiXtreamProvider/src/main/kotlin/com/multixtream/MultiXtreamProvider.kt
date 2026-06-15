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
    private val categoryIcons = mapOf(
    "sky sport" to "https://raw.githubusercontent.com/Danix160/plugintest/master/icons/sport.jpeg",
    "dazn" to "https://raw.githubusercontent.com/Danix160/plugintest/master/icons/dazn.png",
    "tivù sat" to "https://raw.githubusercontent.com/Danix160/plugintest/master/icons/tivusat.png",
    "sky calcio" to "https://raw.githubusercontent.com/Danix160/plugintest/master/icons/skycalcio.jpeg",
    "sky cinema" to "https://raw.githubusercontent.com/Danix160/plugintest/master/icons/cinema.png"
)
    
    private val defaultIcon =
        "https://raw.githubusercontent.com/Danix160/Danix-Repository/refs/heads/master/MultiXtreamProvider/src/main/kotlin/com/multixtream/images.jpg"
    
    fun getCategoryIcon(category: String): String {
    val cat = category.lowercase()

    return when {
        cat.contains("sky") && cat.contains("sport") -> categoryIcons["sky sport"]!!
        cat.contains("dazn") -> categoryIcons["dazn"]!!
        cat.contains("tivu") || cat.contains("sat") -> categoryIcons["tivù sat"]!!
        cat.contains("calcio") -> categoryIcons["sky calcio"]!!
        cat.contains("cinema") -> categoryIcons["sky cinema"]!!
        else -> defaultIcon
    }
}

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val lists = mutableListOf<HomePageList>()

        for (srv in servers) {

            val m3u = app.get(srv.url).body.string()
            val lines = m3u.lines()

            var pendingName = ""
            var pendingGroup = ""

            val grouped = mutableMapOf<String, MutableList<LiveSearchResponse>>()

            for (line in lines) {

                if (line.startsWith("#EXTINF")) {
                    pendingGroup = Regex("""group-title="(.*?)"""")
                        .find(line)?.groupValues?.get(1) ?: "Altro"

                    pendingName = line.substringAfter(",").trim()
                    continue
                }

                if (line.startsWith("http")) {
                    val stream = line.trim()

                    if (!stream.contains("/live/")) continue
                    if (stream.contains("/movie/")) continue
                    if (stream.contains("/series/")) continue
                    if (pendingName.isBlank()) continue

                    val encodedUrl = "$stream|||$pendingName"

                    val channel = newLiveSearchResponse(
                        pendingName,
                        encodedUrl,
                        TvType.Live
                    ) {
                        this.posterUrl = getCategoryIcon(pendingGroup)

                    }

                    grouped.getOrPut(pendingGroup) { mutableListOf() }.add(channel)

                    pendingName = ""
                    pendingGroup = ""
                }
            }

            grouped.forEach { (cat, list) ->
                lists += HomePageList("$cat - ${srv.name}", list)
            }
        }

        return newHomePageResponse(lists, false)
    }

    override suspend fun load(url: String): LoadResponse {

        val realUrl = url.substringBefore("|||")
        val channelName = url.substringAfter("|||")

        return newLiveStreamLoadResponse(
            channelName,
            realUrl,
            realUrl
        ) {
            this.plot = "Streaming Live"
        }
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
