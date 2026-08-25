package com.universal

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class UniversalPlugin : Plugin() {

    override fun load(context: android.content.Context) {

        registerMainAPI(
            UniversalProvider()
        )
    }
}
