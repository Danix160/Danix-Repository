package com.universal.sources

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.universal.models.ProviderEpisode
import com.universal.models.UniversalMedia

interface SourceAdapter {

    val name: String

    val requiresInteraction: Boolean

    val priority: Int

    suspend fun loadLinks(
        media: UniversalMedia,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int

    suspend fun getEpisodeInventory(
        media: UniversalMedia
    ): List<ProviderEpisode> {
        return emptyList()
    }
}
