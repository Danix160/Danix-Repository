android {
    namespace = "com.toonitalia"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
    // ... altre configurazioni (compileSdk, ecc.) ...

    kotlinOptions {
        jvmTarget = "1.8"
        // Questa riga dice al compilatore di ignorare che la libreria è "troppo nuova"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}
cloudstream {
    extra["prefix"] = "ToonItalia"
    extra["displayName"] = "ToonItalia"
    
    version = 72
    description = "Archivio di Anime e Cartoni animati in italiano da ToonItalia.xyz"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime", "Cartoon")
    requiresResources = false
    language = "it"
    iconUrl = "https://toonitalia.xyz/wp-content/uploads/2023/08/cropped-Majintoon-192x192.jpg"
}
