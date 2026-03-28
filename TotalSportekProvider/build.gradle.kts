android {
    // ... altre configurazioni (compileSdk, ecc.) ...

    kotlinOptions {
        jvmTarget = "1.8"
        // Questa riga dice al compilatore di ignorare che la libreria è "troppo nuova"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
}
cloudstream {
    extra["prefix"] = "TotalSportek"
    extra["displayName"] = "TotalSportek"
    
    version = 1
    description = "Sport Live"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Live")
    requiresResources = false
    language = "it"
    iconUrl = ""
}
