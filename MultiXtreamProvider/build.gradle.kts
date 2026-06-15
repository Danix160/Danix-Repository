android {

    namespace = "com.multixtream"
    
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}
cloudstream {
    extra["prefix"] = "MultiXtream"
    extra["displayName"] = "MultiXtream"
    
    version = 16
    description = "MultiXtream IPTV LIVE"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Live")
    requiresResources = false
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/Danix160/Danix-Repository/refs/heads/master/MultiXtreamProvider/GiBEWASW4AAvgrH.jpg"
}
