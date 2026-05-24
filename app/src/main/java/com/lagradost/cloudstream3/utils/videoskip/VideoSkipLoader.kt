package com.lagradost.cloudstream3.utils.videoskip

object VideoSkipLoader {

    val apis: List<VideoSkipApi> = listOf(
        IntroDbSkip()
        // Se vuoi aggiungere AniSkip, basta aggiungerlo qui
        // AniSkip()
    )

    suspend fun load(imdbId: String, season: Int?, episode: Int?): List<VideoSkipSegment> {
        val result = mutableListOf<VideoSkipSegment>()

        for (api in apis) {
            try {
                val res = api.getSkips(imdbId, season, episode)
                if (res != null) {
                    result.addAll(res.segments)
                }
            } catch (e: Exception) {
                println("VideoSkipLoader ERROR: ${e.message}")
            }
        }

        return result
    }
}
