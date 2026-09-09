package com.toonitalia

import com.lagradost.cloudstream3.utils.*
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
    override var name: String = "Uqload"
    override var mainUrl: String = "https://www.uqload.com"
    override val requiresReferer = true

    private val srcRegex = Regex(
    """sources\s*:\s*\[\s*["']([^"']+)["']""",
    setOf(
        RegexOption.IGNORE_CASE,
        RegexOption.DOT_MATCHES_ALL
    )
)

    override suspend fun getUrl(
                url: String,
                referer: String?,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit
            ) {
                println("[Uqload] URL ricevuto: $url")
            
                val response = app.get(
                    url,
                    referer = referer
                )
            
                println("[Uqload] status=${response.code}")
                println("[Uqload] finalUrl=${response.url}")
            
                val match = srcRegex.find(response.text)
            
                if (match == null) {
                    println("[Uqload] SOURCES NON TROVATO")
                    println(
                        "[Uqload] HTML sample=" +
                            response.text.take(1000)
                    )
                    return
                }
            
                val link = match.groupValues[1]
            
                println("[Uqload] VIDEO TROVATO: $link")
            
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link
                    ) {
                        this.referer = "$mainUrl/"
                    }
                )
            }
        }
    }
}
