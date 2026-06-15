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

    private val defaultIcon =
        "https://raw.githubusercontent.com/Danix160/Danix-Repository/refs/heads/master/MultiXtreamProvider/src/main/kotlin/com/multixtream/images.jpg"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val lists = mutableListOf<HomePageList>()

        for (srv in servers) {

            val m3u = app.get(srv.url).body.string()
            val lines = m3u.lines()

            var pendingName = ""
            var pendingGroup = ""

            // Mappa categoria → lista canali
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

                    val channel = newLiveSearchResponse(
                        pendingName,
                        stream,
                        TvType.Live
                    ) {
                        this.posterUrl = defaultIcon
                        this.data = pendingName   // 🔥 salva il nome del canale
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

    /////////////////////////////////////////////////////////////
    /////                EPG PARSER                        ///////
    /////////////////////////////////////////////////////////////

    suspend fun loadXmlTv(url: String): XmlTvData {
        val xml = app.get(url).text

        val channels = Regex("<channel id=\"(.*?)\">[\\s\\S]*?<display-name>(.*?)</display-name>")
            .findAll(xml)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toMap()

        val programmes = Regex(
            "<programme start=\"(.*?)\" stop=\"(.*?)\" channel=\"(.*?)\">[\\s\\S]*?<title>(.*?)</title>"
        ).findAll(xml).map {
            Programme(
                start = it.groupValues[1],
                stop = it.groupValues[2],
                channel = it.groupValues[3],
                title = it.groupValues[4]
            )
        }.toList()

        return XmlTvData(channels, programmes)
    }

    data class XmlTvData(
        val channels: Map<String, String>,
        val programmes: List<Programme>
    )

    data class Programme(
        val start: String,
        val stop: String,
        val channel: String,
        val title: String
    )

    fun getCurrentProgramme(channelName: String, epg: XmlTvData): String {
        val channelId = epg.channels.entries.find {
            it.value.equals(channelName, ignoreCase = true)
        }?.key ?: return ""

        val now = System.currentTimeMillis()

        val current = epg.programmes.find { p ->
            p.channel == channelId &&
            parseEpgTime(p.start) <= now &&
            parseEpgTime(p.stop) > now
        }

        return current?.title ?: ""
    }

    fun parseEpgTime(t: String): Long {
        val sdf = java.text.SimpleDateFormat("yyyyMMddHHmmss Z")
        return sdf.parse(t)?.time ?: 0L
    }

    /////////////////////////////////////////////////////////////
    /////            LOAD (EPG QUI)                      ///////
    /////////////////////////////////////////////////////////////

    override suspend fun load(url: String): LoadResponse {

        val epg = loadXmlTv(
            "https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz"
        )

        // 🔥 Recupera il nome salvato nella Home
        val channelName = data as? String ?: "Canale"

        val epgNow = getCurrentProgramme(channelName, epg)

        val title = if (epgNow.isNotBlank())
            "$channelName — $epgNow"
        else
            channelName

        return newLiveStreamLoadResponse(
            title,
            url,
            url
        )
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
