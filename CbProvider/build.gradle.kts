android {

    namespace = "com.cb"

    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    compileOnly("androidx.fragment:fragment-ktx:1.8.5")
}

cloudstream {
    extra["prefix"] = "CB01"
    extra["displayName"] = "CB01"
    
    version = 84
    description = "cb01uno.one"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Cartoon")
    requiresResources = false
    language = "it"
    iconUrl = "https://cb01uno.one/wp-content/uploads/2026/01/logo-official-uno-2026.png"
}
