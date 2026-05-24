package com.lagradost.cloudstream3.utils.videoskip

import com.lagradost.cloudstream3.app

class IntroDbSkip : VideoSkipApi {

    override val name = "IntroDb"

    override suspend fun getSkips(imdbId: String, season: Int?, episode: Int?): VideoSkipResult? {
        if (season == null || episode == null) return null

        val url =
            "https://api.introdb.app/segments?imdb_id=$imdbId&season=$season&episode=$episode"

        val json = app.get(url).parsedSafe<Map<String, Any>>() ?: return null

        val segments = mutableListOf<VideoSkipSegment>()

        val intro = json["intro"] as? Map<String, Any>
        if (intro != null) {
            segments.add(
                VideoSkipSegment(
                    type = "intro",
                    startMs = ((intro["start_ms"] as Number).toLong()),
                    endMs = ((intro["end_ms"] as Number).toLong())
                )
            )
        }

        val outro = json["outro"] as? Map<String, Any>
        if (outro != null) {
            segments.add(
                VideoSkipSegment(
                    type = "outro",
                    startMs = ((outro["start_ms"] as Number).toLong()),
                    endMs = ((outro["end_ms"] as Number).toLong())
                )
            )
        }

        return VideoSkipResult(
            imdbId = imdbId,
            season = season,
            episode = episode,
            segments = segments
        )
    }
}
