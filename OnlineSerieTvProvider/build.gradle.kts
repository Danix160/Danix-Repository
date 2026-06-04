apply(plugin = "com.android.library")
apply(plugin = "kotlin-android")
apply(plugin = "com.lagradost.cloudstream3.gradle")

android {
    namespace = "com.altadefinizione"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
    // ... altre configurazioni (compileSdk, ecc.) ...

    kotlinOptions {
        jvmTarget = "1.8"
        // Questa riga dice al compilatore di ignorare che la libreria è "troppo nuova"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}
cloudstream {
    extra["prefix"] = "OnlineSerieTV"
    extra["displayName"] = "OnlineSerieTV"
    
    version = 90
    description = "OnlineSerieTV"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/Danix160/Danix-Repository/refs/heads/master/OnlineSerieTvProvider/1000108206.png"
}
