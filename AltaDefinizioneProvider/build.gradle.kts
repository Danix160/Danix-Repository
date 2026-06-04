plugins {
    id("com.android.library")
    kotlin("android")
    id("cloudstream")
}

android {
    namespace = "com.altadefinizione"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    buildFeatures {
        buildConfig = false
    }

    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }
}

cloudstream {
    extra["prefix"] = "AltaDefinizione"
    extra["displayName"] = "AltaDefinizione"

    version = 1
    description = "AltaDefinizione-01"
    authors = listOf("Danix")

    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "it"
    iconUrl = "https://altadefinizione-01.forum/templates/Darktemplate_pagespeed/images/logo.png"
}
