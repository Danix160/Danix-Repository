import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

android {
    namespace = "com.toonitalia"
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

cloudstream {
    extra["prefix"] = "ToonItalia"
    extra["displayName"] = "ToonItalia"
    
    version = 75
    description = "Archivio di Anime e Cartoni animati in italiano da ToonItalia.xyz"
    authors = listOf("Danix")
    
    status = 0
    tvTypes = listOf("Movie", "TvSeries", "Anime", "Cartoon")
    requiresResources = false
    language = "it"
    iconUrl = "https://toonitalia.xyz/wp-content/uploads/2023/08/cropped-Majintoon-192x192.jpg"
}
