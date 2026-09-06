plugins {
    alias(libs.plugins.android.dynamic.feature)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.airangelvl.feature.wifi_rec"
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

androidComponents {
    beforeVariants(selector().withFlavor("distribution" to "usbOnly")) { variant ->
        variant.enable = false
    }
}

dependencies {
    implementation(project(":app"))
    implementation(project(":core"))
    implementation(project(":camera-wifi"))
    implementation(project(":diagnostics"))

    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.timber)
    implementation(libs.hilt.android)

    ksp(libs.hilt.compiler)
}
