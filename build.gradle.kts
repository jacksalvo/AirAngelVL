plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

allprojects {
    extensions.extraProperties["compileSdk"] = 34
    extensions.extraProperties["minSdk"] = 24
    extensions.extraProperties["targetSdk"] = 34
}

tasks.wrapper {
    gradleVersion = "8.7"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "544c35d6bd849ae8a5ed0bcea39ba677dc40f49df7d1835561582da2009b961d"
    networkTimeout.set(60_000)
}

subprojects {
    tasks.withType<Test>().configureEach {
        // Robolectric API 34 models ParcelFileDescriptor through this JDK field.
        jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED")
    }
}
