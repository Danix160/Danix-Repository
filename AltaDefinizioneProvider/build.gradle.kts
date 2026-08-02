android {

    namespace = "com.altadefinizione"
    
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}
cloudstream {
    extra["prefix"] = "AltaDefinizione"
    extra["displayName"] = "AltaDefinizione"
    
    version = 2
    description = "AltaDefinizione BETA"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "it"
    iconUrl = "https://altadefinizionex.co/templates/altadefinizione/images/logo.svg"
}
