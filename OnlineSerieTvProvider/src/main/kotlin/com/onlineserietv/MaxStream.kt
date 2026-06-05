package com.onlineserietv

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app

class MaxStream : ExtractorApi() {
    override val name = "MaxStream"
    override val mainUrl = "https://maxstream.video"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Aggiungiamo uno user-agent standard per evitare blocchi base dall'host
            val response = app.get(
                url, 
                referer = referer ?: mainUrl,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            )
            val html = response.text

            println("DEBUG MAXSTREAM — Pagina caricata, avvio parsing. Lunghezza HTML: ${html.length}")

            // Regex molto più flessibile per beccare il file video (m3u8 o mp4)
            val pattern = """(?:file|src)\s*:\s*["']([^"']+)["']|src\s*=\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""".toRegex(RegexOption.IGNORE_CASE)
            val match = pattern.find(html)

            if (match != null) {
                // Estraiamo il primo gruppo catturato che non sia nullo
                val videoUrl = (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim()
                
                if (videoUrl.isNotEmpty() && videoUrl.startsWith("http")) {
                    println("DEBUG MAXSTREAM — Trovato URL Video valido: $videoUrl")
                    
                    val isM3u8 = videoUrl.contains(".m3u8")

                    val link = newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = videoUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                    
                    callback.invoke(link)
                    return // Uscita pulita dopo aver inviato il link
                }
            }
            
            // Se arriva qui, la regex ha fallito o l'URL non era valido
            println("DEBUG MAXSTREAM — Nessun link video estratto dall'HTML.")

        } catch (e: Exception) {
            println("DEBUG MAXSTREAM — Eccezione durante l'estrazione: ${e.message}")
        }
    }
}
