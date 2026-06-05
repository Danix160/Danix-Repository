android {

    namespace = "com.onlineserietv"
    
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}
cloudstream {
    extra["prefix"] = "OnlineSerieTV"
    extra["displayName"] = "OnlineSerieTV"
    
    version = 101
    description = "OnlineSerieTV"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/Danix160/Danix-Repository/refs/heads/master/OnlineSerieTvProvider/1000108206.png"
}
