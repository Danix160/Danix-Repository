package com.onlineserietv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OnlineSerieTvPlugin : Plugin() {

    override fun load(context: Context) {

        // Salviamo l'Activity/Context di CloudStream
        // per la WebView interattiva Uprot
        UprotWebView.setContext(context)

        registerMainAPI(
            OnlineSerieTvProvider()
        )

        registerExtractorAPI(
            Uprot()
        )

        registerExtractorAPI(
            MaxStream()
        )
    }
}
