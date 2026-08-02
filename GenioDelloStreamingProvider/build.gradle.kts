android {

    namespace = "com.geniodellostreaming"
    
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}
cloudstream {
    extra["prefix"] = "GenioDelloStreaming"
    extra["displayName"] = "GenioDelloStreaming"
    
    version = 1
    description = "GenioDelloStreaming BETA"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "it"
    iconUrl = "https://il-geniodellostreaming.pro/templates/ilgeniodellostreaming/images/logo.png"
}
