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
    
    version = 7
    description = "MultiXtream IPTV LIVE"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Live")
    requiresResources = false
    language = "it"
    iconUrl = "http://vegetatv.duckdns.org/vegeta_cyan.webp"
}
