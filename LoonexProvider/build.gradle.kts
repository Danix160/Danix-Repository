android {
    
    namespace = "com.loonex"

    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}
cloudstream {
    extra["prefix"] = "Loonex"
    extra["displayName"] = "Loonex"
    
    version = 1
    description = "Archivio di Anime e Cartoni animati in italiano da Loonex"
    authors = listOf("Danix")
    
    status = 0
    tvTypes = listOf("Cartoon", "Anime", "TvSeries")
    requiresResources = false
    language = "it"
    iconUrl = "https://loonex.eu/archivio-cartoni-logo.png"
}
