android {

    namespace = "com.guardaplay"

    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

cloudstream {
    extra["prefix"] = "GuardaPlay"
    extra["displayName"] = "GuardaPlay"
    
    version = 3
    description = "GuardaPlay"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Movie")
    requiresResources = false
    language = "it"
    iconUrl = "https://guardaplay.online/wp-content/uploads/2026/06/cropped-GuardaPlay.png"
}
