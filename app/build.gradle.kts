plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val validationAbi = providers.gradleProperty("validationAbis").orNull
require(validationAbi == null || validationAbi == "x86_64") {
    "validationAbis is reserved for the x86_64 emulator build"
}

android {
    namespace = "com.airangelvl.app"
    compileSdk = extra["compileSdk"] as Int
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.airangelvl"
        minSdk = extra["minSdk"] as Int
        targetSdk = extra["targetSdk"] as Int
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += if (validationAbi == null) setOf("armeabi-v7a", "arm64-v8a") else setOf(validationAbi)
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            applicationIdSuffix = if (validationAbi == null) ".debug" else ".validation"
            resValue("string", "app_name", if (validationAbi == null) "AirAngel VL Test" else "AirAngel VL Validation")
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions += "distribution"

    productFlavors {
        create("usbOnly") {
            dimension = "distribution"
            description = "USB preview/record only"
        }
    }

    packaging.resources.excludes += setOf(
        "META-INF/AL2.0",
        "META-INF/LGPL2.1"
    )

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkDependencies = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    testOptions.unitTests.isIncludeAndroidResources = true
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) {
        if (validationAbi != null) it.enable = false
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":camera-usb"))
    implementation(project(":diagnostics"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.android.material)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.androidx.datastore)
    implementation(libs.timber)
    implementation(libs.hilt.android)

    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.mockk)
}
