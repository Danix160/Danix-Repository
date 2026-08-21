package com.guardaplay

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class GuardaPlayPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(GuardaPlayProvider())
    }
}
