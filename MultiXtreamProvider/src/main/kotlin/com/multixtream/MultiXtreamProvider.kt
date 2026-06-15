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
    "sky cinema" to "https://raw.githubusercontent.com/Danix160/plugintest/master/icons/cinema.png",
    "cinema" to "https://raw.githubusercontent.com/Danix160/plugintest/master/icons/cinemam.png",
        "netflix" to "https://raw.githubusercontent.com/Danix160/plugintest/master/icons/netflix.png",
        "pluto" to "https://raw.githubusercontent.com/Danix160/plugintest/master/icons/pluto.png",
        "disney" to "https://raw.githubusercontent.com/Danix160/plugintest/master/icons/disney.jpg",
        "news" to "https://raw.githubusercontent.com/Danix160/plugintest/master/icons/news.png",
        "bein" to "https://raw.githubusercontent.com/Danix160/plugintest/master/icons/bein.png",
        "intra" to "https://raw.githubusercontent.com/Danix160/plugintest/master/icons/intra.png",
        "bambini" to "https://raw.githubusercontent.com/Danix160/plugintest/master/icons/bambini.png",
        "amazon" to "https://raw.githubusercontent.com/Danix160/plugintest/master/icons/amazon.png",
        "lba" to "https://raw.githubusercontent.com/Danix160/plugintest/master/icons/lba.png",
        "musica" to "https://raw.githubusercontent.com/Danix160/plugintest/master/icons/musica.png",
        "fratello" to "https://raw.githubusercontent.com/Danix160/plugintest/master/icons/fratello.png",
        "primafila" to "https://raw.githubusercontent.com/Danix160/plugintest/master/icons/primafila.png",
        "adulti" to "https://raw.githubusercontent.com/Danix160/plugintest/master/icons/adulti.jpg",
        "calcio" to "https://raw.githubusercontent.com/Danix160/plugintest/master/icons/calcio.jpg"
)
    
    private val defaultIcon =
        "https://raw.githubusercontent.com/Danix160/Danix-Repository/refs/heads/master/MultiXtreamProvider/src/main/kotlin/com/multixtream/images.jpg"
    
    fun getCategoryIcon(category: String): String {
    val cat = category.lowercase()

    return when {
        cat.contains("regionali") -> categoryIcons["news"]!!
        cat.contains("sky") && cat.contains("sport") -> categoryIcons["sky sport"]!!
        cat.contains("sky") && cat.contains("formula 1") -> categoryIcons["sky sport"]!!
        cat.contains("dazn") -> categoryIcons["dazn"]!!
        cat.contains("tivu") || cat.contains("sat") -> categoryIcons["tivù sat"]!!
        cat.contains("sky") && cat.contains("calcio") -> categoryIcons["sky sport"]!!
        cat.contains("sky") && cat.contains("cinema") -> categoryIcons["sky cinema"]!!
        cat.contains("sky") && cat.contains("motogp") -> categoryIcons["sky sport"]!!
        cat.contains("lega") && cat.contains("pro") -> categoryIcons["sky calcio"]!!
        cat.contains("netflix") -> categoryIcons["netflix"]!!
        cat.contains("pluto") -> categoryIcons["pluto"]!!
        cat.contains("attori") -> categoryIcons["cinema"]!!
        cat.contains("saghe") -> categoryIcons["cinema"]!!
        cat.contains("bambini") -> categoryIcons["bambini"]!!
        cat.contains("disney") -> categoryIcons["disney"]!!
        cat.contains("musica") -> categoryIcons["musica"]!!
        cat.contains("news") && cat.contains("tg") -> categoryIcons["news"]!!
        cat.contains("regioni") -> categoryIcons["news"]!!
        cat.contains("bein") && cat.contains("sports") -> categoryIcons["bein"]!!
        cat.contains("hotclub") && cat.contains("adulti") -> categoryIcons["adulti"]!!
        cat.contains("grande") && cat.contains("fratello") -> categoryIcons["fratello"]!!
        cat.contains("cinema") && cat.contains("hd") -> categoryIcons["cinema"]!!
        cat.contains("intrattenimento") -> categoryIcons["intra"]!!
        cat.contains("cultura") -> categoryIcons["intra"]!!
        cat.contains("serie") && cat.contains("b") -> categoryIcons["dazn"]!!
        cat.contains("lba") && cat.contains("basket") -> categoryIcons["lba"]!!
        cat.contains("amazon") && cat.contains("infinity") -> categoryIcons["amazon"]!!    
        cat.contains("sky") && cat.contains("primafila") -> categoryIcons["primafila"]!!
                    
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

        return newHomePageResponse(
        lists,
        false,
        "https://raw.githubusercontent.com/Danix160/plugintest/master/icons/banner.png"
    )

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
