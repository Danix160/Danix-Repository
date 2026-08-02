package com.altadefinizione.extractor

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

class VidxGoExtractor : ExtractorApi() {

    override val name = "VidxGo"
    override val mainUrl = "https://v.vidxgo.co"
    override val requiresReferer = true   // NECESSARIO PER FUNZIONARE

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36"

        private val HTML_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Referer" to "https://v.vidxgo.co/",
            "Origin" to "https://v.vidxgo.co",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Dest" to "iframe",
        )

        private val MEDIA_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Referer" to "https://v.vidxgo.co/",
            "Origin" to "https://v.vidxgo.co",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty",
        )

        /** Estrazione XOR da script HTML */
        private fun extractFromHtml(html: String): String? {
            val scripts = Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { it.value }

            val encodedRegex = Regex("""atob\(['"]([^'"]+)['"]\)""")
            val keyRegex = Regex("""var\s+\w+\s*=\s*['"]([^'"]+)['"]""")

            for (script in scripts) {
                val encoded = encodedRegex.find(script)?.groupValues?.get(1) ?: continue
                val keys = keyRegex.findAll(script).map { it.groupValues[1] }

                for (key in keys) {
                    val decrypted = runCatching {
                        val decoded = Base64.decode(encoded, Base64.DEFAULT)
                        val xor = ByteArray(decoded.size) { i ->
                            (decoded[i].toInt() xor key[i % key.length].code).toByte()
                        }
                        String(xor)
                    }.getOrNull() ?: continue

                    val url = Regex("""currentSrc\s*=\s*['"]([^'"]+)['"]""")
                        .find(decrypted)
                        ?.groupValues?.get(1)
                        ?: Regex("""https?://[^\s'"]+""")
                            .find(decrypted)
                            ?.value

                    if (!url.isNullOrBlank()) {
                        return url.replace("\\/", "/")
                    }
                }
            }
            return null
        }

        /** Estrazione JSON fallback */
        private fun extractFromJson(text: String): String? {
            return Regex("""["']url["']\s*:\s*["']([^"']+)["']""")
                .find(text)
                ?.groupValues?.get(1)
                ?.replace("\\/", "/")
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val targetUrl = buildTargetUrl(url)

        val response = app.get(targetUrl, headers = HTML_HEADERS)
        val html = response.text

        val videoUrl =
            extractFromJson(html)
                ?: extractFromHtml(html)
                ?: return

        val type =
            if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8
            else ExtractorLinkType.VIDEO

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "VidxGo",
                url = videoUrl,
                type = type,
            ) {
                this.headers = MEDIA_HEADERS
                this.referer = "https://v.vidxgo.co/"
            }
        )
    }

    /** Adattato per Altadefinizione: url = "8814476" */
    private fun buildTargetUrl(url: String): String {
        if (url.startsWith("http")) return url
        val id = url.trim().replace("tt", "")
        return "$mainUrl/$id"
    }
}
