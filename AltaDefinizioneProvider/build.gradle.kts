android {
    // ... altre configurazioni (compileSdk, ecc.) ...

    kotlinOptions {
        jvmTarget = "1.8"
        // Questa riga dice al compilatore di ignorare che la libreria è "troppo nuova"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}
cloudstream {
    extra["prefix"] = "AltaDefinizione"
    extra["displayName"] = "AltaDefinizione"
    
    version = 3
    description = "AltaDefinizione"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "it"
    iconUrl = "https://altadefinizione.you/templates/Alta/images/logo.svg"
}
