package com.universal

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.universal.extractors.VidxGoExtractor

@CloudstreamPlugin
class UniversalPlugin : Plugin() {

    override fun load(context: Context) {

        registerMainAPI(
            UniversalProvider()
        )
        
        registerExtractorAPI(
            VidxGoExtractor()
        )
        
        registerExtractorAPI(
            Uprot()
        )
        
        registerExtractorAPI(
            MaxStream()
        )
    }
}
