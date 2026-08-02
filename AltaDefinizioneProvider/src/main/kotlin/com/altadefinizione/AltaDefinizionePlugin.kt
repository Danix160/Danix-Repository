package com.altadefinizione // Deve essere uguale a quello in cima al file

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.altadefinizione.extractor.VidxGoExtractor

@CloudstreamPlugin
class AltaDefinizionePlugin: Plugin() {
    override fun load(context: Context) {
        // Registra il provider definito sopra nella classe ToonItaliaProvider
        registerMainAPI(AltaDefinizioneProvider())

        // Registra l'extractor VidxGo
        registerExtractorAPI(VidxGoExtractor())
    }
}
