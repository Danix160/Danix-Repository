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
    
        val code = url
            .substringAfterLast("/")
            .substringBefore(".html")
            .substringBefore("?")
            .trim()
    
        if (code.isBlank()) {
            println("[Uqload] FILE CODE VUOTO")
            return
        }
    
        println("[Uqload] CODE = $code")
    
        val embedResponse = app.get(
            url,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to (referer ?: "https://toonitalia.xyz/")
            )
        )
    
        println("[Uqload] EMBED STATUS = ${embedResponse.code}")
    
        val fileId = Regex(
            """\$\.cookie\(\s*['"]file_id['"]\s*,\s*['"](\d+)['"]"""
        ).find(embedResponse.text)
            ?.groupValues
            ?.getOrNull(1)
        
        val aff = Regex(
            """\$\.cookie\(\s*['"]aff['"]\s*,\s*['"](\d+)['"]"""
        ).find(embedResponse.text)
            ?.groupValues
            ?.getOrNull(1)
    
        println("[Uqload] file_id = $fileId")
        println("[Uqload] aff = $aff")
    
        val cookies = buildString {
            if (!fileId.isNullOrBlank()) {
                append("file_id=$fileId")
            }
    
            if (!aff.isNullOrBlank()) {
                if (isNotEmpty()) append("; ")
                append("aff=$aff")
            }
        }
    
        val dlResponse = app.post(
            "$mainUrl/dl",
            data = mapOf(
                "op" to "embed",
                "file_code" to code,
                "auto" to "1",
                "referer" to ""
            ),
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to url,
                "Origin" to mainUrl,
                "Content-Type" to "application/x-www-form-urlencoded"
            ) + if (cookies.isNotBlank()) {
                mapOf("Cookie" to cookies)
            } else {
                emptyMap()
            }
        )
    
        println("[Uqload] DL STATUS = ${dlResponse.code}")
        println("[Uqload] DL FINAL URL = ${dlResponse.url}")
        println("[Uqload] DL LENGTH = ${dlResponse.text.length}")

        val packedScript = Regex(
            """eval\(function\(p,a,c,k,e,d\).*?</script>""",
            setOf(
                RegexOption.IGNORE_CASE,
                RegexOption.DOT_MATCHES_ALL
            )
        ).find(dlResponse.text)
            ?.value
            ?.substringBefore("</script>")
            ?.trim()
        
        println("========== UQLOAD PACKED ==========")
        
        if (packedScript == null) {
            println("[Uqload] PACKED SCRIPT NON TROVATO")
        } else {
            println("[Uqload] PACKED LENGTH = ${packedScript.length}")
            println(packedScript)
        } 
        println("========== END UQLOAD PACKED ==========")
    }
}
