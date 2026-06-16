plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

cloudstream {
    extra["prefix"] = "MultiXtream"
    extra["displayName"] = "MultiXtream"
    
    version = 36
    description = "MultiXtream IPTV LIVE"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Live")
    requiresResources = false
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/Danix160/Danix-Repository/refs/heads/master/MultiXtreamProvider/GiBEWASW4AAvgrH.jpg"
}

android {

    namespace = "com.multixtream"
    
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    implementation("com.github.recloudstream:cloudstream:pre-release")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
