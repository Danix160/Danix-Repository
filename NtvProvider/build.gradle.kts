android {

    namespace = "com.ntv"
    
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}
cloudstream {
    extra["prefix"] = "Ntv.cx"
    extra["displayName"] = "Ntv.cx"
    
    version = 1
    description = "Ntv.cx Stream Live"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Live")
    requiresResources = false
    language = "it"
    iconUrl = "http://ntv.cx/assets/img/logo1.png"
}
