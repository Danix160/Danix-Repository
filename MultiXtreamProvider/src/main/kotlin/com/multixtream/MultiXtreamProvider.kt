package com.xtream

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = servers.map { srv ->
            newLiveSearchResponse(srv.name, srv.url, TvType.Live)
        }

        return newHomePageResponse(
            listOf(HomePageList("Server Xtream", items)),
            hasNext = false
        )
    }

    override suspend fun load(url: String): LoadResponse {
        return newLiveStreamLoadResponse("Xtream", url, url)
    }

    override suspend fun getMainPageList(url: String): List<HomePageList> {
        val m3u = app.get(url).text

        val channels = mutableListOf<LiveSearchResponse>()

        val lines = m3u.lines()
        var name = ""
        var logo = ""
        var group = ""
        var streamId = ""

        val base = url.substringBefore("/get.php")
        val user = Regex("""username=([^&]+)""").find(url)?.groupValues?.get(1) ?: ""
        val pass = Regex("""password=([^&]+)""").find(url)?.groupValues?.get(1) ?: ""

        for (i in lines.indices) {
            val line = lines[i]

            if (line.startsWith("#EXTINF")) {
                logo = Regex("""tvg-logo="(.*?)"""").find(line)?.groupValues?.get(1) ?: ""
                group = Regex("""group-title="(.*?)"""").find(line)?.groupValues?.get(1) ?: "Altro"
                name = line.substringAfter(",").trim()
            }

            if (line.startsWith("http")) {
                val stream = line.trim()
                streamId = stream.substringAfterLast("/").substringBefore(".m3u8")

                val epgUrl =
                    "$base/player_api.php?username=$user&password=$pass&action=get_simple_data_table&stream_id=$streamId"

                val epgJson = try {
                    JSONObject(app.get(epgUrl).text)
                } catch (e: Exception) {
                    null
                }

                val epgText = epgJson?.optJSONArray("epg_listings")?.let { arr ->
                    if (arr.length() > 0) {
                        val first = arr.getJSONObject(0)
                        val title = first.optString("title")
                        val start = first.optString("start")
                        val end = first.optString("end")
                        val desc = first.optString("description")

                        "▶ $title\n🕒 $start → $end\n\n$desc"
                    } else null
                } ?: "Nessun EPG disponibile"

                channels += newLiveSearchResponse("$name [$group]", stream, TvType.Live) {
                    this.posterUrl = logo
                    this.plot = epgText
                }
            }
        }

        return channels.groupBy {
            it.name.substringAfterLast("[", "").substringBefore("]").trim()
        }.map { (cat, list) ->
            HomePageList(cat, list)
        }
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
            ) {
                this.referer = mainUrl
                this.headers = mapOf("User-Agent" to USER_AGENT)
            }
        )

        return true
    }
}
