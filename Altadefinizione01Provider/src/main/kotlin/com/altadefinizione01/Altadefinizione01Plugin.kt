package com.altadefinizione01

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Altadefinizione01Plugin : Plugin() {

    override fun load(context: Context) {

        registerMainAPI(
            Altadefinizione01Provider()
        )

          registerExtractorAPI(
             VidxGoExtractor()
        )
         
    }
}
