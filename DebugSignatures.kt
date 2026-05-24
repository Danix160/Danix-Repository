package debug

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

fun debugPrint() {
    println("---- SIGNATURES ----")

    println("newEpisode:")
    ::newEpisode.parameters.forEach { println(" - ${it.name} : ${it.type}") }

    println("\nnewTvSeriesLoadResponse:")
    ::newTvSeriesLoadResponse.parameters.forEach { println(" - ${it.name} : ${it.type}") }

    println("\nnewMovieLoadResponse:")
    ::newMovieLoadResponse.parameters.forEach { println(" - ${it.name} : ${it.type}") }
}
