import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.1.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// Rimosse le funzioni "fun Project.cloudstream" e "android" che causavano l'Unresolved reference

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    // Configurazione globale dei task Kotlin
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
        }
    }

    // Configurazione specifica per i task JVM
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
            freeCompilerArgs.addAll(
                "-Xno-call-assertions",
                "-Xno-param-assertions",
                "-Xno-receiver-assertions"
            )
        }
    }

    // Configuriamo il plugin Cloudstream in modo dinamico per evitare errori di compilazione dello script (.kts)
    extensions.configure<Any>("cloudstream") {
        // Usiamo la riflessione o dichiariamo dinamicamente via Groovy/Kotlin Object
        val setRepoMethod = this.javaClass.getMethod("setRepo", String::class.java)
        setRepoMethod.invoke(this, System.getenv("GITHUB_REPOSITORY") ?: "user/repo")
    }

    // Configurazione Android classica tramite il nome della extension
    configure<com.android.build.gradle.BaseExtension> {
        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        // Stubs per le classi di Cloudstream
        cloudstream("com.lagradost:cloudstream3:pre-release")

        implementation(kotlin("stdlib")) 
        implementation("com.github.Blatzar:NiceHttp:0.4.11") 
        implementation("org.jsoup:jsoup:1.18.3") 
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1") 
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
