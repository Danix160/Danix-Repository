package com.streamfree

import android.util.Log
import com.lagradost.cloudstream3.*
import org.json.JSONObject
import java.net.URLEncoder

class StreamFreeProvider : MainAPI() {

    override var name =
        "StreamFree"

    override var mainUrl =
        "https://streamfree.top"

    override var lang =
        "en"

    override val supportedTypes =
        setOf(
            TvType.Live
        )

    override val hasMainPage =
        true

    override val hasQuickSearch =
        true

    companion object {

        private const val TAG =
            "STREAMFREE"

        private const val API =
            "https://streamfree.top/api/v1"
    }

    override val mainPage =
        mainPageOf(
            "$API/streams" to
                "Tutti i live",

            "$API/streams?category=soccer" to
                "Calcio",

            "$API/streams?category=basketball" to
                "Basket",

            "$API/streams?category=football" to
                "Football",

            "$API/streams?category=hockey" to
                "Hockey",

            "$API/streams?category=baseball" to
                "Baseball",

            "$API/streams?category=tennis" to
                "Tennis",

            "$API/streams?category=combat" to
                "Combat",

            "$API/streams?category=racing" to
                "Motori",

            "$API/streams?category=cricket" to
                "Cricket"
        )

    // ============================================================
    // HOME
    // ============================================================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        /*
         * L'API restituisce solo eventi attualmente disponibili,
         * quindi non serve vera paginazione.
         */
        if (page > 1) {

            return newHomePageResponse(
                request.name,
                emptyList()
            )
        }

        Log.d(
            TAG,
            "HOME = ${request.data}"
        )

        val response =
            try {

                app.get(
                    request.data
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "HOME errore: ${e.message}"
                )

                return newHomePageResponse(
                    request.name,
                    emptyList()
                )
            }

        if (
            response.code !in
            200..299
        ) {

            return newHomePageResponse(
                request.name,
                emptyList()
            )
        }

        val root =
            try {

                JSONObject(
                    response.text
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "JSON home: ${e.message}"
                )

                return newHomePageResponse(
                    request.name,
                    emptyList()
                )
            }

        val streams =
            root.optJSONArray(
                "streams"
            )
                ?: return newHomePageResponse(
                    request.name,
                    emptyList()
                )

        val results =
            mutableListOf<SearchResponse>()

        for (
            i in 0 until
                streams.length()
        ) {

            val item =
                streams.optJSONObject(i)
                    ?: continue

            parseStream(
                item
            )
                ?.let {
                    results.add(it)
                }
        }

        return newHomePageResponse(
            request.name,
            results
        )
    }

    // ============================================================
    // SEARCH
    // ============================================================

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        if (
            query.isBlank()
        ) {
            return emptyList()
        }

        val response =
            try {

                app.get(
                    "$API/streams"
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "SEARCH errore: ${e.message}"
                )

                return emptyList()
            }

        if (
            response.code !in
            200..299
        ) {
            return emptyList()
        }

        val root =
            try {

                JSONObject(
                    response.text
                )

            } catch (_: Exception) {
                return emptyList()
            }

        val streams =
            root.optJSONArray(
                "streams"
            )
                ?: return emptyList()

        val normalizedQuery =
            query
                .trim()
                .lowercase()

        val result =
            mutableListOf<SearchResponse>()

        for (
            i in 0 until
                streams.length()
        ) {

            val item =
                streams.optJSONObject(i)
                    ?: continue

            val name =
                item.optString(
                    "name"
                )

            val league =
                item.optString(
                    "league"
                )

            val category =
                item.optString(
                    "category"
                )

            if (
                !name.lowercase()
                    .contains(
                        normalizedQuery
                    ) &&
                !league.lowercase()
                    .contains(
                        normalizedQuery
                    ) &&
                !category.lowercase()
                    .contains(
                        normalizedQuery
                    )
            ) {
                continue
            }

            parseStream(
                item
            )
                ?.let {
                    result.add(it)
                }
        }

        return result
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {

        return search(
            query
        )
            .take(
                20
            )
    }

    // ============================================================
    // CARD
    // ============================================================

    private fun parseStream(
        item: JSONObject
    ): SearchResponse? {

        val name =
            item.optString(
                "name"
            )
                .trim()

        val streamKey =
            item.optString(
                "stream_key"
            )
                .trim()

        val category =
            item.optString(
                "category"
            )
                .trim()

        if (
            name.isBlank() ||
            streamKey.isBlank() ||
            category.isBlank()
        ) {
            return null
        }

        val thumbnail =
            item.optString(
                "thumbnail_url"
            )
                .takeIf {
                    it.isNotBlank() &&
                        it != "null"
                }

        val data =
            buildLoadData(
                category =
                    category,

                streamKey =
                    streamKey
            )

        return newLiveSearchResponse(
            name,
            data,
            TvType.Live
        ) {

            this.posterUrl =
                thumbnail
        }
    }

    // ============================================================
    // LOAD
    // ============================================================

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val streamData =
            parseLoadData(
                url
            )
                ?: return null

        val category =
            streamData.first

        val streamKey =
            streamData.second

        /*
         * Recuperiamo il record corrente dalla API.
         *
         * La documentazione indica che lo stream può sparire
         * quando l'evento termina.
         */
        val response =
            try {

                app.get(
                    "$API/streams/$streamKey"
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "LOAD errore: ${e.message}"
                )

                return null
            }

        if (
            response.code !in
            200..299
        ) {
            return null
        }

        val item =
            try {

                JSONObject(
                    response.text
                )

            } catch (_: Exception) {
                return null
            }

        val name =
            item.optString(
                "name"
            )
                .ifBlank {
                    streamKey
                }

        val league =
            item.optString(
                "league"
            )
                .takeIf {
                    it.isNotBlank() &&
                        it != "null"
                }

        val thumbnail =
            item.optString(
                "thumbnail_url"
            )
                .takeIf {
                    it.isNotBlank() &&
                        it != "null"
                }

        val timestamp =
            item.optLong(
                "match_timestamp",
                0L
            )

        val embedUrl =
            item.optString(
                "embed_url"
            )
                .takeIf {
                    it.isNotBlank() &&
                        it != "null"
                }
                ?: "$mainUrl/embed/$category/$streamKey"

        val playData =
            JSONObject()
                .put(
                    "name",
                    name
                )
                .put(
                    "category",
                    category
                )
                .put(
                    "streamKey",
                    streamKey
                )
                .put(
                    "embedUrl",
                    embedUrl
                )
                .toString()

        return newLiveStreamLoadResponse(
            name,
            url,
            playData
        ) {

            this.posterUrl =
                thumbnail

            this.plot =
                league

            if (
                timestamp > 0
            ) {

                this.tags =
                    listOf(
                        category,
                        "LIVE"
                    )

            } else {

                this.tags =
                    listOf(
                        category
                    )
            }
        }
    }

    // ============================================================
    // LOAD LINKS
    // ============================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback:
            (SubtitleFile) -> Unit,
        callback:
            (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean {

        val json =
            try {

                JSONObject(
                    data
                )

            } catch (_: Exception) {
                return false
            }

        val embedUrl =
            json.optString(
                "embedUrl"
            )

        if (
            embedUrl.isBlank()
        ) {
            return false
        }

        Log.d(
            TAG,
            "EMBED = $embedUrl"
        )

        /*
         * Per ora il provider arriva fino all'embed URL.
         *
         * La parte di riproduzione diretta va collegata
         * soltanto a sorgenti/flussi che sei autorizzato
         * a riprodurre.
         */
        return false
    }

    // ============================================================
    // SERIALIZZAZIONE
    // ============================================================

    private fun buildLoadData(
        category: String,
        streamKey: String
    ): String {

        val encodedCategory =
            URLEncoder.encode(
                category,
                "UTF-8"
            )

        val encodedKey =
            URLEncoder.encode(
                streamKey,
                "UTF-8"
            )

        return "https://streamfree.local/" +
            "$encodedCategory/" +
            encodedKey
    }

    private fun parseLoadData(
        data: String
    ): Pair<String, String>? {

        if (
            !data.startsWith(
                "https://streamfree.local/"
            )
        ) {
            return null
        }

        val clean =
            data.substringAfter(
                "https://streamfree.local/"
            )

        val category =
            clean.substringBefore(
                "/"
            )

        val streamKey =
            clean.substringAfter(
                "/",
                ""
            )

        if (
            category.isBlank() ||
            streamKey.isBlank()
        ) {
            return null
        }

        return category to
            streamKey
    }
}
