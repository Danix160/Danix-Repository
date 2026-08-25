package com.universal.utils

import android.util.Log
import com.universal.models.ProviderEpisode
import com.universal.models.UniversalMedia
import java.text.Normalizer

object EpisodeMapper {

    private const val TAG = "UNIVERSAL_MAPPER"

    private fun normalize(
        value: String?
    ): String {

        if (value.isNullOrBlank()) {
            return ""
        }

        return Normalizer
            .normalize(
                value,
                Normalizer.Form.NFD
            )
            .replace(
                Regex("\\p{InCombiningDiacriticalMarks}+"),
                ""
            )
            .lowercase()
            .replace(
                Regex("[^a-z0-9]+"),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    private fun titleSimilarity(
        first: String?,
        second: String?
    ): Int {

        val a = normalize(first)
        val b = normalize(second)

        if (
            a.isBlank() ||
            b.isBlank()
        ) {
            return 0
        }

        if (a == b) {
            return 250
        }

        if (
            a.contains(b) ||
            b.contains(a)
        ) {
            return 150
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
            aWords.intersect(bWords).size

        val max =
            maxOf(
                aWords.size,
                bWords.size
            )

        val ratio =
            common.toDouble() /
                max.toDouble()

        return when {
            ratio >= 0.80 -> 120
            ratio >= 0.60 -> 80
            ratio >= 0.40 -> 40
            else -> 0
        }
    }

    private fun score(
        media: UniversalMedia,
        candidate: ProviderEpisode
    ): Int {

        var score = 0

        /*
         * Titolo episodio.
         *
         * È molto importante quando TMDB e provider
         * dividono le stagioni diversamente.
         */
        score +=
            titleSimilarity(
                media.episodeTitle,
                candidate.title
            )

        /*
         * Numero assoluto.
         *
         * Es:
         *
         * TMDB S03E01 = episodio assoluto 26
         * Provider S02E09 = episodio assoluto 26
         */
        if (
            media.absoluteEpisode != null &&
            candidate.absoluteEpisode ==
            media.absoluteEpisode
        ) {
            score += 200
        }

        /*
         * Stagione + episodio esatti.
         *
         * Molto affidabile quando le strutture coincidono.
         */
        if (
            media.season != null &&
            media.episode != null &&
            candidate.season ==
            media.season &&
            candidate.episode ==
            media.episode
        ) {
            score += 180
        }

        /*
         * Solo episodio uguale.
         * Peso basso perché può ripetersi tra stagioni.
         */
        if (
            media.episode != null &&
            candidate.episode ==
            media.episode
        ) {
            score += 20
        }

        return score
    }

    fun findBest(
        media: UniversalMedia,
        episodes: List<ProviderEpisode>
    ): ProviderEpisode? {

        if (episodes.isEmpty()) {
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
            scored.firstOrNull()
                ?: return null

        Log.d(
            TAG,
            "TMDB S${media.season}E${media.episode} " +
                "abs=${media.absoluteEpisode} " +
                "title=${media.episodeTitle}"
        )

        Log.d(
            TAG,
            "BEST PROVIDER " +
                "S${best.first.season}E${best.first.episode} " +
                "abs=${best.first.absoluteEpisode} " +
                "title=${best.first.title} " +
                "score=${best.second}"
        )

        /*
         * Evitiamo match casuali.
         */
        if (best.second < 100) {

            Log.d(
                TAG,
                "Match episodio rifiutato: score troppo basso"
            )

            return null
        }

        return best.first
    }
}
