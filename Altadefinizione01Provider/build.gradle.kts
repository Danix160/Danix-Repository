import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

android {
    namespace = "com.altadefinizione01"
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

cloudstream {
    extra["prefix"] = "Altadefinizione01"
    extra["displayName"] = "Altadefinizione01"
    
    version = 16
    description = "Altadefinizione"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Cartoon")
    requiresResources = false
    language = "it"
    iconUrl = "https://altadefinizione-01.fun/templates/altadefinizione01/images/logo.png"
}
