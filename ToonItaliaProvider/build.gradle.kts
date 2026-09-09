import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

android {
    namespace = "com.toonitalia"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

cloudstream {
    extra["prefix"] = "ToonItalia"
    extra["displayName"] = "ToonItalia"

    version = 113
    description = "Archivio di Anime e Cartoni animati in italiano da ToonItalia.xyz"
    authors = listOf("Danix")

    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime", "Cartoon")
    requiresResources = false
    language = "it"
    iconUrl = "https://toonitalia.xyz/wp-content/uploads/2023/08/cropped-Majintoon-192x192.jpg"
}
