package com.toonitalia

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import java.net.URLEncoder

object Tmdb {

    private const val apiUrl =
        "https://api.themoviedb.org/3"

    private const val imageUrl =
        "https://image.tmdb.org/t/p/original"

    /*
     * Inserisci qui la tua API KEY v3 di TMDB.
     *
     * Per ora usiamo api_key per semplicità.
     */
    private const val apiKey =
        "e541cb159df14ce70fc51ab75703a1a2"

    data class Metadata(
        val id: Int,
        val title: String?,
        val originalTitle: String?,
        val posterUrl: String?,
        val backgroundPosterUrl: String?,
        val plot: String?,
        val year: Int?,
        val genres: List<String>,
        val score: Int?
    )

    private data class SearchResponse(
        @JsonProperty("results")
        val results: List<SearchItem>? = null
    )

    private data class SearchItem(
        @JsonProperty("id")
        val id: Int? = null,

        @JsonProperty("title")
        val title: String? = null,

        @JsonProperty("name")
        val name: String? = null,

        @JsonProperty("original_title")
        val originalTitle: String? = null,

        @JsonProperty("original_name")
        val originalName: String? = null,

        @JsonProperty("release_date")
        val releaseDate: String? = null,

        @JsonProperty("first_air_date")
        val firstAirDate: String? = null,

        @JsonProperty("popularity")
        val popularity: Double? = null
    )

    private data class DetailsResponse(
        @JsonProperty("id")
        val id: Int? = null,

        @JsonProperty("title")
        val title: String? = null,

        @JsonProperty("name")
        val name: String? = null,

        @JsonProperty("original_title")
        val originalTitle: String? = null,

        @JsonProperty("original_name")
        val originalName: String? = null,

        @JsonProperty("overview")
        val overview: String? = null,

        @JsonProperty("poster_path")
        val posterPath: String? = null,

        @JsonProperty("backdrop_path")
        val backdropPath: String? = null,

        @JsonProperty("release_date")
        val releaseDate: String? = null,

        @JsonProperty("first_air_date")
        val firstAirDate: String? = null,

        @JsonProperty("vote_average")
        val voteAverage: Double? = null,

        @JsonProperty("genres")
        val genres: List<Genre>? = null
    )

    private data class Genre(
        @JsonProperty("id")
        val id: Int? = null,

        @JsonProperty("name")
        val name: String? = null
    )

    suspend fun getMovie(
        title: String,
        year: Int?
    ): Metadata? {
        return search(
            title = title,
            year = year,
            isTv = false
        )
    }

    suspend fun getTv(
        title: String,
        year: Int?
    ): Metadata? {
        return search(
            title = title,
            year = year,
            isTv = true
        )
    }

    private suspend fun search(
        title: String,
        year: Int?,
        isTv: Boolean
    ): Metadata? {

        if (apiKey.isBlank() ||
            apiKey == "INSERISCI_LA_TUA_TMDB_API_KEY"
        ) {
            println("[TMDB] API KEY non configurata")
            return null
        }

        val cleanTitle =
            cleanTitle(title)

        println(
            "[TMDB] Ricerca ${if (isTv) "TV" else "MOVIE"}: " +
                "$cleanTitle ($year)"
        )

        val encodedTitle =
            URLEncoder.encode(
                cleanTitle,
                "UTF-8"
            )

        val searchType =
            if (isTv) "tv" else "movie"

        val yearParameter =
            when {
                year == null ->
                    ""

                isTv ->
                    "&first_air_date_year=$year"

                else ->
                    "&primary_release_year=$year"
            }

        val searchUrl =
            "$apiUrl/search/$searchType" +
                "?api_key=$apiKey" +
                "&language=it-IT" +
                "&include_adult=false" +
                "&query=$encodedTitle" +
                yearParameter

        var response =
            runCatching {
                app.get(searchUrl)
                    .parsed<SearchResponse>()
            }.getOrNull()

        /*
         * Se l'anno impedisce il match,
         * riproviamo senza filtro anno.
         */
        if (response?.results.isNullOrEmpty() &&
            year != null
        ) {
            println(
                "[TMDB] Nessun risultato con anno, " +
                    "riprovo senza anno"
            )

            val fallbackUrl =
                "$apiUrl/search/$searchType" +
                    "?api_key=$apiKey" +
                    "&language=it-IT" +
                    "&include_adult=false" +
                    "&query=$encodedTitle"

            response =
                runCatching {
                    app.get(fallbackUrl)
                        .parsed<SearchResponse>()
                }.getOrNull()
        }

        val results =
            response?.results
                .orEmpty()

        if (results.isEmpty()) {
            println(
                "[TMDB] Nessun risultato per: $cleanTitle"
            )
            return null
        }

        val best =
            findBestMatch(
                title = cleanTitle,
                year = year,
                results = results,
                isTv = isTv
            ) ?: return null

        val id =
            best.id
                ?: return null

        println(
            "[TMDB] MATCH: " +
                "${best.title ?: best.name} | ID=$id"
        )

        return getDetails(
            id = id,
            isTv = isTv
        )
    }

    private suspend fun getDetails(
        id: Int,
        isTv: Boolean
    ): Metadata? {

        val type =
            if (isTv) "tv" else "movie"

        val url =
            "$apiUrl/$type/$id" +
                "?api_key=$apiKey" +
                "&language=it-IT"

        val details =
            runCatching {
                app.get(url)
                    .parsed<DetailsResponse>()
            }.getOrNull()
                ?: return null

        val title =
            details.title
                ?: details.name

        val originalTitle =
            details.originalTitle
                ?: details.originalName

        val date =
            details.releaseDate
                ?: details.firstAirDate

        val year =
            date
                ?.take(4)
                ?.toIntOrNull()

        val score =
            details.voteAverage
                ?.let {
                    (it * 10)
                        .toInt()
                }

        return Metadata(
            id = details.id ?: id,
            title = title,
            originalTitle = originalTitle,
            posterUrl =
                details.posterPath
                    ?.let { "$imageUrl$it" },
            backgroundPosterUrl =
                details.backdropPath
                    ?.let { "$imageUrl$it" },
            plot =
                details.overview
                    ?.takeIf {
                        it.isNotBlank()
                    },
            year = year,
            genres =
                details.genres
                    .orEmpty()
                    .mapNotNull {
                        it.name
                    }
                    .filter {
                        it.isNotBlank()
                    },
            score = score
        )
    }

    private fun findBestMatch(
        title: String,
        year: Int?,
        results: List<SearchItem>,
        isTv: Boolean
    ): SearchItem? {

        val normalizedTarget =
            normalize(title)

        return results
            .map { item ->

                val candidateTitle =
                    item.title
                        ?: item.name
                        ?: ""

                val original =
                    item.originalTitle
                        ?: item.originalName
                        ?: ""

                val candidateYear =
                    (
                        item.releaseDate
                            ?: item.firstAirDate
                        )
                        ?.take(4)
                        ?.toIntOrNull()

                var points = 0

                val candidateNormalized =
                    normalize(candidateTitle)

                val originalNormalized =
                    normalize(original)

                if (
                    candidateNormalized ==
                    normalizedTarget
                ) {
                    points += 100
                }

                if (
                    originalNormalized ==
                    normalizedTarget
                ) {
                    points += 90
                }

                if (
                    candidateNormalized.contains(
                        normalizedTarget
                    ) ||
                    normalizedTarget.contains(
                        candidateNormalized
                    )
                ) {
                    points += 40
                }

                if (
                    originalNormalized.contains(
                        normalizedTarget
                    ) ||
                    normalizedTarget.contains(
                        originalNormalized
                    )
                ) {
                    points += 30
                }

                if (
                    year != null &&
                    candidateYear != null
                ) {
                    when {
                        candidateYear == year ->
                            points += 50

                        kotlin.math.abs(
                            candidateYear - year
                        ) == 1 ->
                            points += 10
                    }
                }

                points +=
                    ((item.popularity ?: 0.0) / 100.0)
                        .toInt()
                        .coerceAtMost(10)

                item to points
            }
            .sortedByDescending {
                it.second
            }
            .firstOrNull()
            ?.also {
                println(
                    "[TMDB] BEST SCORE = ${it.second}"
                )
            }
            ?.first
    }

    private fun cleanTitle(
        title: String
    ): String {

        return title
            .replace(
                Regex(
                    """\s*\([^)]*(?:ITA|SUB|SUB-ITA|STREAMING)[^)]*\)\s*""",
                    RegexOption.IGNORE_CASE
                ),
                " "
            )
            .replace(
                Regex(
                    """\s*[-–|]\s*(?:ITA|SUB ITA|SUB-ITA).*$""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(
                Regex("""\s+"""),
                " "
            )
            .trim()
    }

    private fun normalize(
        value: String
    ): String {

        return java.text.Normalizer
            .normalize(
                value.lowercase(),
                java.text.Normalizer.Form.NFD
            )
            .replace(
                Regex("\\p{Mn}+"),
                ""
            )
            .replace(
                Regex("[^a-z0-9]+"),
                " "
            )
            .trim()
    }
}
