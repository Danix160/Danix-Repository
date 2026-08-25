package com.universal.models

data class ProviderEpisode(

    val source: String,

    val season: Int? = null,

    val episode: Int? = null,

    val absoluteEpisode: Int,

    val title: String? = null,

    val urls: List<String> =
        emptyList()
)
