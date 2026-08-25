package com.universal.sources

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.universal.models.UniversalMedia
import java.text.Normalizer

class Cb01Source : SourceAdapter {

    override val name =
        "CB01"

    override val requiresInteraction =
        true

    override val priority =
        30

    companion object {

        private const val TAG =
            "UNIVERSAL_CB01"
    }

    override suspend fun loadLinks(
        media: UniversalMedia,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {

        Log.d(
            TAG,
            "=============================="
        )

        Log.d(
            TAG,
            "Titolo = ${media.title}"
        )

        Log.d(
            TAG,
            "Originale = ${media.originalTitle}"
        )

        Log.d(
            TAG,
            "Anno = ${media.year}"
        )

        Log.d(
            TAG,
            "S${media.season}E${media.episode}"
        )

        /*
         * Qui implementeremo anche
         * il matching delle pagine raggruppate.
         */

        return 0
    }

    /*
     * Verrà utilizzato per Scooby-Doo
     * e casi simili.
     */
    private fun normalize(
        value: String?
    ): String {

        if (value.isNullOrBlank()) {
            return ""
        }

        val normalized =
            Normalizer.normalize(
                value,
                Normalizer.Form.NFD
            )

        return normalized
            .replace(
                Regex("\\p{InCombiningDiacriticalMarks}+"),
                ""
            )
            .lowercase()
            .replace(
                Regex("[^a-z0-9]+"),
                " "
            )
            .trim()
    }

    private fun calculateScore(
        media: UniversalMedia,
        candidateTitle: String,
        candidateYear: Int? = null,
        candidateSeason: Int? = null,
        candidateEpisode: Int? = null
    ): Int {

        var score =
            0

        val candidate =
            normalize(
                candidateTitle
            )

        val title =
            normalize(
                media.title
            )

        val original =
            normalize(
                media.originalTitle
            )

        /*
         * Titolo esatto.
         */
        if (
            candidate.isNotBlank() &&
            candidate == title
        ) {

            score +=
                50
        }

        /*
         * Titolo originale.
         */
        if (
            original.isNotBlank() &&
            candidate == original
        ) {

            score +=
                40
        }

        /*
         * Titolo contenuto.
         *
         * Utile per pagine aggregate.
         */
        if (
            candidate.contains(
                title
            ) ||
            title.contains(
                candidate
            )
        ) {

            score +=
                20
        }

        if (
            media.year != null &&
            candidateYear != null &&
            media.year == candidateYear
        ) {

            score +=
                20
        }

        if (
            media.season != null &&
            candidateSeason != null &&
            media.season == candidateSeason
        ) {

            score +=
                30
        }

        if (
            media.episode != null &&
            candidateEpisode != null &&
            media.episode == candidateEpisode
        ) {

            score +=
                50
        }

        return score
    }
}
