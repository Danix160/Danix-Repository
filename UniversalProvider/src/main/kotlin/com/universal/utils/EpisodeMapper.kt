package com.universal.utils

import android.util.Log
import com.universal.models.ProviderEpisode
import com.universal.models.UniversalMedia
import java.text.Normalizer

object EpisodeMapper {

    private const val TAG =
        "UNIVERSAL_MAPPER"

    private fun normalize(
        value: String?
    ): String {

        if (
            value.isNullOrBlank()
        ) {
            return ""
        }

        return Normalizer
            .normalize(
                value,
                Normalizer.Form.NFD
            )
            .replace(
                Regex(
                    "\\p{InCombiningDiacriticalMarks}+"
                ),
                ""
            )
            .lowercase()
            .replace(
                Regex(
                    "[^a-z0-9]+"
                ),
                " "
            )
            .replace(
                Regex(
                    "\\s+"
                ),
                " "
            )
            .trim()
    }

    /*
     * Permette anche questo caso:
     *
     * TMDB:
     * "Il ritorno del Goblin"
     *
     * Provider:
     * "1x01 Il ragno colpisce / Il ritorno del Goblin"
     *
     * Quindi una singola voce provider può corrispondere
     * a più episodi/segmenti TMDB.
     */
    private fun titleContained(
        wanted: String?,
        provider: String?
    ): Boolean {

        val wantedNormalized =
            normalize(
                wanted
            )

        val providerNormalized =
            normalize(
                provider
            )

        if (
            wantedNormalized.isBlank() ||
            providerNormalized.isBlank()
        ) {
            return false
        }

        return providerNormalized
            .contains(
                wantedNormalized
            )
    }

    private fun titleSimilarity(
        first: String?,
        second: String?
    ): Int {

        val a =
            normalize(
                first
            )

        val b =
            normalize(
                second
            )

        if (
            a.isBlank() ||
            b.isBlank()
        ) {
            return 0
        }

        /*
         * Titolo identico.
         */
        if (
            a ==
            b
        ) {
            return 400
        }

        /*
         * Fondamentale per gli episodi doppi.
         *
         * Es:
         *
         * TMDB = "Titolo B"
         * provider = "Titolo A / Titolo B"
         */
        if (
            titleContained(
                first,
                second
            )
        ) {
            return 350
        }

        /*
         * Manteniamo anche il contrario
         * per titoli provider più corti.
         */
        if (
            a.contains(
                b
            )
        ) {
            return 180
        }

        val aWords =
            a.split(" ")
                .filter {
                    it.length >= 3
                }
                .toSet()

        val bWords =
            b.split(" ")
                .filter {
                    it.length >= 3
                }
                .toSet()

        if (
            aWords.isEmpty() ||
            bWords.isEmpty()
        ) {
            return 0
        }

        val common =
            aWords
                .intersect(
                    bWords
                )
                .size

        val max =
            maxOf(
                aWords.size,
                bWords.size
            )

        val ratio =
            common.toDouble() /
                max.toDouble()

        return when {

            ratio >= 0.80 ->
                140

            ratio >= 0.60 ->
                90

            ratio >= 0.40 ->
                40

            else ->
                0
        }
    }

    private fun score(
        media: UniversalMedia,
        candidate: ProviderEpisode
    ): Int {

        var score =
            0

        val titleScore =
            titleSimilarity(
                media.episodeTitle,
                candidate.title
            )

        /*
         * Il titolo è ora il criterio principale.
         */
        score +=
            titleScore

        /*
         * Stagione + episodio identici.
         *
         * Utile per provider con numerazione normale,
         * ma non deve battere un titolo corretto.
         */
        if (
            media.season != null &&
            media.episode != null &&
            candidate.season ==
            media.season &&
            candidate.episode ==
            media.episode
        ) {

            score +=
                120
        }

        /*
         * Numero assoluto.
         *
         * Ora è solo un supporto.
         *
         * Dopo episodi doppi/spezzati può non coincidere
         * più perfettamente tra TMDB e provider.
         */
        if (
            media.absoluteEpisode != null &&
            candidate.absoluteEpisode ==
            media.absoluteEpisode
        ) {

            score +=
                80
        }

        /*
         * Se il titolo è già molto forte,
         * diamo un ulteriore bonus.
         */
        if (
            titleScore >=
            300
        ) {

            score +=
                200
        }

        return score
    }

    fun findBest(
        media: UniversalMedia,
        episodes: List<ProviderEpisode>
    ): ProviderEpisode? {

        if (
            episodes.isEmpty()
        ) {
            return null
        }

        val scored =
            episodes
                .map { episode ->

                    episode to
                        score(
                            media,
                            episode
                        )
                }
                .sortedByDescending {
                    it.second
                }

        val best =
            scored
                .firstOrNull()
                ?: return null

        Log.d(
            TAG,
            "TMDB " +
                "S${media.season}" +
                "E${media.episode} " +
                "abs=${media.absoluteEpisode} " +
                "title=${media.episodeTitle}"
        )

        Log.d(
            TAG,
            "BEST PROVIDER " +
                "${best.first.source} " +
                "S${best.first.season}" +
                "E${best.first.episode}" +
                (
                    best.first.part
                        ?.let {
                            ".$it"
                        }
                        ?: ""
                ) +
                " abs=${best.first.absoluteEpisode} " +
                "title=${best.first.title} " +
                "score=${best.second}"
        )

        /*
         * Una corrispondenza debole basata
         * soltanto sui numeri non è sufficiente.
         */
        if (
            best.second <
            120
        ) {

            Log.d(
                TAG,
                "Match episodio rifiutato: " +
                    "score troppo basso"
            )

            return null
        }

        return best.first
    }

    fun hasMatch(
        media: UniversalMedia,
        episodes: List<ProviderEpisode>
    ): Boolean {

        if (
            episodes.isEmpty()
        ) {
            return false
        }

        return findBest(
            media,
            episodes
        ) != null
    }
}
