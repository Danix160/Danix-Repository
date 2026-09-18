package com.altadefinizionefast

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AltadefinizionePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AltadefinizioneFastProvider())
        registerExtractorAPI(VidxGoExtractor())
    }
}
