package com.universal.sources

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.universal.models.UniversalMedia

class OnlineSerieTvSource : SourceAdapter {

    override val name =
        "OnlineSerieTV"

    /*
     * Uprot può richiedere CAPTCHA.
     */
    override val requiresInteraction =
        true

    override val priority =
        20

    companion object {

        private const val TAG =
            "UNIVERSAL_OSTV"
    }

    override suspend fun loadLinks(
        media: UniversalMedia,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {

        Log.d(
            TAG,
            "Cerco ${media.title}"
        )

        /*
         * Verrà collegata in seguito
         * la logica OnlineSerieTV.
         *
         * IMPORTANTE:
         *
         * questa funzione verrà chiamata
         * solamente se nessuna sorgente
         * senza CAPTCHA ha prodotto video.
         */

        return 0
    }
}
