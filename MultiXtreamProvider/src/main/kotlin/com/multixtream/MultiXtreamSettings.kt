package com.multixtream

import com.lagradost.cloudstream3.ui.settings.SettingsFragment
import com.lagradost.cloudstream3.ui.settings.SelectSetting

class MultiXtreamSettings : SettingsFragment() {

    override fun getTitle(): String {
        return "Impostazioni MultiXtream"
    }

    override fun getSettingsList(): List<Any> {
        return listOf(
            SelectSetting(
                key = "multixtream_version",
                title = "Versione Provider",
                values = listOf("v1", "v2"),
                defaultValue = "v1"
            )
        )
    }
}
