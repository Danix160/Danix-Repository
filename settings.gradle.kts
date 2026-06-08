pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") // Permette a Gradle di trovare il plugin di Cloudstream a livello globale
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PRESERVE_EXISTING)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "CloudstreamPlugins"

// Lista dei provider che vuoi disabilitare/ignorare temporaneamente
val disabled = listOf<String>()

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(":${dir.name}") // Corretto l'include aggiungendo il flag standard ":" di Gradle
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
