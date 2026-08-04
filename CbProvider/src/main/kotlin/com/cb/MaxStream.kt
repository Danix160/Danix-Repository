package com.cb

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import android.util.Base64

class MaxStream : ExtractorApi() {
    override val name = "MaxStream"
    override val mainUrl = "https://maxstream.video"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var cleanUrl = url.trim()

        // Fix link tipo /watchfree/ID1/ID2/token
        if (cleanUrl.contains("watchfree/")) {
            val parts = cleanUrl.split("watchfree/")[1].removeSuffix("/").split("/")
            val videoId = if (parts.size == 3) parts[1] else parts[0]
            cleanUrl = "https://maxstream.video/emvvv/$videoId"
        }

        // Fix link Uprot → Maxstream
        if (cleanUrl.contains("uprots.com/v/")) {
            cleanUrl = cleanUrl.replace("uprots.com/v/", "maxstream.video/emvvv/")
        }

        val response = app.get(cleanUrl, referer = referer)
        val html = response.text

        // 1️⃣ Regex classico: sources: [{src:"..."}]
        val classicRegex = Regex("""sources\s*[:=]\s*

\[\s*\{\s*src\s*[:=]\s*["']([^"']+)["']""")
        classicRegex.find(html)?.groupValues?.getOrNull(1)?.let { src ->
            return callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = src,
                    type = if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = cleanUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        // 2️⃣ Decodifica avanzata: decodedBaseUrl + decodedEncryptedVal
        val b64Base = Regex("""decodedBaseUrl\s*=\s*atob\(["']([^"']+)["']\)""")
            .find(html)?.groupValues?.getOrNull(1)

        val b64Val = Regex("""decodedEncryptedVal\s*=\s*atob\(["']([^"']+)["']\)""")
            .find(html)?.groupValues?.getOrNull(1)

        if (!b64Base.isNullOrBlank() && !b64Val.isNullOrBlank()) {
            try {
                val decodedBase = String(Base64.decode(b64Base, Base64.DEFAULT))
                val decodedVal = String(Base64.decode(b64Val, Base64.DEFAULT))
                val finalUrl = decodedBase + decodedVal

                return callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = finalUrl,
                        type = if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = cleanUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            } catch (_: Exception) {}
        }

        // 3️⃣ Regex fallback: src:"..."
        val fallbackRegex = Regex("""src\s*[:=]\s*["']([^"']+)["']""")
        fallbackRegex.find(html)?.groupValues?.getOrNull(1)?.let { src ->
            return callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = src,
                    type = if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = cleanUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}
