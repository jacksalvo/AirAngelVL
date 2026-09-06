plugins {
    alias(libs.plugins.android.library)
}

// Compile the preserved upstream USB API and JNI together. Prebuilt libraries in
// the old source tree are deliberately not inputs to the shipping package.
val upstream = rootProject.file("external/AndroidUSBCamera")
val validationAbi = providers.gradleProperty("validationAbis").orNull
require(validationAbi == null || validationAbi == "x86_64")
val selectedAbis = validationAbi ?: "armeabi-v7a,arm64-v8a"
val generatedJni = layout.buildDirectory.dir("generated/${if (validationAbi == null) "android-arm" else "validation-x86_64"}/jniLibs")
val generatedJava = layout.buildDirectory.dir("generated/java")
val prepareUsbSources by tasks.registering(Sync::class) {
    from(upstream.resolve("libuvc/src/main/java")) {
        include("com/serenegiant/usb/*.java", "com/serenegiant/utils/XLogWrapper.java")
    }
    from(upstream.resolve("libuvccommon/src/main/java")) {
        include("com/serenegiant/utils/BuildCheck.java", "com/serenegiant/utils/HandlerThreadHandler.java")
    }
    into(generatedJava)
}
val buildNative by tasks.registering(Exec::class) {
    inputs.dir(upstream.resolve("libuvc/src/main/jni"))
    inputs.file(upstream.resolve("libuvc/src/main/AndroidManifest.xml"))
    inputs.file(rootProject.file("scripts/build-native.ps1"))
    inputs.property("abis", selectedAbis)
    outputs.dir(generatedJni)
    commandLine(
        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        rootProject.file("scripts/build-native.ps1").absolutePath,
        "-OutputDirectory", generatedJni.get().asFile.absolutePath,
        "-Abis", selectedAbis
    )
}

android {
    namespace = "com.serenegiant.uvccamera"
    compileSdk = extra["compileSdk"] as Int
    ndkVersion = "27.0.12077973"
    defaultConfig {
        minSdk = extra["minSdk"] as Int
        consumerProguardFiles("consumer-rules.pro")
        ndk.abiFilters += selectedAbis.split(',')
    }
    sourceSets.getByName("main") {
        java.setSrcDirs(listOf(generatedJava))
        jniLibs.srcDir(generatedJni)
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named("preBuild").configure { dependsOn(buildNative, prepareUsbSources) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("com.elvishew:xlog:1.11.0")
}
