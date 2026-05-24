package com.lagradost.cloudstream3.utils.videoskip

data class VideoSkipSegment(
    val type: String,
    val startMs: Long,
    val endMs: Long
)

data class VideoSkipResult(
    val imdbId: String?,
    val season: Int?,
    val episode: Int?,
    val segments: List<VideoSkipSegment>
)

interface VideoSkipApi {
    val name: String
    suspend fun getSkips(imdbId: String, season: Int?, episode: Int?): VideoSkipResult?
}
