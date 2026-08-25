package com.universal.models

data class ProviderEpisode(
    val season: Int? = null,
    val episode: Int? = null,

    // Posizione cronologica dell'episodio nel provider.
    // Parte da 1.
    val absoluteEpisode: Int,

    val title: String? = null,

    // URL player trovati nella riga/pagina dell'episodio.
    val urls: List<String> = emptyList()
)
