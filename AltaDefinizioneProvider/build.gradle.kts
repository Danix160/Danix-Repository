android {

    namespace = "com.altadefinizione"
    
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}
dependencies {
    // ... altre dipendenze ...
    implementation("org.jsoup:jsoup:1.16.1") // o la versione che usi negli altri moduli
}

cloudstream {
    extra["prefix"] = "AltaDefinizione"
    extra["displayName"] = "AltaDefinizione"
    
    version = 10
    description = "Altadefinizione-01"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "it"
    iconUrl = "https://altadefinizione-01.forum/templates/Darktemplate_pagespeed/images/logo.png"
}
