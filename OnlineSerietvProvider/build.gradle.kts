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
    extra["prefix"] = "OnlineSerieTv"
    extra["displayName"] = "OnlineSerieTv"
    
    version = 17
    description = "OnlineSerieTv"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Movie", "TvSeries, Cartoon, Anime")
    requiresResources = false
    language = "it"
    iconUrl = "https://onlineserietv.live/wp-content/uploads/2023/01/cropped-tv-1.png"
}
