android {
    // ... altre configurazioni (compileSdk, ecc.) ...

    kotlinOptions {
        jvmTarget = "1.8"
        // Questa riga dice al compilatore di ignorare che la libreria è "troppo nuova"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}
cloudstream {
    extra["prefix"] = "OnlineSerieTV"
    extra["displayName"] = "OnlineSerieTV"
    
    version = 72
    description = "OnlineSerieTV"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/Danix160/Danix-Repository/refs/heads/master/OnlineSerieTvProvider/1000108206.png"
}
