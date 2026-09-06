plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.airangelvl.core"
    compileSdk = extra["compileSdk"] as Int

    defaultConfig {
        minSdk = extra["minSdk"] as Int
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
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.javax.inject)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

