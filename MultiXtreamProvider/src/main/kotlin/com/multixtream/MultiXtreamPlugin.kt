package com.multixtream // Deve essere uguale a quello in cima al file

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import android.content.SharedPreferences
import androidx.fragment.app.Fragment

@CloudstreamPlugin
class MultiXtreamPlugin : Plugin() {

    companion object {
        const val PREF_NAME = "multixtream_settings"
        const val KEY_SITE_VERSION = "site_version"
        const val KEY_VERSION_POSITION = "versionPosition"
    }

    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val version = prefs.getString(KEY_SITE_VERSION, "v1") ?: "v1"

        when (version) {
            "v1" -> registerMainAPI(MultiXtreamProvider())
            "v2" -> registerMainAPI(MultiXtreamProviderV2())
            "v3" -> registerMainAPI(MultiXtreamProviderV3())
            else -> registerMainAPI(MultiXtreamProvider())
        }
    }

    override fun getSettingsFragment(context: Context): Fragment {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return MultiXtreamSettings(this, prefs)
    }
}

