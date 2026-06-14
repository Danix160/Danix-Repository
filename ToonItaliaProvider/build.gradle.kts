android {
    
    namespace = "com.toonitalia"

    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}
cloudstream {
    extra["prefix"] = "ToonItalia"
    extra["displayName"] = "ToonItalia"
    
    version = 73
    description = "Archivio di Anime e Cartoni animati in italiano da ToonItalia.xyz"
    authors = listOf("Danix")
    
    status = 0
    tvTypes = listOf("Movie", "TvSeries", "Anime", "Cartoon")
    requiresResources = false
    language = "it"
    iconUrl = "https://toonitalia.xyz/wp-content/uploads/2023/08/cropped-Majintoon-192x192.jpg"
}
