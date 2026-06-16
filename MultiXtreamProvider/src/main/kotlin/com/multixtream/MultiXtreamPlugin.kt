package com.multixtream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MultiXtreamPlugin : Plugin() {

    override fun load(context: Context) {

        val version = getKey<String>("multixtream_version") ?: "v1"

        when (version) {
            "v2" -> registerMainAPI(MultiXtreamProviderV2())
            else -> registerMainAPI(MultiXtreamProvider())
        }

        registerSettings(MultiXtreamSettings())
    }
}
