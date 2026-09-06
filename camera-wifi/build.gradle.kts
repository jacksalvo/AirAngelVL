plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.airangelvl.camera.wifi"
    compileSdk = extra["compileSdk"] as Int

    defaultConfig {
        minSdk = extra["minSdk"] as Int
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("usbOnly") { dimension = "distribution" }
        create("full") { dimension = "distribution" }
    }

    buildFeatures {
        buildConfig = true
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
    api(project(":diagnostics"))
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.exo.rtsp)
    implementation(libs.exo.ui)
    implementation(libs.timber)
    implementation(libs.hilt.android)

    ksp(libs.hilt.compiler)
}
