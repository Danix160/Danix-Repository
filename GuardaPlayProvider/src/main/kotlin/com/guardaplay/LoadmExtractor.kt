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

Log.e(
    TAG,
    "LOADM TITLE = ${document.title()}"
)

document
    .select("script")
    .forEachIndexed { index, script ->

        val src =
            script.attr("src")

        if (src.isNotBlank()) {
            Log.e(
                TAG,
                "SCRIPT SRC [$index] = $src"
            )
        }

        val content =
            script.data()
                .ifBlank {
                    script.html()
                }

        if (content.isNotBlank()) {
            Log.e(
                TAG,
                "SCRIPT [$index] = ${
                    content
                        .replace("\n", " ")
                        .take(10000)
                }"
            )
        }

        val interesting =
            content.contains("location.hash", true) ||
            content.contains("window.location", true) ||
            content.contains("fetch(", true) ||
            content.contains("player", true) ||
            content.contains("getVideo", true) ||
            content.contains("fireplayer", true) ||
            content.contains("videoSource", true) ||
            content.contains("loadx", true)

        if (interesting) {
            Log.e(
                TAG,
                "SCRIPT INTERESSANTE [$index] = ${
                    content
                        .replace("\n", " ")
                        .take(15000)
                }"
            )
        }
    }

document
    .select("iframe")
    .forEachIndexed { index, iframe ->

        Log.e(
            TAG,
            "IFRAME [$index] = ${
                iframe.attr("src")
            }"
        )
    }

Log.e(
    TAG,
    "LOADM HTML = ${
        html.take(20000)
    }"
)
       return

        } catch (e: Exception) {

            Log.e(
                TAG,
                "ERRORE LOADM DIAGNOSTICO: ${e.message}",
                e
            )
        }
    }
}
