package com.toonitalia

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.*

// import android.util.Log

class Uqloadvc : Uqload() {
    override var mainUrl = "https://uqload.vc"
}

class Uqload1 : Uqload() {
    override var mainUrl = "https://uqload.com"
}

class Uqload2 : Uqload() {
    override var mainUrl = "https://uqload.co"
}

class Uqloadcx : Uqload() {
    override var mainUrl = "https://uqload.cx"
}

class Uqloadbz : Uqload() {
    override var mainUrl = "https://uqload.bz"
}

open class Uqload : ExtractorApi() {

    override var name = "Uqload"
    override var mainUrl = "https://www.uqload.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[Uqload] URL = $url")

        val response = app.get(
            url,
            referer = referer
        )

        println("[Uqload] STATUS = ${response.code}")
        println("[Uqload] FINAL URL = ${response.url}")

        val document = response.document

        val form = document.selectFirst("form[name=F1]")

        if (form == null) {
            println("[Uqload] FORM F1 NON TROVATO")
            return
        }

        println("========== UQLOAD FORM F1 ==========")
        println(form.outerHtml())
        println("========== END UQLOAD FORM F1 ==========")
    }
}
