package com.guardaplay

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

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
                    headers =
                        mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to
                                (referer ?: mainUrl)
                        )
                )

            Log.d(
                TAG,
                "GET STATUS = ${pageResponse.code}"
            )

            /*
             * Recuperiamo fireplayer_player
             * dai Set-Cookie.
             */
            val setCookies =
                pageResponse.headers
                    .filter {
                        it.first.equals(
                            "set-cookie",
                            ignoreCase = true
                        )
                    }
                    .map {
                        it.second
                    }

            Log.d(
                TAG,
                "SET COOKIE trovati = ${setCookies.size}"
            )

            val firePlayerCookie =
                setCookies
                    .firstOrNull {
                        it.startsWith(
                            "fireplayer_player=",
                            ignoreCase = true
                        )
                    }
                    ?.substringBefore(";")

            if (firePlayerCookie.isNullOrBlank()) {

                Log.e(
                    TAG,
                    "fireplayer_player cookie NON trovato"
                )

                setCookies.forEachIndexed { index, cookie ->
                    Log.d(
                        TAG,
                        "COOKIE [$index] = ${
                            cookie.take(500)
                        }"
                    )
                }

                return
            }

            Log.d(
                TAG,
                "fireplayer_player cookie trovato"
            )

            /*
             * Streamflix usa:
             *
             * POST /player/index.php
             * ?data=VIDEO_ID
             * &do=getVideo
             */
            val apiUrl =
                "$mainUrl/player/index.php" +
                    "?data=$videoId" +
                    "&do=getVideo"

            Log.d(
                TAG,
                "POST = $apiUrl"
            )

            val apiHeaders =
                mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to mainUrl,
                    "Origin" to mainUrl,
                    "Accept" to "*/*",
                    "Accept-Language" to
                        "en-US,en;q=0.5",
                    "X-Requested-With" to
                        "XMLHttpRequest",
                    "Content-Type" to
                        "application/x-www-form-urlencoded; charset=UTF-8",
                    "Cookie" to
                        firePlayerCookie
                )

            val apiResponse =
                app.post(
                    apiUrl,
                    headers = apiHeaders,
                    data = emptyMap()
                )

            Log.d(
                TAG,
                "POST STATUS = ${apiResponse.code}"
            )

            Log.d(
                TAG,
                "POST BODY = ${
                    apiResponse.text.take(3000)
                }"
            )

            val json =
                JSONObject(
                    apiResponse.text
                )

            val videoSource =
                json.optString(
                    "videoSource"
                )
                    .trim()

            if (videoSource.isBlank()) {

                Log.e(
                    TAG,
                    "videoSource non trovato"
                )

                return
            }

            Log.d(
                TAG,
                "VIDEO SOURCE = $videoSource"
            )

            val streamHeaders =
                mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to mainUrl,
                    "Origin" to mainUrl,
                    "Accept" to "*/*",
                    "Cookie" to firePlayerCookie
                )

            if (
                videoSource.contains(
                    ".m3u8",
                    ignoreCase = true
                )
            ) {

                try {

                    val links =
                        M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = videoSource,
                            referer = mainUrl,
                            headers = streamHeaders
                        )

                    if (links.isNotEmpty()) {

                        links.forEach(callback)

                        return
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "generateM3u8 fallito: ${e.message}"
                    )
                }

                /*
                 * Fallback diretto.
                 */
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = videoSource,
                        type =
                            ExtractorLinkType.M3U8
                    ) {

                        this.referer =
                            mainUrl

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
                        url = videoSource,
                        type =
                            ExtractorLinkType.VIDEO
                    ) {

                        this.referer =
                            mainUrl

                        this.headers =
                            streamHeaders

                        this.quality =
                            Qualities.Unknown.value
                    }
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "ERRORE LOADM: ${e.message}",
                e
            )
        }
    }
}
