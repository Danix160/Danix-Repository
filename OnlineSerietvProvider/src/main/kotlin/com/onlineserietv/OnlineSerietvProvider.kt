override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Se il link è già un link a un host esterno (uprot, flexy, maxstream)
        if (data.startsWith("http") && (data.contains("uprot") || data.contains("flexy") || data.contains("maxstream"))) {
            // Carica l'estrattore dedicato. Cloudstream ha già i plugin per questi host.
            loadExtractor(data, subtitleCallback, callback)
            return true
        }

        // Se invece è un link interno del sito, usiamo la WebView per "scovare" il video
        val webViewRes = app.get(
            data,
            interceptor = WebViewResolver(
                Regex(".*flexy\\.stream.*|.*uprot\\.net.*|.*master\\.m3u8.*|.*index\\.m3u8.*|.*maxstream.*")
            ),
            headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to pcUserAgent
            ),
            timeout = 45 // Aumentiamo il timeout per dare tempo alla pagina di caricare
        )

        // Se la WebView trova direttamente il flusso video
        if (webViewRes.url.contains(".m3u8") || webViewRes.url.contains(".mp4")) {
            callback.invoke(
                newExtractorLink(
                    this.name,
                    "Link Diretto",
                    webViewRes.url,
                    mainUrl,
                    Qualities.Unknown.value,
                    true
                )
            )
        }

        // Cerca iframe nascosti nella pagina dell'episodio
        webViewRes.document.select("iframe[src*='flexy'], iframe[src*='uprot'], iframe[src*='maxstream']").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            loadExtractor(src, data, subtitleCallback, callback)
        }

        return true
    }
