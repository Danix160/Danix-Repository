package com.multixtream

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MultiXtreamPlugin : Plugin() {

    override fun load(context: Context) {

        val sharedPref = context.getSharedPreferences("multixtream_prefs", Context.MODE_PRIVATE)
        val version = sharedPref.getString("site_version", "v1") ?: "v1"

        when (version) {
            "v2" -> registerMainAPI(MultiXtreamProviderV2())
            "v3" -> registerMainAPI(MultiXtreamProviderV3())
            else -> registerMainAPI(MultiXtreamProvider())
        }

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = MultiXtreamSettings(this, sharedPref)
            frag.show(activity.supportFragmentManager, "MultiXtreamSettings")
        }
    }
}
