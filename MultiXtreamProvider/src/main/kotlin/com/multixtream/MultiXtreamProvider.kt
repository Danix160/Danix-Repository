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

    // Icone per categoria
    private val categoryIcons = mapOf(
        "Cinema" to "https://upload.wikimedia.org/wikipedia/commons/5/5e/Sky_Cinema_-_Logo_2020.svg",
        "Sport" to "https://upload.wikimedia.org/wikipedia/commons/3/3c/Sky_Sport_-_Logo_2020.svg",
        "DAZN" to "https://upload.wikimedia.org/wikipedia/commons/2/20/DAZN_logo.svg",
        "Italia" to "https://upload.wikimedia.org/wikipedia/commons/1/1e/Rai_-_Logo_2016.svg",
        "News" to "https://upload.wikimedia.org/wikipedia/commons/5/5c/Sky_TG24_-_Logo_2018.svg",
        "Intrattenimento" to "https://upload.wikimedia.org/wikipedia/commons/3/3e/Mediaset_Infinity_logo.svg",
        "Kids" to "https://upload.wikimedia.org/wikipedia/commons/8/80/Cartoon_Network_2010_logo.svg",
        "Documentari" to "https://upload.wikimedia.org/wikipedia/commons/6/6b/National_Geographic_Channel.svg",
        "Musica" to "https://upload.wikimedia.org/wikipedia/commons/0/0f/MTV_2021_%28brand_version%29.svg",
        "Altro" to "https://i.imgur.com/7QFQpQp.png"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val lists = mutableListOf<HomePageList>()

        for (srv in servers) {

            val m3u = app.get(srv.url).body.string()
            val lines = m3u.lines()

            var pendingName = ""
            var pendingLogo = ""
            var pendingGroup = ""

            val channels = mutableListOf<LiveSearchResponse>()

            for (line in lines) {

                // --- EXTINF ---
                if (line.startsWith("#EXTINF")) {
                    pendingLogo = Regex("""tvg-logo="(.*?)"""").find(line)?.groupValues?.get(1) ?: ""
                    pendingGroup = Regex("""group-title="(.*?)"""").find(line)?.groupValues?.get(1) ?: "Altro"
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

                    // Scegli logo: originale → icona categoria → fallback
                    val finalLogo = when {
                        pendingLogo.isNotBlank() -> pendingLogo
                        categoryIcons.containsKey(pendingGroup) -> categoryIcons[pendingGroup]
                        else -> categoryIcons["Altro"]
                    }

                    channels += newLiveSearchResponse("${pendingName} [${pendingGroup}]", stream, TvType.Live) {
                        this.posterUrl = finalLogo
                    }

                    // reset EXTINF
                    pendingName = ""
                    pendingLogo = ""
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
