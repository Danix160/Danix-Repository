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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val lists = mutableListOf<HomePageList>()

        for (srv in servers) {

            val m3u = app.get(srv.url).body.string()
            val lines = m3u.lines()

            var pendingName = ""
            var pendingLogo = ""
            var pendingGroup = ""

            val channels = mutableListOf<LiveSearchResponse>()
            val categoryLogos = mutableMapOf<String, String>() // fallback logo per categoria

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

                    // salva logo valido per categoria
                    if (pendingLogo.isNotBlank()) {
                        categoryLogos.putIfAbsent(pendingGroup, pendingLogo)
                    }

                    channels += newLiveSearchResponse("${pendingName} [${pendingGroup}]", stream, TvType.Live) {
                        this.posterUrl = pendingLogo // fallback dopo
                    }

                    // reset EXTINF
                    pendingName = ""
                    pendingLogo = ""
                    pendingGroup = ""
                }
            }

            // --- FALLBACK LOGO PER CATEGORIA ---
            channels.forEach { ch ->
                val cat = ch.name.substringAfterLast("[", "").substringBefore("]").trim()
                if (ch.posterUrl.isNullOrBlank()) {
                    ch.posterUrl = categoryLogos[cat] ?: "https://i.imgur.com/7QFQpQp.png"
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
