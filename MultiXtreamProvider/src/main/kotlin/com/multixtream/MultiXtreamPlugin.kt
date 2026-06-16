package com.multixtream

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MultiXtreamPlugin : Plugin() {

    private val sharedPref by lazy {
        activity?.getSharedPreferences("MultiXtream", Context.MODE_PRIVATE)
    }

    override fun load(context: Context) {
        val version = sharedPref?.getString("version", "v1") ?: "v1"

        when (version) {
            "v2" -> registerMainAPI(MultiXtreamProviderV2())
            else -> registerMainAPI(MultiXtreamProvider())
        }

        // eventuali extractor
        // registerExtractorAPI(XtreamExtractor())

        openSettings = { ctx ->
            val act = ctx as AppCompatActivity
            val frag = MultiXtreamSettings(this, sharedPref)
            frag.show(act.supportFragmentManager, "MultiXtreamSettings")
        }
    }
}
