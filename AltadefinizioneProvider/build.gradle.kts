import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

android {
    namespace = "com.altadefinizione"

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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

cloudstream {
    extra["prefix"] = "Altadefinizione"
    extra["displayName"] = "Altadefinizione"
    
    version = 10
    description = "Altadefinizione"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Cartoon")
    requiresResources = false
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/Danix160/Danix-Repository/refs/heads/master/AltadefinizioneProvider/logo.png"
}
