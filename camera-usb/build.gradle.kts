plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val validationAbi = providers.gradleProperty("validationAbis").orNull
require(validationAbi == null || validationAbi == "x86_64")

android {
    namespace = "com.airangelvl.camera.usb"
    compileSdk = extra["compileSdk"] as Int
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = extra["minSdk"] as Int
        targetSdk = extra["targetSdk"] as Int
        ndk.abiFilters += if (validationAbi == null) setOf("armeabi-v7a", "arm64-v8a") else setOf(validationAbi)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("usbOnly") { dimension = "distribution" }
    }

    buildFeatures {
        buildConfig = true
    }

    // Preserve the old copied binaries on disk, but package only :native-uvc's
    // matching source build. A duplicate must fail rather than pick a random ABI.
    sourceSets.getByName("main").jniLibs.setSrcDirs(emptyList<String>())

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    api(project(":diagnostics"))
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)
    implementation(libs.timber)
    implementation(libs.hilt.android)
    implementation(project(":native-uvc"))

    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
