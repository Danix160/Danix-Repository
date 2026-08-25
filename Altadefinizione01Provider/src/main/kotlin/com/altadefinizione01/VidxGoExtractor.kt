package com.altadefinizione01

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

class VidxGoExtractor : ExtractorApi() {

    override val name = "VidxGo"
    override val mainUrl = "https://v.vidxgo.co"
    override val requiresReferer = true

    companion object {

        private const val TAG =
            "VIDXGO_DEBUG"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        Log.d(TAG, "==============================")
        Log.d(TAG, "URL = $url")
        Log.d(TAG, "REFERER = $referer")

        try {

            val vidxReferer =
                "$mainUrl/"

            val headers =
                mutableMapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to vidxReferer
                )

            /*
             * Per i film VidxGo viene caricato
             * normalmente come iframe.
             *
             * Per /t/... invece il server restituisce
             * direttamente i dati del video.
             */
            if (!url.contains("/t/")) {
                headers["sec-fetch-dest"] =
                    "iframe"
            } else {
                headers["sec-fetch-dest"] =
                    "empty"
            }

            val response =
                app.get(
                    url,
                    headers = headers
                )

            val body =
                response.text

            Log.d(
                TAG,
                "STATUS = ${response.code}"
            )

            Log.d(
                TAG,
                "FINAL URL = ${response.url}"
            )

            Log.d(
                TAG,
                "BODY LENGTH = ${body.length}"
            )
            
            Log.e(
    TAG,
    "BODY 403 = ${
        response.text
            .replace("\n", " ")
            .take(3000)
    }"
)

            // ====================================================
            // SERIE TV
            // ====================================================

            if (url.contains("/t/")) {

                val videoUrlRaw =
                    Regex(
                        """"url"\s*:\s*"([^"]+)""""
                    )
                        .find(body)
                        ?.groupValues
                        ?.getOrNull(1)

                if (
                    videoUrlRaw.isNullOrBlank()
                ) {

                    Log.e(
                        TAG,
                        "SERIE: campo url non trovato"
                    )

                    Log.e(
                        TAG,
                        "BODY = ${body.take(3000)}"
                    )

                    return
                }

                val videoUrl =
                    videoUrlRaw
                        .replace("\\/", "/")
                        .replace("\\u0026", "&")

                Log.d(
                    TAG,
                    "SERIE VIDEO URL = $videoUrl"
                )

                emitVideo(
                    videoUrl = videoUrl,
                    callback = callback
                )

                return
            }

            // ====================================================
            // FILM
            // ====================================================

            val document =
                Jsoup.parse(body)

            val scripts =
                document
                    .select("script")
                    .mapNotNull {

                        val content =
                            it.data()
                                .ifBlank {
                                    it.html()
                                }

                        content.takeIf {
                            script ->
                            script.contains(
                                "(function()",
                                ignoreCase = true
                            ) &&
                            script.contains(
                                "var k",
                                ignoreCase = true
                            ) &&
                            script.contains(
                                "atob(",
                                ignoreCase = true
                            )
                        }
                    }

            Log.d(
                TAG,
                "SCRIPT CIFRATI = ${scripts.size}"
            )

            if (scripts.isEmpty()) {

                Log.e(
                    TAG,
                    "Nessuno script cifrato trovato"
                )

                return
            }

            /*
             * Streamflix prende il quinto script.
             *
             * Qui proviamo tutti gli script compatibili:
             * è più robusto se VidxGo cambia ordine.
             */
            var finalVideoUrl: String? = null

            scripts.forEachIndexed {
                    index,
                    script ->

                if (
                    finalVideoUrl != null
                ) {
                    return@forEachIndexed
                }

                try {

                    val key =
                        Regex(
                            """var\s+k\s*=\s*['"]([^'"]+)['"]"""
                        )
                            .find(script)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?: return@forEachIndexed

                    val encodedData =
                        Regex(
                            """atob\(\s*['"]([^'"]+)['"]\s*\)"""
                        )
                            .find(script)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?: return@forEachIndexed

                    Log.d(
                        TAG,
                        "SCRIPT [$index] key length = ${key.length}"
                    )

                    val decodedBytes =
                        Base64.decode(
                            encodedData,
                            Base64.DEFAULT
                        )

                    val decrypted =
                        ByteArray(
                            decodedBytes.size
                        )

                    for (
                        i in decodedBytes.indices
                    ) {

                        val dataByte =
                            decodedBytes[i]
                                .toInt() and 0xFF

                        val keyByte =
                            key[
                                i % key.length
                            ].code and 0xFF

                        decrypted[i] =
                            (
                                dataByte xor
                                    keyByte
                            ).toByte()
                    }

                    val decryptedText =
                        String(
                            decrypted,
                            Charsets.UTF_8
                        )

                    Log.d(
                        TAG,
                        "SCRIPT [$index] decifrato length = ${decryptedText.length}"
                    )

                    val currentSrc =
                        Regex(
                            """currentSrc\s*=\s*['"]([^'"]+)['"]"""
                        )
                            .find(
                                decryptedText
                            )
                            ?.groupValues
                            ?.getOrNull(1)

                    if (
                        !currentSrc.isNullOrBlank()
                    ) {

                        finalVideoUrl =
                            currentSrc
                                .replace(
                                    "\\/",
                                    "/"
                                )
                                .replace(
                                    "\\u0026",
                                    "&"
                                )

                        Log.d(
                            TAG,
                            "FILM currentSrc trovato nello script [$index]"
                        )
                    }

                } catch (
                    e: Exception
                ) {

                    Log.e(
                        TAG,
                        "Errore script [$index]: ${e.message}"
                    )
                }
            }

            if (
                finalVideoUrl.isNullOrBlank()
            ) {

                /*
                 * Fallback fedele alla logica
                 * Streamflix: prova il quinto script.
                 */
                if (
                    scripts.size >= 5
                ) {

                    finalVideoUrl =
                        decryptScript(
                            scripts[4]
                        )
                }
            }

            if (
                finalVideoUrl.isNullOrBlank()
            ) {

                Log.e(
                    TAG,
                    "FILM: currentSrc non trovato"
                )

                return
            }

            Log.d(
                TAG,
                "FILM VIDEO URL = $finalVideoUrl"
            )

            emitVideo(
                videoUrl = finalVideoUrl!!,
                callback = callback
            )

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "ERRORE VIDXGO: ${e.message}",
                e
            )
        }
    }

    // ============================================================
    // DECRYPT
    // ============================================================

    private fun decryptScript(
        script: String
    ): String? {

        return try {

            val key =
                Regex(
                    """var\s+k\s*=\s*['"]([^'"]+)['"]"""
                )
                    .find(script)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return null

            val encodedData =
                Regex(
                    """atob\(\s*['"]([^'"]+)['"]\s*\)"""
                )
                    .find(script)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return null

            val decoded =
                Base64.decode(
                    encodedData,
                    Base64.DEFAULT
                )

            val decrypted =
                ByteArray(
                    decoded.size
                )

            for (
                i in decoded.indices
            ) {

                decrypted[i] =
                    (
                        (
                            decoded[i]
                                .toInt() and
                                0xFF
                        ) xor
                            (
                                key[
                                    i %
                                        key.length
                                ].code and
                                    0xFF
                            )
                    ).toByte()
            }

            val text =
                String(
                    decrypted,
                    Charsets.UTF_8
                )

            Regex(
                """currentSrc\s*=\s*['"]([^'"]+)['"]"""
            )
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(
                    "\\/",
                    "/"
                )
                ?.replace(
                    "\\u0026",
                    "&"
                )

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "decryptScript error: ${e.message}"
            )

            null
        }
    }

    // ============================================================
    // OUTPUT
    // ============================================================

    private suspend fun emitVideo(
        videoUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {

        val streamHeaders =
            mapOf(
                "User-Agent" to
                    USER_AGENT,

                "Origin" to
                    mainUrl,

                "Referer" to
                    "$mainUrl/",

                "sec-fetch-dest" to
                    "empty",

                "sec-fetch-site" to
                    "cross-site"
            )

        val isM3u8 =
            videoUrl.contains(
                ".m3u8",
                ignoreCase = true
            )

        if (isM3u8) {

            try {

                val links =
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl =
                            videoUrl,
                        referer =
                            "$mainUrl/",
                        headers =
                            streamHeaders
                    )

                if (
                    links.isNotEmpty()
                ) {

                    links.forEach(
                        callback
                    )

                    return
                }

            } catch (
                e: Exception
            ) {

                Log.e(
                    TAG,
                    "M3U8 parsing error: ${e.message}"
                )
            }

            /*
             * fallback diretto
             */
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type =
                        ExtractorLinkType.M3U8
                ) {

                    this.referer =
                        "$mainUrl/"

                    this.headers =
                        streamHeaders

                    this.quality =
                        Qualities.Unknown.value
                }
            )

        } else {

            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type =
                        ExtractorLinkType.VIDEO
                ) {

                    this.referer =
                        "$mainUrl/"

                    this.headers =
                        streamHeaders

                    this.quality =
                        Qualities.Unknown.value
                }
            )
        }
    }
}
