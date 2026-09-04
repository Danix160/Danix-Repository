import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

android {
    namespace = "com.onlineserietv"
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}


// ⬇️ LE DIPENDENZE VANNO RACCHIUSE IN QUESTO BLOCCO
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    compileOnly("androidx.fragment:fragment-ktx:1.8.5")
}

cloudstream {
    extra["prefix"] = "OnlineSerieTV"
    extra["displayName"] = "OnlineSerieTV"
    
    version = 160
    description = "OnlineSerieTV"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/Danix160/Danix-Repository/refs/heads/master/OnlineSerieTvProvider/1000108206.png"
}
