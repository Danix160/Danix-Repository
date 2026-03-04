package com.guardaplay

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
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
        
        // Fix per l'errore FileNotFoundException del Logcat
        val posterUrl = selectFirst("img")?.attr("src")?.let { src ->
            when {
                src.startsWith("//") -> "https:$src"
                src.startsWith("/") -> "https://image.tmdb.org$src"
                else -> src
            }
        }
        
        return newMovieSearchResponse(title, href, TvType.Movie) { 
            this.posterUrl = posterUrl 
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: ""
        
        val poster = document.selectFirst(".post-thumbnail img")?.attr("src")?.let { src ->
            if (src.startsWith("//")) "https:$src" else src
        }
        
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
        
        // Nuovo parsing basato sullo snippet HTML: cerca gli iframe nelle opzioni
        val playerSources = document.select("aside#aa-options div.video iframe")
        
        if (playerSources.isEmpty()) {
            // Fallback: cerca qualsiasi iframe nella sezione player
            document.select("section.player iframe").forEach { iframe ->
                val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
                if (src.isNotEmpty()) processIframe(src, data, subtitleCallback, callback)
            }
        } else {
            playerSources.forEach { iframe ->
                val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
                if (src.isNotEmpty()) processIframe(src, data, subtitleCallback, callback)
            }
        }
        return true
    }

    private suspend fun processIframe(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = if (url.startsWith("//")) "https:$url" else url
        
        // Gestione dei link interni "trembed"
        if (cleanUrl.contains("trembed") || cleanUrl.contains("trid=")) {
            val iframeDoc = app.get(cleanUrl, headers = mapOf("Referer" to referer)).document
            
            // Cerca il vero link video dentro l'iframe (VidStack o simili)
            val finalUrl = iframeDoc.selectFirst("iframe")?.attr("src") 
                ?: iframeDoc.selectFirst("source")?.attr("src")
                ?: Regex("""file:\s*["']([^"']+)""").find(iframeDoc.html())?.groupValues?.get(1)

            finalUrl?.let { loadExtractor(it, cleanUrl, subtitleCallback, callback) }
        } else {
            loadExtractor(cleanUrl, referer, subtitleCallback, callback)
        }
    }
}

// =============================================================================
// ESTRATTORE: VidStack (Versione Corretta per il Build)
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
        val res = app.get(url, headers = mapOf("Referer" to (referer ?: ""))).text
        
        val hash = url.substringAfterLast("#").substringAfter("/")
            .let { if (it.isBlank()) Regex("""id\s*:\s*["']([^"']+)""").find(res)?.groupValues?.get(1) else it } ?: return

        val apiResponse = app.get("https://vidstack.io/api/v1/video?id=$hash", headers = mapOf("Referer" to url)).text
        if (apiResponse.isBlank()) return

        try {
            val decrypted = AesHelper.decryptAES(apiResponse.trim(), "kiemtienmua911ca", "1234567890oiuytr")
            val m3u8 = Regex("\"source\":\"(.*?)\"").find(decrypted)?.groupValues?.get(1)?.replace("\\/", "/")
            
            if (m3u8 != null) {
                // Firma sicura di newExtractorLink per evitare errori di compilazione
                callback.invoke(
                    newExtractorLink(
                        source = "GuardaPlay",
                        name = "Server HD",
                        url = m3u8
                    ) {
                        this.quality = Qualities.P1080.value
                        this.type = ExtractorLinkType.M3U8
                        this.headers = mapOf(
                            "Referer" to url,
                            "Origin" to "https://guardaplay.space"
                        )
                    }
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
