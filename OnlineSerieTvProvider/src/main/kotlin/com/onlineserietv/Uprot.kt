package com.onlineserietv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jsoup.Jsoup
import kotlin.coroutines.resume

// Modello per rappresentare un tassello della griglia CAPTCHA
data class CaptchaTile(
    val id: String,
    val bitmap: Bitmap,
    var isSelected: Boolean = false
)

class Uprot : ExtractorApi() {
    override val name = "Uprot"
    override val mainUrl = "https://uprot.net"
    override val requiresReferer = true

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "it-IT,it;q=0.9",
        "Connection" to "keep-alive"
    )

    // ==========================================
    // 1. REFLECTION E INTERFACCIA UTENTE NATIVA
    // ==========================================

    private fun getActivity(): Activity? {
        return try {
            val clazz = Class.forName("com.lagradost.cloudstream3.CommonActivity")
            val instance = clazz.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
            clazz.getDeclaredMethod("getActivity").invoke(instance) as? Activity
        } catch (e: Exception) {
            Log.e("Uprot", "Reflection Activity fallita: ${e.message}")
            null
        }
    }

    /**
     * Dialog Nativo per CAPTCHA standard (testo/numeri su singola immagine)
     */
    private suspend fun showCaptchaDialog(base64Data: String): String? {
        return suspendCancellableCoroutine { continuation ->
            val activity = getActivity()
            if (activity == null) {
                Log.e("Uprot", "Activity non disponibile per dialog numerico")
                if (continuation.isActive) continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            activity.runOnUiThread {
                try {
                    val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

                    val layout = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(50, 40, 50, 40)
                    }

                    val imageView = ImageView(activity).apply {
                        setImageBitmap(bitmap)
                        adjustViewBounds = true
                    }

                    val inputEditText = EditText(activity).apply {
                        hint = "Inserisci i numeri che vedi"
                        inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    }

                    layout.addView(imageView)
                    layout.addView(inputEditText)

                    val dialog = AlertDialog.Builder(activity)
                        .setTitle("Verifica Richiesta")
                        .setMessage("Risolvi il CAPTCHA per avviare il video")
                        .setView(layout)
                        .setCancelable(false)
                        .setPositiveButton("Sblocca") { _, _ ->
                            val codice = inputEditText.text.toString().trim()
                            if (continuation.isActive) continuation.resume(codice)
                        }
                        .setNegativeButton("Annulla") { _, _ ->
                            if (continuation.isActive) continuation.resume(null)
                        }
                        .create()

                    continuation.invokeOnCancellation { dialog.dismiss() }
                    dialog.show()

                } catch (e: Exception) {
                    Log.e("Uprot", "Errore dialog numerico: ${e.message}")
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    /**
     * Dialog Nativo per CAPTCHA a Selezione Immagini (Griglia)
     */
    private suspend fun showImageGridCaptchaDialog(
        instructionText: String,
        tiles: List<CaptchaTile>
    ): List<String>? {
        return suspendCancellableCoroutine { continuation ->
            val activity = getActivity()
            if (activity == null) {
                Log.e("Uprot", "Activity non disponibile per dialog griglia")
                if (continuation.isActive) continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            activity.runOnUiThread {
                try {
                    val layout = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(40, 30, 40, 30)
                    }

                    val gridView = GridView(activity).apply {
                        numColumns = 3
                        horizontalSpacing = 10
                        verticalSpacing = 10
                        stretchMode = GridView.STRETCH_COLUMN_WIDTH
                    }

                    val adapter = object : BaseAdapter() {
                        override fun getCount(): Int = tiles.size
                        override fun getItem(position: Int): Any = tiles[position]
                        override fun getItemId(position: Int): Long = position.toLong()

                        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                            val imageView = (convertView as? ImageView) ?: ImageView(activity).apply {
                                layoutParams = GridView.LayoutParams(250, 250)
                                scaleType = ImageView.ScaleType.CENTER_CROP
                            }

                            val item = tiles[position]
                            imageView.setImageBitmap(item.bitmap)

                            // Evidenziazione visiva del tassello selezionato
                            if (item.isSelected) {
                                imageView.setBackgroundColor(Color.parseColor("#0088FF"))
                                imageView.setPadding(8, 8, 8, 8)
                            } else {
                                imageView.setBackgroundColor(Color.TRANSPARENT)
                                imageView.setPadding(0, 0, 0, 0)
                            }

                            imageView.setOnClickListener {
                                item.isSelected = !item.isSelected
                                notifyDataSetChanged()
                            }

                            return imageView
                        }
                    }

                    gridView.adapter = adapter
                    layout.addView(gridView)

                    val dialog = AlertDialog.Builder(activity)
                        .setTitle("Verifica Immagini")
                        .setMessage(instructionText.ifEmpty { "Seleziona le immagini richieste" })
                        .setView(layout)
                        .setCancelable(false)
                        .setPositiveButton("Invia") { _, _ ->
                            val selectedIds = tiles.filter { it.isSelected }.map { it.id }
                            if (continuation.isActive) continuation.resume(selectedIds)
                        }
                        .setNegativeButton("Annulla") { _, _ ->
                            if (continuation.isActive) continuation.resume(null)
                        }
                        .create()

                    continuation.invokeOnCancellation { dialog.dismiss() }
                    dialog.show()

                } catch (e: Exception) {
                    Log.e("Uprot", "Errore dialog griglia: ${e.message}")
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    // ==========================================
    // 2. LOGICA DI BYPASS E RICHIESTE HTTP
    // ==========================================

    private suspend fun bypassUprot(link: String): String? {
        val updatedLink = fixUrl(if ("msf" in link) link.replace("msf", "mse") else link)
        Log.d("Uprot", "🟦 bypassUprot() Avvio: $updatedLink")

        val response = app.get(updatedLink, headers = baseHeaders, timeout = 10_000)
        val document = response.document
        Log.d("Uprot", "🟡 GET completato, URL finale: ${response.url}")

        val tokenElement = document.selectFirst("input[name=token]")
        val token = tokenElement?.attr("value") ?: ""

        // CASE A: Rilevato CAPTCHA a Griglia di Immagini
        val gridTilesElements = document.select("div.captcha-tile img, img.captcha-grid-item")
        val instruction = document.selectFirst(".captcha-instruction, #captcha-text")?.text() ?: ""

        if (gridTilesElements.isNotEmpty() && token.isNotEmpty()) {
            Log.d("Uprot", "🟠 CAPTCHA a griglia rilevato (${gridTilesElements.size} immagini)")

            val tilesList = mutableListOf<CaptchaTile>()
            gridTilesElements.forEachIndexed { index, el ->
                val src = el.attr("src")
                val tileId = el.attr("data-id").ifEmpty { index.toString() }
                
                val bitmap = if (src.contains("base64,")) {
                    val base64Data = src.substringAfter("base64,")
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else {
                    val tileUrl = fixUrl(src)
                    val imgBytes = app.get(tileUrl, headers = baseHeaders).body.bytes()
                    BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)
                }

                if (bitmap != null) {
                    tilesList.add(CaptchaTile(tileId, bitmap))
                }
            }

            val selectedTileIds = showImageGridCaptchaDialog(instruction, tilesList)
            if (selectedTileIds.isNullOrEmpty()) {
                Log.d("Uprot", "❌ Annullato dall'utente o nessuna selezione")
                return null
            }

            val postResponse = app.post(
                updatedLink,
                headers = baseHeaders,
                data = mapOf(
                    "token" to token,
                    "capt" to selectedTileIds.joinToString(",")
                ),
                timeout = 10_000
            )

            return parsePostResult(postResponse.document, postResponse.url)
        }

        // CASE B: CAPTCHA Testuale/Numerico su Singola Immagine
        val captchaImg = document.selectFirst("img[alt=CAPTCHA], img#captcha_img")
        if (captchaImg != null && token.isNotEmpty()) {
            Log.d("Uprot", "🟠 Captcha singolo numeri/testo rilevato")
            val imgSrc = captchaImg.attr("src")
            val base64Data = imgSrc.substringAfter("base64,")

            val captchaRisolto = showCaptchaDialog(base64Data)
            if (captchaRisolto.isNullOrEmpty()) {
                Log.d("Uprot", "❌ Captcha numerico annullato dall'utente")
                return null
            }

            val postResponse = app.post(
                updatedLink,
                headers = baseHeaders,
                data = mapOf("token" to token, "capt" to captchaRisolto),
                timeout = 10_000
            )

            return parsePostResult(postResponse.document, postResponse.url)
        }

        // CASE C: Nessun CAPTCHA, cerca link diretto
        Log.d("Uprot", "🟢 Nessun CAPTCHA form trovato, estrazione diretta")
        val directLink = document.selectFirst("a[href*='maxstream'], a[href*='maxwe'], a[href*='uprots']")?.attr("href")
        return if (!directLink.isNullOrEmpty()) fixUrl(directLink) else null
    }

    private fun parsePostResult(postDoc: org.jsoup.nodes.Document, postUrl: String): String? {
        if (!postUrl.contains("uprot.net")) {
            Log.d("Uprot", "✅ Redirect diretto post-form: $postUrl")
            return postUrl
        }

        val buttokLink = postDoc.selectFirst("#buttok")?.parent()?.attr("href")
        if (!buttokLink.isNullOrEmpty()) return fixUrl(buttokLink)

        val uprotsLink = postDoc.selectFirst("a[href*='uprots']")?.attr("href")
        if (!uprotsLink.isNullOrEmpty()) return fixUrl(uprotsLink)

        val fallbackLink = postDoc.selectFirst("a[href*='maxstream'], a[href*='maxwe']")?.attr("href")
        return if (!fallbackLink.isNullOrEmpty()) fixUrl(fallbackLink) else null
    }

    // ==========================================
    // 3. EXTRACTOR MAIN ENTRY POINT
    // ==========================================

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val target = fixUrl(url)
        Log.d("Uprot", "Avvio getUrl su: $target")

        // 1. Prova prima il bypass nativo (Captcha immagine / Form POST)
        var extractedUrl = bypassUprot(target)

        // 2. Se il bypass nativo fallisce o trova Cloudflare Turnstile/JS, passa alla WebView
        if (extractedUrl.isNullOrEmpty()) {
            Log.d("Uprot", "Bypass nativo non risolto, tentativo con WebViewResolver...")
            try {
                val webViewResponse = app.get(
                    target,
                    headers = baseHeaders,
                    interceptor = WebViewResolver(
                        interceptUrl = Regex("""https?://(?:www\.)?(?:maxstream\.video|uprot\.net/(?:uprotem|mse|msf)).*"""),
                        additionalUrls = listOf(Regex(""".*maxstream\.video.*"""))
                    )
                )

                val html = webViewResponse.text
                extractedUrl = parsePostResult(Jsoup.parse(html), webViewResponse.url)
            } catch (e: Exception) {
                Log.e("Uprot", "Errore WebViewResolver: ${e.message}")
            }
        }

        // 3. Invio del link all'Extractor finale (Maxstream)
        if (!extractedUrl.isNullOrEmpty() && !extractedUrl.literaryEquals(target)) {
            Log.d("Uprot", "🔗 Caricamento Extractor finale: $extractedUrl")
            val refererToUse = if (extractedUrl.contains("maxstream")) target else url
            loadExtractor(extractedUrl, refererToUse, subtitleCallback, callback)
        } else {
            Log.e("Uprot", "❌ Impossibile ottenere un link video valido")
        }
    }

    // ==========================================
    // 4. UTILITIES FORMATTAZIONE E CONFRONTO
    // ==========================================

    private fun fixUrl(url: String, domain: String = "https://uprot.net"): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$domain$url"
            !url.startsWith("http") -> "$domain/$url"
            else -> url
        }
    }

    private fun String?.literaryEquals(other: String?): Boolean {
        if (this == null || other == null) return this == other
        return this.trimEnd('/') == other.trimEnd('/')
    }
}
