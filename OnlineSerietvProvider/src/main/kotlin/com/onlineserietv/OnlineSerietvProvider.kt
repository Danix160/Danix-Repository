override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Carichiamo la pagina dell'iframe
        val doc = app.get(data).document
        
        // Cerchiamo l'URL dell'iframe video (es. https://onlineserietv.live/stream-film/...)
        val iframeUrl = doc.selectFirst("iframe")?.attr("src") ?: return false

        // 2. Chiamata al server del player
        // Il sito usa spesso un form POST per selezionare il player (Flexy è il default)
        val playerResponse = app.get(iframeUrl, referer = data).document

        // --- GESTIONE CAPTCHA UPROT ---
        // Se troviamo il form del captcha, Cloudstream non può risolverlo automaticamente senza input.
        // Qui estraiamo l'immagine o il testo se necessario, ma solitamente
        // si tenta di bypassare o si usa un'interfaccia di input.
        val hasCaptcha = playerResponse.selectFirst("form input[name=captcha]") != null
        
        if (hasCaptcha) {
            // Logica placeholder: In una versione avanzata qui scatterebbe un pop-up di Cloudstream
            // per chiedere all'utente i numeri visualizzati.
        }

        // 3. Estrazione dei link dai player specifici
        // Cerchiamo le variabili "file" o "source" negli script (comune in Flexy/MaxStream)
        val scripts = playerResponse.select("script").html()
        
        // Regex per trovare il link sorgente (solitamente .m3u8 o .mp4)
        val videoRegex = Regex("""file(?:\s*):(?:\s*)"([^"]+)"""")
        val videoUrl = videoRegex.find(scripts)?.groupValues?.get(1)

        if (videoUrl != null) {
            // Usiamo newExtractorLink con le variabili corrette
            callback.invoke(
                ExtractorLink(
                    source = this.name,          // Nome del provider (OnlineSerieTv)
                    name = "Flexy (Uprot)",      // Nome del player visualizzato nell'app
                    url = videoUrl,              // L'URL diretto al video (.m3u8 o .mp4)
                    referer = iframeUrl,         // Fondamentale: il referer deve essere l'iframe
                    quality = getQualityFromName("720p"), // Puoi mappare la qualità se disponibile
                    isM3u8 = videoUrl.contains(".m3u8")
                )
            )
        }

        // 4. Alternativa MaxStream (se presente nel sorgente)
        // Se il primo non trova nulla, cerchiamo MaxStream
        if (videoUrl == null) {
            val maxStreamUrl = playerResponse.selectFirst("iframe[src*=maxstream]")?.attr("src")
            if (maxStreamUrl != null) {
                loadExtractor(maxStreamUrl, iframeUrl, subtitleCallback, callback)
            }
        }

        return true
    }
