plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.airangelvl.updater"
    compileSdk = extra["compileSdk"] as Int

    defaultConfig {
        minSdk = extra["minSdk"] as Int
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("usbOnly") { dimension = "distribution" }
        create("full") { dimension = "distribution" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    // Preserved legacy scaffold; this module is excluded from settings.gradle.kts.
    // Migrate Play Core before re-enabling it for modern Android versions.
    implementation("com.google.android.play:core:1.10.3")
    implementation(libs.androidx.work.runtime)
    implementation(libs.timber)
    implementation(libs.hilt.android)

    ksp(libs.hilt.compiler)
}
