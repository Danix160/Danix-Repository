package com.cb

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CbPlugin : Plugin() {

    override fun load(context: Context) {

        // Registra l'Activity per entrambe le WebView
        UprotWebView.setContext(context)
        MaxStreamWebView.setContext(context)

        // Provider
        registerMainAPI(CbProvider())

        // Extractor
        registerExtractorAPI(Uprot())
        registerExtractorAPI(MaxStream())
    }
}
