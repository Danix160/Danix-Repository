pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    // Rimosso il riferimento diretto all'enum per evitare i blocchi di compilazione di Gradle
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
        include(":${dir.name}")
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
