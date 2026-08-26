android {

    namespace = "com.altadefinizione01"

    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

cloudstream {
    extra["prefix"] = "Altadefinizione01"
    extra["displayName"] = "Altadefinizione01"
    
    version = 15
    description = "Altadefinizione"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Cartoon")
    requiresResources = false
    language = "it"
    iconUrl = "https://altadefinizione-01.fun/templates/altadefinizione01/images/logo.png"
}
