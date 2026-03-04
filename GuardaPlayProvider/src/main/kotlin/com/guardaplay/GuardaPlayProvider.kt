package com.guardaplay

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class GuardaPlayProvider : MainAPI() {
    override var mainUrl = "https://guardaplay.space"
    override var name = "GuardaPlay"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ultimi Film",
        "$mainUrl/category/animazione/" to "Animazione",
        "$mainUrl/category/azione/" to "Azione",
        "$mainUrl/category/commedia/" to "Commedia",
        "$mainUrl/category/horror/" to "Horror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        val home = document.select("article.movies").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("article.movies").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".entry-title")?.text() ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val posterUrl = selectFirst("img")?.attr("src")
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: ""
        val poster = document.selectFirst(".post-thumbnail img")?.attr("src")
        val plot = document.selectFirst(".description p")?.text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundAny = false
        val options = document.select("li.dooplay_player_option")
        
        if (options.isEmpty()) {
            val postId = document.selectFirst("div#player")?.attr("data-post")
                ?: document.selectFirst("input#wp-post-id")?.attr("value")
            if (postId != null) {
                if (fetchDooPlayAjax(postId, "1", "0", data, subtitleCallback, callback)) foundAny = true
            }
        } else {
            options.forEach { option ->
                val post = option.attr("data-post")
                val nume = option.attr("data-nume")
                val type = option.attr("data-type")
                if (fetchDooPlayAjax(post, nume, type, data, subtitleCallback, callback)) foundAny = true
            }
        }
        return foundAny
    }

    private suspend fun fetchDooPlayAjax(
        post: String,
        nume: String,
        type: String,
        refUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val response = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to post,
                    "nume" to nume,
                    "type" to type
                ),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to refUrl
                )
            ).text

            val iframeUrl = Regex("""(?:src|href)\s*[:=]\s*["']([^"']+)["']""").find(response)?.groupValues?.get(1)
                ?: Regex("""https?://[^\s"']+""").find(response)?.value

            if (iframeUrl != null) {
                val cleanUrl = iframeUrl.replace("\\/", "/")
                if (cleanUrl.contains("trembed=") || cleanUrl.contains("vidstack") || cleanUrl.contains("uns.bio") || cleanUrl.contains("loadm.cam")) {
                    VidStack().getUrl(cleanUrl, refUrl, subtitleCallback, callback)
                    true
                } else {
                    loadExtractor(cleanUrl, refUrl, subtitleCallback, callback)
                }
            } else false
        } catch (e: Exception) {
            false
        }
    }
}

// =============================================================================
// ESTRATTORE: VidStack (Pulizia totale parametri nominati)
// =============================================================================

open class VidStack : ExtractorApi() {
    override var name = "Vidstack"
    override var mainUrl = "https://vidstack.io"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // CORREZIONE RIGA 169: Rimosso 'referer = ...' e usato solo headers
        val doc = app.get(url, headers = mapOf("Referer" to (referer ?: ""))).text
        
        val hash = url.substringAfterLast("#").substringAfter("/")
            .let { if (it.isBlank()) Regex("""id\s*:\s*["']([^"']+)""").find(doc)?.groupValues?.get(1) else it } ?: return

        val apiResponse = app.get("https://vidstack.io/api/v1/video?id=$hash", headers = mapOf("Referer" to url)).text
        if (apiResponse.isBlank()) return

        val key = "kiemtienmua911ca"
        val iv = "1234567890oiuytr" 

        try {
            val decrypted = AesHelper.decryptAES(apiResponse.trim(), key, iv)
            val m3u8 = Regex("\"source\":\"(.*?)\"").find(decrypted)?.groupValues?.get(1)?.replace("\\/", "/")
            
            if (m3u8 != null) {
                // CORREZIONE: Rimosso 'referer = url' anche qui, messo in headers
                callback.invoke(
                    ExtractorLink(
                        source = "GuardaPlay",
                        name = "Server HD",
                        url = m3u8,
                        referer = "", // Lasciato vuoto per evitare il crash del parametro nominato
                        quality = Qualities.P1080.value,
                        type = ExtractorLinkType.M3U8,
                        headers = mapOf(
                            "Referer" to url,
                            "Origin" to "https://guardaplay.space",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                        )
                    )
                )
            }
        } catch (e: Exception) { }
    }
}

object AesHelper {
    fun decryptAES(inputHex: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
        val secretKey = SecretKeySpec(key.toByteArray(), "AES")
        val ivSpec = IvParameterSpec(iv.toByteArray())
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        val decodedHex = inputHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return String(cipher.doFinal(decodedHex))
    }
}
