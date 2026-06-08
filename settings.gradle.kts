pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    // PREVIENE CHE I SOTTO-PROGETTI IGNORINO I REPOSITORY DI BUILD SCRIPT SE IMPOSTATO SU FAIL_ON_PROJECT_REPOS
    repositoriesMode.set(RepositoriesMode.PRESERVE_HERITAGE)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "CloudstreamPlugins"

// Inclusione dinamica dei sotto-moduli dei plugin
File(rootDir, ".").eachDir { dir ->
    if (File(dir, "build.gradle.kts").exists()) {
        include(":${dir.name}")
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
