android {

    namespace = "com.vegetatv"
    
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}
cloudstream {
    extra["prefix"] = "VegetaTV"
    extra["displayName"] = "VegetaTV"
    
    version = 1
    description = "VegetaTV Stream Live"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Live")
    requiresResources = false
    language = "it"
    iconUrl = "http://vegetatv.duckdns.org/vegeta_cyan.webp"
}
