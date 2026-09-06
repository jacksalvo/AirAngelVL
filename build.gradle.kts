plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

allprojects {
    extensions.extraProperties["compileSdk"] = 36
    extensions.extraProperties["minSdk"] = 24
    extensions.extraProperties["targetSdk"] = 36
}

tasks.wrapper {
    gradleVersion = "8.11.1"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6"
    networkTimeout.set(60_000)
}

subprojects {
    tasks.withType<Test>().configureEach {
        // Robolectric API 34 models ParcelFileDescriptor through this JDK field.
        jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED")
    }
}
