import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

android {
    namespace = "com.rivestream"
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
    extra["prefix"] = "RiveStream"
    extra["displayName"] = "RiveStream"
    
    version = 29
    description = "Live Events Sport "
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Live")
    requiresResources = false
    language = "it"
    iconUrl = ""
}
