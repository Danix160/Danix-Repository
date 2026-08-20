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
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.FormBody
import kotlin.coroutines.resume

data class CaptchaTile(
    val id: String,
    val bitmap: Bitmap,
    var isSelected: Boolean = false
)

class Uprot : ExtractorApi() {
    override val name = "Uprot"
    override val mainUrl = "https://uprot.net"
    override val requiresReferer = true

    // CookieJar in-memory per preservare la sessione tra GET e POST
    private val cookieJar = object : CookieJar {
        private val cookieStore = mutableMapOf<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host] ?: emptyList()
        }
    }

    // Client HTTP con gestione automatica dei cookie
    private val sessionClient by lazy {
        app.baseClient.newBuilder()
            .cookieJar(cookieJar)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "it-IT,it;q=0.9",
        "Connection" to "keep-alive"
    )

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

    private suspend fun showImageGridCaptchaDialog(
        instructionText: String,
        tiles: List<CaptchaTile>
    ): List<String>? {
        return suspendCancellableCoroutine { continuation ->
            val activity = getActivity()
            if (activity == null) {
                Log.e("Uprot", "Activity non disponibile")
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
                                layoutParams = AbsListView.LayoutParams(250, 250)
                                scaleType = ImageView.ScaleType.CENTER_CROP
                            }

                            val item = tiles[position]
                            imageView.setImageBitmap(item.bitmap)

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
                        .setTitle("Verifica Uprot")
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
                    Log.e("Uprot", "Errore dialog: ${e.message}")
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    private suspend fun bypassUprot(link: String): String? {
        val updatedLink = fixUrl(if ("msf" in link) link.replace("msf", "mse") else link)
        Log.d("Uprot", "Avvio bypass: $updatedLink")

        // Usa sessionClient per salvare la sessione nei cookie
        val response = app.get(updatedLink, headers = baseHeaders, client = sessionClient, timeout = 10_000)
        val document = response.document

        val tokenElement = document.selectFirst("input[name=token]")
        val token = tokenElement?.attr("value") ?: ""

        val gridTilesElements = document.select("div.captcha-tile img, img.captcha-grid-item, form img[src*='base64']")
        val instruction = document.selectFirst(".captcha-instruction, #captcha-text, form p, form b")?.text() ?: ""

        if (gridTilesElements.isNotEmpty() && token.isNotEmpty()) {
            Log.d("Uprot", "Trovata griglia CAPTCHA (${gridTilesElements.size} elementi)")

            val tilesList = mutableListOf<CaptchaTile>()
            gridTilesElements.forEachIndexed { index, el ->
                val src = el.attr("src")
                val tileId = el.attr("data-id").ifEmpty { el.attr("value") }.ifEmpty { index.toString() }

                val bitmap = if (src.contains("base64,")) {
                    val base64Data = src.substringAfter("base64,")
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else {
                    val tileUrl = fixUrl(src)
                    val imgBytes = app.get(tileUrl, headers = baseHeaders, client = sessionClient).body.bytes()
                    BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)
                }

                if (bitmap != null) {
                    tilesList.add(CaptchaTile(tileId, bitmap))
                }
            }

            val selectedTileIds = showImageGridCaptchaDialog(instruction, tilesList)
            if (selectedTileIds.isNullOrEmpty()) return null

            // Costruzione form payload con array capt[]
            val formBodyBuilder = FormBody.Builder()
                .add("token", token)

            // Aggiungi ciascun elemento selezionato come parametro capt[]
            for (id in selectedTileIds) {
                formBodyBuilder.add("capt[]", id)
                formBodyBuilder.add("capt", id) // Fallback per form con name="capt"
            }

            val postHeaders = baseHeaders.toMutableMap().apply {
                put("Referer", updatedLink)
                put("Origin", "https://uprot.net")
            }

            val postResponse = app.post(
                updatedLink,
                headers = postHeaders,
                requestBody = formBodyBuilder.build(),
                client = sessionClient,
                timeout = 10_000
            )

            return parsePostResult(postResponse.document, postResponse.url)
        }

        val directLink = document.selectFirst("a[href*='maxstream'], a[href*='maxwe'], a[href*='uprots']")?.attr("href")
        return if (!directLink.isNullOrEmpty()) fixUrl(directLink) else null
    }

    private fun parsePostResult(postDoc: org.jsoup.nodes.Document, postUrl: String): String? {
        if (!postUrl.contains("uprot.net")) {
            return postUrl
        }

        val buttokLink = postDoc.selectFirst("#buttok")?.parent()?.attr("href")
        if (!buttokLink.isNullOrEmpty()) return fixUrl(buttokLink)

        val uprotsLink = postDoc.selectFirst("a[href*='uprots']")?.attr("href")
        if (!uprotsLink.isNullOrEmpty()) return fixUrl(uprotsLink)

        val fallbackLink = postDoc.selectFirst("a[href*='maxstream'], a[href*='maxwe']")?.attr("href")
        return if (!fallbackLink.isNullOrEmpty()) fixUrl(fallbackLink) else null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val target = fixUrl(url)
        val extractedUrl = bypassUprot(target)

        if (!extractedUrl.isNullOrEmpty() && !extractedUrl.literaryEquals(target)) {
            val refererToUse = if (extractedUrl.contains("maxstream")) target else url
            loadExtractor(extractedUrl, refererToUse, subtitleCallback, callback)
        } else {
            Log.e("Uprot", "Impossibile ottenere un link video valido")
        }
    }

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
