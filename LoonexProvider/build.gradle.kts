import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

android {
    namespace = "com.loonex"
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

cloudstream {
    extra["prefix"] = "Loonex"
    extra["displayName"] = "Loonex"
    
    version = 2
    description = "Archivio di Anime e Cartoni animati in italiano da Loonex"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Cartoon", "Anime", "TvSeries")
    requiresResources = false
    language = "it"
    iconUrl = "https://loonex.eu/archivio-cartoni-logo.png"
}
