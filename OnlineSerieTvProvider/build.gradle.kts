android {
    namespace = "com.onlineserietv"
    
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}

// ⬇️ LE DIPENDENZE VANNO RACCHIUSE IN QUESTO BLOCCO
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

cloudstream {
    extra["prefix"] = "OnlineSerieTV"
    extra["displayName"] = "OnlineSerieTV"
    
    version = 121
    description = "OnlineSerieTV"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/Danix160/Danix-Repository/refs/heads/master/OnlineSerieTvProvider/1000108206.png"
}
