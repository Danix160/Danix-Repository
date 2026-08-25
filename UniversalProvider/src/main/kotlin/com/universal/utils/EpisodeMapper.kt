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
    
        private fun hasUsefulTitle(
            title: String?
        ): Boolean {
        
            val normalized =
                normalize(title)
        
            if (normalized.isBlank()) {
                return false
            }
        
            /*
             * Titoli che in realtà non sono titoli episodio.
             */
            if (
                normalized.matches(
                    Regex(
                        """(?:episodio|episode|ep)\s*\d+"""
                    )
                )
            ) {
                return false
            }
        
            if (
                normalized.matches(
                    Regex(
                        """\d+\s*x\s*\d+"""
                    )
                )
            ) {
                return false
            }
        
            return normalized.length >= 4
        }
    private fun titleSimilarity(
        first: String?,
        second: String?
    ): Int {

        val a =
            normalize(first)

        val b =
            normalize(second)

        if (
            a.isBlank() ||
            b.isBlank()
        ) {
            return 0
        }

        /*
         * Titolo identico.
         */
        if (a == b) {
            return 500
        }

        /*
         * Caso episodio accorpato.
         *
         * TMDB:
         * "Titolo B"
         *
         * Provider:
         * "Titolo A / Titolo B"
         */
        if (
            b.contains(a)
        ) {
            return 450
        }

        /*
         * Caso contrario:
         * provider con titolo più corto.
         */
        if (
            a.contains(b)
        ) {
            return 220
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
            aWords.intersect(
                bWords
            ).size

        val maxSize =
            maxOf(
                aWords.size,
                bWords.size
            )

        val ratio =
            common.toDouble() /
                maxSize.toDouble()

        return when {

            ratio >= 0.85 ->
                180

            ratio >= 0.70 ->
                130

            ratio >= 0.55 ->
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
         * Il titolo è il criterio principale.
         */
        score +=
            titleScore

        /*
         * Se il titolo è forte,
         * premiamo ancora.
         */
        if (
            titleScore >= 400
        ) {
            score += 250
        }

        /*
         * Stagione + episodio:
         * solo supporto.
         */
        if (
            media.season != null &&
            media.episode != null &&
            candidate.season ==
            media.season &&
            candidate.episode ==
            media.episode
        ) {
            score += 80
        }

        /*
         * Numero assoluto:
         * supporto debole.
         *
         * Non deve mai essere sufficiente
         * da solo per far sopravvivere
         * un episodio TMDB sbagliato.
         */
        if (
            media.absoluteEpisode != null &&
            candidate.absoluteEpisode ==
            media.absoluteEpisode
        ) {
            score += 40
        }

        /*
         * Se titolo completamente diverso,
         * i numeri non devono salvare il match.
         */
        if (
            titleScore == 0
        ) {

            score -= 120
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

    val bestTitleScore =
        titleSimilarity(
            media.episodeTitle,
            best.first.title
        )

    val providerHasUsefulTitle =
        hasUsefulTitle(
            best.first.title
        )

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
            "titleScore=$bestTitleScore " +
            "usefulTitle=$providerHasUsefulTitle " +
            "score=${best.second}"
    )

    /*
     * CASO 1
     *
     * Abbiamo veri titoli episodio.
     * Usiamo il titolo come criterio principale.
     *
     * Permette anche:
     *
     * TMDB E1 = Titolo A
     * TMDB E2 = Titolo B
     *
     * Provider E1 = Titolo A / Titolo B
     */
    if (
        providerHasUsefulTitle &&
        !media.episodeTitle.isNullOrBlank()
    ) {

        if (
            bestTitleScore >= 130
        ) {

            return best.first
        }

        Log.d(
            TAG,
            "Match rifiutato: " +
                "titolo provider presente ma incompatibile"
        )

        return null
    }

    /*
     * CASO 2
     *
     * Il provider NON espone veri titoli.
     *
     * Allora possiamo usare stagione/episodio,
     * perché altrimenti elimineremmo tutta la serie.
     */
    val sameSeasonEpisode =
        media.season != null &&
            media.episode != null &&
            best.first.season ==
                media.season &&
            best.first.episode ==
                media.episode

    if (sameSeasonEpisode) {

        Log.d(
            TAG,
            "Match numerico accettato: " +
                "provider senza titolo utile"
        )

        return best.first
    }

    /*
     * Fallback assoluto solo quando non
     * abbiamo titoli utili.
     */
    val sameAbsolute =
        media.absoluteEpisode != null &&
            best.first.absoluteEpisode ==
                media.absoluteEpisode

    if (sameAbsolute) {

        Log.d(
            TAG,
            "Match assoluto accettato: " +
                "provider senza titolo utile"
        )

        return best.first
    }

    Log.d(
        TAG,
        "Nessuna corrispondenza episodio"
    )

    return null
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
