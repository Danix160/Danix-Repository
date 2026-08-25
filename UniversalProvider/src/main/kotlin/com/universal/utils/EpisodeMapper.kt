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

    fun hasMatch(
        media: UniversalMedia,
        episodes: List<ProviderEpisode>
    ): Boolean {
    
        if (episodes.isEmpty()) {
            return false
        }
    
        return findBest(
            media,
            episodes
        ) != null
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
    
        val titleScore =
            titleSimilarity(
                media.episodeTitle,
                candidate.title
            )
    
        score +=
            titleScore
    
        /*
         * Numero assoluto:
         * utile, ma non deve prevalere
         * su un titolo chiaramente diverso.
         */
        if (
            media.absoluteEpisode != null &&
            candidate.absoluteEpisode ==
            media.absoluteEpisode
        ) {
    
            score +=
                150
        }
    
        /*
         * Stagione + episodio identici.
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
         * Titolo episodio esatto/molto simile
         * deve essere il segnale più forte.
         */
        if (
            titleScore >= 150
        ) {
    
            score +=
                150
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
        if (best.second < 120) {

            Log.d(
                TAG,
                "Match episodio rifiutato: score troppo basso"
            )

            return null
        }

        return best.first
    }
}
