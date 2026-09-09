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
        } 
                println("========== END UQLOAD PACKED ==========")

        val unpacked = packedScript?.let {
            unpackPacker(it)
        }

     println("========== UQLOAD UNPACKED ==========")

        if (unpacked == null) {
            println("[Uqload] UNPACK FALLITO")
            println("========== END UQLOAD UNPACKED ==========")
            return
        }

        println("[Uqload] UNPACKED LENGTH = ${unpacked.length}")

        val streamUrl = Regex(
            """sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)["']""",
            setOf(
                RegexOption.IGNORE_CASE,
                RegexOption.DOT_MATCHES_ALL
            )
        ).find(unpacked)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.trim()

        if (streamUrl.isNullOrBlank()) {
            println("[Uqload] STREAM URL NON TROVATO")
            println("========== END UQLOAD UNPACKED ==========")
            return
        }

        println("[Uqload] STREAM TROVATO = $streamUrl")

        callback(
            newExtractorLink(
                source = name,
                name = "$name HLS",
                url = streamUrl
            ) {
                this.referer = "$mainUrl/"
                this.type = ExtractorLinkType.M3U8
            }
        )

        println("[Uqload] ExtractorLink inviato")
        println("========== END UQLOAD UNPACKED ==========")
    }

    private fun unpackPacker(script: String): String? {
        val regex = Regex(
            """\}\('((?:\\.|[^'])*)',(\d+),(\d+),'((?:\\.|[^'])*)'\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL
        )

        val match = regex.find(script) ?: return null

        var payload = match.groupValues[1]
            .replace("\\'", "'")
            .replace("\\\\", "\\")

        val radix = match.groupValues[2].toIntOrNull()
            ?: return null

        val count = match.groupValues[3].toIntOrNull()
            ?: return null

        val words = match.groupValues[4]
            .split("|")

        fun encode(value: Int): String {
            val chars =
                "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

            if (value == 0) return "0"

            var number = value
            var result = ""

            while (number > 0) {
                result =
                    chars[number % radix] + result

                number /= radix
            }

            return result
        }

        for (i in count - 1 downTo 0) {
            if (i >= words.size) continue

            val replacement = words[i]

            if (replacement.isBlank()) continue

            val key = encode(i)

            payload = payload.replace(
                Regex("""\b${Regex.escape(key)}\b"""),
                replacement
            )
        }

        return payload
    }
}
