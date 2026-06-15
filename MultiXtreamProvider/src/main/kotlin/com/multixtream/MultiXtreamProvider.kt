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
                        this.posterUrl = defaultIcon
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
    /////                EPG PARSER + DEBUG                //////
    /////////////////////////////////////////////////////////////

    fun normalize(name: String): String {
        val n = name
            .lowercase()
            .replace(" hd", "")
            .replace(" sd", "")
            .replace("hd", "")
            .replace("sd", "")
            .replace("+1", "")
            .replace("[^a-z0-9]".toRegex(), "")
            .trim()

        println("NORMALIZE: '$name' → '$n'")
        return n
    }

    suspend fun loadXmlTv(url: String): XmlTvData {
        val xml = app.get(url).text
        println("EPG LOADED: ${xml.length} chars")

        val channels = Regex("<channel id=\"(.*?)\">[\\s\\S]*?<display-name[^>]*>(.*?)</display-name>")
            .findAll(xml)
            .associate { match ->
                val id = match.groupValues[1]
                val displayName = match.groupValues[2]
                println("EPG CHANNEL: id='$id' display='$displayName'")
                id to displayName
            }

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

        println("EPG PROGRAMMES TOTAL: ${programmes.size}")

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

    /////////////////////////////////////////////////////////////
    /////            TIMEZONE FIX + DEBUG                 //////
    /////////////////////////////////////////////////////////////

    fun parseEpgTime(t: String): Long {
    val utc = java.text.SimpleDateFormat("yyyyMMddHHmmss Z")
    utc.timeZone = java.util.TimeZone.getTimeZone("UTC")

    val date = utc.parse(t)
    if (date == null) {
        println("DEBUG: Failed to parse time '$t'")
        return 0L
    }

    val localCal = java.util.Calendar.getInstance()
    localCal.time = date

    val result = localCal.timeInMillis

    println("DEBUG: PARSE TIME '$t' → UTC=${date} → LOCAL=${java.util.Date(result)}")

    return result
}

   fun getCurrentProgramme(channelName: String, epg: XmlTvData): String {

    val normalized = normalize(channelName)
    println("DEBUG: Looking for channel '$channelName' normalized='$normalized'")

    val entry = epg.channels.entries.find {
        normalize(it.value) == normalized
    }

    if (entry == null) {
        println("DEBUG: No match found for '$channelName'")
        return ""
    }

    val channelId = entry.key
    println("DEBUG: MATCH FOUND → channelId='$channelId' display='${entry.value}'")

    val now = System.currentTimeMillis()
    println("DEBUG: NOW = $now (${java.util.Date(now)})")

    val programmesForChannel = epg.programmes.filter { it.channel == channelId }
    println("DEBUG: Programmes for channel '$channelId' = ${programmesForChannel.size}")

    programmesForChannel.forEach { p ->
        println("DEBUG: PROGRAMME '${p.title}' | ${p.start} → ${p.stop}")
    }

    val current = programmesForChannel.find { p ->
        val start = parseEpgTime(p.start)
        val stop = parseEpgTime(p.stop)
        println("DEBUG: Checking '${p.title}' | start=$start (${java.util.Date(start)}) stop=$stop (${java.util.Date(stop)})")
        start <= now && stop > now
    }

    if (current == null) {
        println("DEBUG: No current programme found for '$channelName'")
        return ""
    }

    println("DEBUG: CURRENT PROGRAMME FOUND → ${current.title}")

    return current.title
}


    /////////////////////////////////////////////////////////////
    /////            LOAD (EPG QUI) + DEBUG               //////
    /////////////////////////////////////////////////////////////

    override suspend fun load(url: String): LoadResponse {

        println("LOAD REQUEST: $url")

        val epg = loadXmlTv(
            "https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz"
        )

        val realUrl = url.substringBefore("|||")
        val channelName = url.substringAfter("|||")

        println("CHANNEL NAME FROM URL: '$channelName'")

        val epgNow = getCurrentProgramme(channelName, epg)

        val title = if (epgNow.isNotBlank())
            "$channelName — $epgNow"
        else
            channelName

        println("FINAL TITLE: $title")
        println("FINAL PLOT: ${epgNow.ifBlank { "Nessuna informazione EPG disponibile" }}")

        return newLiveStreamLoadResponse(
            title,
            realUrl,
            realUrl
        ) {
            this.plot = epgNow.ifBlank { "Nessuna informazione EPG disponibile" }
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
