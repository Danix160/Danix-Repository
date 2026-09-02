import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

android {
    namespace = "com.cb"
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
    compileOnly("androidx.fragment:fragment-ktx:1.8.5")
}

cloudstream {
    extra["prefix"] = "CB01"
    extra["displayName"] = "CB01"
    
    version = 88
    description = "cb01uno.one"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Cartoon")
    requiresResources = false
    language = "it"
    iconUrl = "https://cb01uno.one/wp-content/uploads/2026/01/logo-official-uno-2026.png"
}
