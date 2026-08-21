package com.onlineserietv

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

class Uprot : ExtractorApi() {

    override val name = "Uprot"
    override val mainUrl = "https://uprot.net"
    override val requiresReferer = true

    companion object {
        private const val TAG = "UPROT_DEBUG"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/139.0.0.0 Mobile Safari/537.36"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        Log.d(TAG, "==============================")
        Log.d(TAG, "URL ricevuto: $url")
        Log.d(TAG, "Referer ricevuto: $referer")

        val mseUrl = when {
            url.contains("/msf/") ->
                url.replace("/msf/", "/mse/")

            else -> url
        }

        Log.d(TAG, "URL da richiedere: $mseUrl")

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to (referer ?: "https://onlineserietv.mom/")
        )

        try {

            val response = app.get(
                mseUrl,
                headers = headers
            )

            Log.d(TAG, "STATUS = ${response.code}")
            Log.d(TAG, "FINAL URL = ${response.url}")

            val html = response.text

            Log.d(TAG, "HTML length = ${html.length}")

            Log.d(
                TAG,
                "decodedBaseUrl presente = ${html.contains("decodedBaseUrl")}"
            )

            Log.d(
                TAG,
                "decodedEncryptedVal presente = ${html.contains("decodedEncryptedVal")}"
            )

            Log.d(
                TAG,
                "maxstream presente = ${html.contains("maxstream", true)}"
            )

            val b64Base = Regex(
                """decodedBaseUrl\s*=\s*atob\(\s*["']([^"']+)["']\s*\)"""
            ).find(html)?.groupValues?.getOrNull(1)

            val b64Val = Regex(
                """decodedEncryptedVal\s*=\s*atob\(\s*["']([^"']+)["']\s*\)"""
            ).find(html)?.groupValues?.getOrNull(1)

            var maxstreamUrl: String? = null

            if (!b64Base.isNullOrBlank() &&
                !b64Val.isNullOrBlank()
            ) {

                Log.d(TAG, "Base64 trovati")

                val decodedBase = String(
                    Base64.decode(
                        b64Base,
                        Base64.DEFAULT
                    ),
                    Charsets.UTF_8
                )

                val decodedVal = String(
                    Base64.decode(
                        b64Val,
                        Base64.DEFAULT
                    ),
                    Charsets.UTF_8
                )

                maxstreamUrl = decodedBase + decodedVal

                Log.d(
                    TAG,
                    "MAXSTREAM DA BASE64 = $maxstreamUrl"
                )
            }

            if (maxstreamUrl == null) {

                val doc = Jsoup.parse(html)

                maxstreamUrl =
                    doc.selectFirst(
                        "iframe[src*=maxstream]"
                    )?.attr("src")
                        ?: doc.selectFirst(
                            "iframe[src*=max]"
                        )?.attr("src")
                        ?: doc.selectFirst(
                            "a[href*=maxstream]"
                        )?.attr("href")
                        ?: doc.selectFirst(
                            "a[href*=max]"
                        )?.attr("href")

                Log.d(
                    TAG,
                    "MAXSTREAM DA DOM = $maxstreamUrl"
                )
            }

            if (!maxstreamUrl.isNullOrBlank()) {

                if (maxstreamUrl.startsWith("//")) {
                    maxstreamUrl = "https:$maxstreamUrl"
                }

                Log.d(
                    TAG,
                    "PASSO A MAXSTREAM: $maxstreamUrl"
                )

                loadExtractor(
                    maxstreamUrl,
                    mseUrl,
                    subtitleCallback,
                    callback
                )

            } else {

                Log.e(
                    TAG,
                    "NESSUN MAXSTREAM TROVATO"
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "ERRORE UPROT: ${e.message}",
                e
            )
        }
    }
}
