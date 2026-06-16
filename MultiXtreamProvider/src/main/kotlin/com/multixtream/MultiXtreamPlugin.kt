package com.multixtream

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MultiXtreamPlugin : Plugin() {

    companion object {
        const val PREF_NAME = "multixtream_prefs"
        const val KEY_SITE_VERSION = "site_version"
    }

    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val version = prefs.getString(KEY_SITE_VERSION, "v1") ?: "v1"

        when (version) {
            "v2" -> registerMainAPI(MultiXtreamProviderV2())
            else -> registerMainAPI(MultiXtreamProvider())
        }

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = MultiXtreamSettings(this, prefs)
            frag.show(activity.supportFragmentManager, "MultiXtreamSettings")
        }
    }
}
