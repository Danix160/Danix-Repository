package com.universal.sources

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.universal.models.UniversalMedia

class Altadefinizione01Source : SourceAdapter {

    override val name =
        "Altadefinizione01"

    override val requiresInteraction =
        false

    override val priority =
        10

    companion object {

        private const val TAG =
            "UNIVERSAL_AD01"
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
            "Cerco: ${media.title}"
        )

        Log.d(
            TAG,
            "TMDB = ${media.tmdbId}"
        )

        Log.d(
            TAG,
            "IMDb = ${media.imdbId}"
        )

        Log.d(
            TAG,
            "Stagione = ${media.season}"
        )

        Log.d(
            TAG,
            "Episodio = ${media.episode}"
        )

        /*
         * QUI collegheremo la logica reale
         * del tuo Altadefinizione01Provider.
         *
         * Non inserisco selettori inventati.
         */

        return 0
    }
}
