package com.universal.sources

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.universal.models.UniversalMedia

interface SourceAdapter {

    val name: String

    /*
     * TRUE:
     *
     * la sorgente può aprire WebView,
     * CAPTCHA o altre UI.
     *
     *
     * FALSE:
     *
     * può essere provata silenziosamente.
     */
    val requiresInteraction: Boolean

    /*
     * Priorità.
     *
     * Più basso = viene provato prima.
     */
    val priority: Int

    suspend fun loadLinks(
        media: UniversalMedia,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int
}
