android {

    namespace = "com.cineblog"
    
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}
cloudstream {
    extra["prefix"] = "Cineblog"
    extra["displayName"] = "Cineblog"
    
    version = 10
    description = "Cineblog BETA"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "it"
    iconUrl = "https://cineblog001.store/templates/CB01/img/logo.png"
}
