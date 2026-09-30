import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

android {
    namespace = "com.loonex"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.25.2")
}

cloudstream {
    extra["prefix"] = "Loonex"
    extra["displayName"] = "Loonex"
    
    version = 39
    description = "Archivio di Anime e Cartoni animati in italiano da Loonex"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Cartoon", "Anime", "TvSeries", "Movie")
    requiresResources = false
    language = "it"
    iconUrl = "https://loonex.eu/archivio-cartoni-logo.png"
}
