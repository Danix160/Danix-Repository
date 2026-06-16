package com.multixtream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.ui.settings.SettingsProvider
import com.lagradost.cloudstream3.ui.settings.SelectSetting

@CloudstreamPlugin
class MultiXtreamPlugin : Plugin() {

    override fun load(context: Context) {

        // Impostazione ufficiale Cloudstream 4
        SettingsProvider.addSetting(
            SelectSetting(
                key = "multixtream_version",
                title = "Versione Provider",
                values = listOf("v1", "v2", "v3"),
                defaultValue = "v1"
            )
        )

        val version = SettingsProvider.getString("multixtream_version", "v1")

        when (version) {
            "v2" -> registerMainAPI(MultiXtreamProviderV2())
            "v3" -> registerMainAPI(MultiXtreamProviderV3())
            else -> registerMainAPI(MultiXtreamProvider())
        }
    }
}
