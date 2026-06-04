package com.altadefinizione

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.altadefinizione.extractors.VidxGoExtractor
import com.lagradost.cloudstream3.utils.AppUtils.registerExtractor

@CloudstreamPlugin
class AltaDefinizionePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AltaDefinizioneProvider())
        registerExtractor(VidxGoExtractor())
    }
}
