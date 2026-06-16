package com.multixtream

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin


@CloudstreamPlugin
class MultiXtreamPlugin : Plugin() {


    override fun load(context: Context) {


        val pref = context.getSharedPreferences(
            "multixtream_prefs",
            Context.MODE_PRIVATE
        )


        val version =
            pref.getString(
                "multixtream_version",
                "v1"
            ) ?: "v1"



        when(version) {

            "v2" -> registerMainAPI(
                MultiXtreamProviderV2()
            )


            else -> registerMainAPI(
                MultiXtreamProvider()
            )
        }



        openSettings = { ctx ->

            val activity =
                ctx as AppCompatActivity


            MultiXtreamSettings(
                this,
                pref
            )
            .show(
                activity.supportFragmentManager,
                "MultiXtreamSettings"
            )
        }
    }
}
