plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.airangelvl.diagnostics"
    compileSdk = extra["compileSdk"] as Int

    defaultConfig {
        minSdk = extra["minSdk"] as Int
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("usbOnly") { dimension = "distribution" }
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
    implementation(libs.timber)
    implementation(libs.androidx.datastore)
    implementation(libs.hilt.android)

    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
