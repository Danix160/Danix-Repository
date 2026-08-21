package com.guardaplay

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink

class LoadmExtractor : ExtractorApi() {

    override val name = "Loadm"
    override val mainUrl = "https://loadm.cam"
    override val requiresReferer = true

    companion object {
        private const val TAG = "LOADM_DEBUG"

        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:139.0) " +
                "Gecko/20100101 Firefox/139.0"
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

            /*
             * GuardaPlay restituisce:
             *
             * https://loadm.cam/#wnle9k
             *
             * quindi l'ID è dopo #
             */
            val videoId =
                url.substringAfter("#", "")
                    .ifBlank {
                        url
                            .trimEnd('/')
                            .substringAfterLast("/")
                            .removePrefix("#")
                    }

            if (videoId.isBlank()) {
                Log.e(TAG, "VIDEO ID non trovato")
                return
            }

            Log.d(
                TAG,
                "VIDEO ID = $videoId"
            )

            /*
             * Il fragment #ID non viene inviato via HTTP,
             * quindi per ottenere i cookie basta caricare
             * il dominio/pagina.
             */
            val pageUrl =
                url.substringBefore("#")

            Log.d(
                TAG,
                "GET PAGE = $pageUrl"
            )

            val pageResponse =
    app.get(
        pageUrl,
        headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: mainUrl)
        )
    )

val html = pageResponse.text

Log.e(TAG, "==============================")
Log.e(TAG, "LOADM PAGE STATUS = ${pageResponse.code}")
Log.e(TAG, "LOADM FINAL URL = ${pageResponse.url}")
Log.e(TAG, "LOADM HTML LENGTH = ${html.length}")

val document =
    org.jsoup.Jsoup.parse(html)

val scriptSrc =
    document
        .selectFirst("script[type=module][src]")
        ?.attr("src")

Log.e(
    TAG,
    "LOADM APP SCRIPT = $scriptSrc"
)

if (!scriptSrc.isNullOrBlank()) {

    val scriptUrl =
        if (scriptSrc.startsWith("http")) {
            scriptSrc
        } else {
            mainUrl.trimEnd('/') + "/" + scriptSrc.trimStart('/')
        }

    Log.e(
        TAG,
        "SCARICO JS = $scriptUrl"
    )

    val jsResponse =
        app.get(
            scriptUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        )

    val js =
        jsResponse.text

    Log.e(
        TAG,
        "JS STATUS = ${jsResponse.code}"
    )

    Log.e(
        TAG,
        "JS LENGTH = ${js.length}"
    )

    Log.e(
        TAG,
        "========== LOADM JAVASCRIPT =========="
    )

    Log.e(
        TAG,
        js.take(50000)
    )

    Log.e(
        TAG,
        "========== FINE JAVASCRIPT =========="
    )

    val keywords = listOf(
        "location.hash",
        "getVideo",
        "videoSource",
        "fetch(",
        "axios",
        "m3u8",
        "player",
        "/api/",
        "fireplayer"
    )

    keywords.forEach { keyword ->

        val position =
            js.indexOf(
                keyword,
                ignoreCase = true
            )

        if (position >= 0) {

            val start =
                (position - 1500)
                    .coerceAtLeast(0)

            val end =
                (position + 4000)
                    .coerceAtMost(js.length)

            Log.e(
                TAG,
                "===== TROVATO $keyword ====="
            )

            Log.e(
                TAG,
                js.substring(
                    start,
                    end
                )
            )
        }
    }
}
        } catch (e: Exception) {

            Log.e(
                TAG,
                "ERRORE LOADM DIAGNOSTICO: ${e.message}",
                e
            )
        }
    }
}
