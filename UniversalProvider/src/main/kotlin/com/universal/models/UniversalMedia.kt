package com.universal.models

data class UniversalMedia(

    val title: String,

    val originalTitle: String? = null,

    val year: Int? = null,

    val tmdbId: Int? = null,

    val imdbId: String? = null,

    val season: Int? = null,

    val episode: Int? = null,

    val episodeTitle: String? = null,

    val isMovie: Boolean = false
)
