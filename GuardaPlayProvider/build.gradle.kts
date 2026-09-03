import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

android {
    namespace = "com.guardaplay"
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
    extra["prefix"] = "GuardaPlay"
    extra["displayName"] = "GuardaPlay"
    
    version = 7
    description = "GuardaPlay"
    authors = listOf("Danix")
    
    status = 0
    tvTypes = listOf("Movie")
    requiresResources = false
    language = "it"
    iconUrl = "https://guardaplay.online/wp-content/uploads/2026/06/cropped-GuardaPlay.png"
}
