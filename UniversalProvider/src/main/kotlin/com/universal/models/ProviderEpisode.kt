package com.universal.models

data class ProviderEpisode(

    val source: String,

    val season: Int? = null,

    val episode: Int? = null,

    /*
     * Parte/segmento dell'episodio.
     *
     * Esempi CB01:
     * 1x01.1 -> part = 1
     * 1x01.2 -> part = 2
     */
    val part: Int? = null,

    /*
     * Posizione reale nella sequenza del provider.
     *
     * Importante:
     * per provider con episodi spezzati,
     * ogni segmento incrementa questo valore.
     */
    val absoluteEpisode: Int,

    val title: String? = null,

    val urls: List<String> =
        emptyList()
)
