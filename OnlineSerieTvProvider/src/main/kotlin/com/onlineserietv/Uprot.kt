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

    if (
        !url.contains("/msf/") &&
        !url.contains("/mse/") &&
        !url.contains("/msfi/")
    ) {
        Log.d(TAG, "URL UPROT ignorato: $url")
        return
    }

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

            Log.d(TAG, "----- ANALISI SCRIPT UPROT -----")

val document = Jsoup.parse(html)

document.select("script").forEachIndexed { index, script ->

    val src = script.attr("src")

    if (src.isNotBlank()) {
        Log.d(
            TAG,
            "SCRIPT SRC [$index] = $src"
        )
    }

    val content = script.html()

    val interesting =
        content.contains("fetch(", true) ||
        content.contains("ajax", true) ||
        content.contains("api", true) ||
        content.contains("xhr", true) ||
        content.contains("XMLHttpRequest", true) ||
        content.contains("/ms", true)

    if (interesting) {

        Log.d(
            TAG,
            "SCRIPT INTERESSANTE [$index] = ${
                content
                    .replace("\n", " ")
                    .take(2000)
            }"
        )
    }
}

Log.d(TAG, "----- FORM -----")

document.select("form").forEachIndexed { index, form ->

    Log.d(
        TAG,
        "FORM [$index] action=${form.attr("action")} method=${form.attr("method")}"
    )

    form.select("input").forEach { input ->

        Log.d(
            TAG,
            "INPUT name=${input.attr("name")} type=${input.attr("type")} value=${input.attr("value").take(150)}"
        )
    }
}

Log.d(TAG, "----- DATA ATTRIBUTES -----")

document.select("*").forEach { element ->

    element.attributes().forEach { attr ->

        if (
            attr.key.startsWith("data-") &&
            (
                attr.value.contains("http", true) ||
                attr.value.contains("api", true) ||
                attr.value.contains("ms", true)
            )
        ) {

            Log.d(
                TAG,
                "${element.tagName()} ${attr.key}=${attr.value}"
            )
        }
    }
}

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

    maxstreamUrl =
        document.selectFirst(
            "iframe[src*=maxstream]"
        )?.attr("src")
            ?: document.selectFirst(
                "iframe[src*=max]"
            )?.attr("src")
            ?: document.selectFirst(
                "a[href*=maxstream]"
            )?.attr("href")
            ?: document.selectFirst(
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
