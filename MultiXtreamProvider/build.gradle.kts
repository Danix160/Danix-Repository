import org.jetbrains.kotlin.konan.properties.Properties

cloudstream {
    extra["prefix"] = "MultiXtream"
    extra["displayName"] = "MultiXtream"
    
    version = 39
    description = "MultiXtream IPTV LIVE"
    authors = listOf("Danix")
    
    status = 1
    tvTypes = listOf("Live")
    requiresResources = true
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/Danix160/Danix-Repository/refs/heads/master/MultiXtreamProvider/GiBEWASW4AAvgrH.jpg"
}

android {

    namespace = "com.multixtream"
    
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
     buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    defaultConfig {
        val properties = Properties()
        val file = project.rootProject.file("secrets.properties")
        if (file.exists()) {
            properties.load(file.inputStream())
            buildConfigField("String", "TMDB_API", "\"${properties.getProperty("TMDB_API")}\"")
        } else {
            buildConfigField("String", "TMDB_API", "\"\"")
        }
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
}


