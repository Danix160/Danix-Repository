package com.altadefinizione

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.app
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.Base64

class VidxGoExtractor : ExtractorApi() {
    override val name = "VidxGo"
    override val mainUrl = "https://v.vidxgo.co"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, 
        referer: String?, 
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ) {
        val parsedUrl = url.toHttpUrl()
        val refererUrl = "${parsedUrl.scheme}://${parsedUrl.host}/"
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        val headersMap = mutableMapOf(
            "Referer" to refererUrl,
            "User-Agent" to userAgent
        )
        
        if (!url.contains("/t/")) {
            headersMap["sec-fetch-dest"] = "iframe"
        }

        val response = app.get(
            url = url,
            headers = headersMap
        )
        val html = response.text
        var videoUrl: String? = null

        if (url.contains("/t/")) {
            // Gestione Serie TV: Spesso risponde con un JSON o una stringa contenente l'oggetto JSON del video
            // Usiamo una regex flessibile per estrarre il valore di "url"
            val videoUrlRaw = Regex("""["']url["']\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            videoUrl = videoUrlRaw?.replace("\\/", "/")
        } else {
            // Gestione Film (Decodifica XOR personalizzata)
            val scriptRegex = Regex("<script[\\s\\S]*?>[\\s\\S]*?\\(function\\(\\)\\s*\\{[\\s\\S]*?\\}\\s*\\)\\(\\);[\\s\\S]*?</script>", RegexOption.IGNORE_CASE)
            val scriptMatches = scriptRegex.findAll(html).toList()
            
            if (scriptMatches.size >= 3) {
                val targetScript = scriptMatches[2].value
                val k = Regex("var\\s+k\\s*=\\s*['\"]([^'\"]+)['\"]").find(targetScript)?.groupValues?.get(1)
                val d = Regex("atob\\(['\"]([^'\"]+)['\"]\\)").find(targetScript)?.groupValues?.get(1)

                if (k != null && d != null) {
                    // Sostituito con java.util.Base64 per garantire compatibilità cross-platform
                    val decodedD = Base64.getDecoder().decode(d)
                    val decrypted = ByteArray(decodedD.size) { i ->
                        ((decodedD[i].toInt() and 0xFF) xor (k[i % k.length].code and 0xFF)).toByte()
                    }
                    val decryptedText = String(decrypted)
                    
                    val videoUrlRaw = Regex("currentSrc\\s*=\\s*['\"]([^'\"]+)['\"]").find(decryptedText)?.groupValues?.get(1)
                    videoUrl = videoUrlRaw?.replace("\\/", "/")
                }
            }
        }

        // Se non siamo riusciti ad estrarre l'URL video, interrompiamo per evitare crash
        if (videoUrl.isNullOrBlank()) return

        val isM3u8 = videoUrl.contains(".m3u8")

        // Intestazioni di riproduzione complete incluse di User-Agent speculare
        val playbackHeaders = mapOf(
            "origin" to "https://v.vidxgo.co",
            "referer" to "https://v.vidxgo.co/",
            "User-Agent" to userAgent,
            "sec-fetch-dest" to "empty",
            "sec-fetch-site" to "cross-site"
        )

        if (isM3u8) {
            M3u8Helper.generateM3u8(
                source = this.name,
                streamUrl = videoUrl,
                referer = refererUrl,
                headers = playbackHeaders
            ).forEach { link ->
                callback.invoke(link)
            }
        } else {
            val link = newExtractorLink(
                source = this.name,
                name = this.name,
                url = videoUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = refererUrl
                this.quality = Qualities.Unknown.value
                this.headers = playbackHeaders
            }
            callback.invoke(link)
        }
    }
}
