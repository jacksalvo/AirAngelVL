# Building the USB application on Windows

The shipping application is `usbOnly`, version **0.2.0 / code 2**, with minimum Android 7.0 / API 24. The unsigned release keeps package `com.airangelvl`. The signed debug APK is **AirAngel VL Test**, package `com.airangelvl.debug`, so it can be installed alongside the original application. Wi-Fi, updater and the dynamic-feature source folders remain preserved but are excluded from `settings.gradle.kts`. The old `full` variant is not a shipping build.

## Toolchain

- Temurin JDK **17.0.20.1+1** (portable; no global Java settings required).
- Gradle **8.7**, obtained by the wrapper and checked against its pinned SHA-256.
- Android Gradle Plugin **8.5.2**; Kotlin **1.9.24**.
- Android SDK platform **34**, build-tools **34.0.0** (build) and **35.0.0** (16 KB ZIP verification).
- Android NDK **27.0.12077973**.

On this PC the SDK is `C:\Android\Sdk` and the portable JDK is `C:\Coding\AAVL-toolchains\jdk-17.0.20.1+1`. Android Studio's bundled Java 25 is incompatible with this Gradle/Kotlin baseline. The setup script writes the ignored project-local `.gradle/config.properties` `java.home` entry used by the existing `#GRADLE_LOCAL_JAVA_HOME` setting, preserving other properties. It does not change global IDE or Java settings.

```powershell
.\scripts\setup-toolchain.ps1
.\scripts\build.ps1 -Verify
```

`setup-toolchain.ps1` downloads the pinned official Adoptium archive, verifies its SHA-256 before extraction, and checks the installed SDK packages. Use `-ToolchainDirectory` and `-SdkRoot` for alternate locations. `build.ps1` accepts `-JavaHome`, `-SdkRoot`, and `-Tasks`; it sets process-local environment variables and writes only the ignored `local.properties` SDK path. It restores the caller's Java/SDK environment afterward.

The command-line build has been verified using the wrapper's process-local JDK TCP-loopback fallback because AF_UNIX connections fail on this Windows host. It does not change Windows networking or global Java options. Android Studio sync has not been verified; selecting the correct local JDK alone does not apply the host-specific TCP workaround to the IDE. Use the verified command-line wrapper until IDE sync is checked. A successful Studio resync should refresh the old module list from `settings.gradle.kts`; the excluded source folders should not be manually added back to the build.

## Native source and packaging

`:native-uvc` builds the preserved `external/AndroidUSBCamera/libuvc/src/main/jni` source with `ndk-build`. The Java USB API and its two common helpers are copied into an ignored generated-source directory and compiled together. This keeps the dependency independent of the unfinished libausbc renderer and its remote JitPack artifacts.

Only `armeabi-v7a` and `arm64-v8a` are packaged in delivery APKs. The UVC libraries are built at API 24 with the preserved 16 KB linker flags and a statically linked C++ runtime: `libUVCCamera.so`, `libuvc.so`, `libusb100.so` and `libjpeg-turbo1500.so`. The app also packages AndroidX DataStore's `libdatastore_shared_counter.so`; its ELF alignment is verified alongside the UVC code, giving five libraries per ABI. JNI names and callbacks are protected by the module's consumer shrinker rules. The old `src/main/libs` binaries, the `camera-usb` JNI link and `libnative` build folders are not packaging inputs.

Generated native objects and libraries live under `native-uvc/build`; source files are not overwritten. The native script converts paths to their Windows short names because GNU make cannot reliably handle spaces. If short names are disabled on a drive, place the checkout and SDK in paths without spaces.

```powershell
.\scripts\build.ps1 -Tasks ':native-uvc:assembleDebug'
.\scripts\build.ps1
.\scripts\verify-native.ps1 -Apk '.\artifacts\app-usbOnly-debug.apk'
.\scripts\verify-native.ps1 -Apk '.\artifacts\app-usbOnly-release-unsigned.apk'
```

The build wrapper copies completed APKs into `artifacts` so a later emulator build cannot replace the ARM delivery APKs. Versioned deliverables are `AirAngelVL-0.2.0-test-universal.apk` and `AirAngelVL-0.2.0-release-unsigned-universal.apk`; `SHA256SUMS.txt` records their checksums. The verifier checks every packaged native library's ABI, 16 KB ELF load alignment, exact library inventory, duplicate names and 16 KB APK ZIP alignment. It fails on unexpected transitive native libraries. The release APK is deliberately unsigned; sign with the established application key before distributing an update. Its production application ID is unchanged.

## Separate emulator build

```powershell
.\scripts\build.ps1 -Validation
.\scripts\verify-native.ps1 -Validation -Apk '.\artifacts\app-usbOnly-x86_64-validation-debug.apk'
.\scripts\build.ps1 -Validation -Tasks ':camera-usb:assembleUsbOnlyDebugAndroidTest'
```

The explicit validation profile (`-PvalidationAbis=x86_64`) builds native code into a separate generated directory, uses package `com.airangelvl.validation`, and disables app release variants. It is an emulator artifact, not the ARM delivery APK. Connected test commands must select only the disposable emulator using process-local `ANDROID_SERIAL`; do not target connected personal phones or existing emulator instances.

## Validation boundaries

`-Verify` builds debug and minified release, runs Android lint including every active project dependency and the generated UVC Java source, and runs the app, USB, core and diagnostics unit-test tasks. The USB instrumentation APK explicitly targets API 34, matching the app, and uses the selected ARM or x86_64 ABI set. Local tests do not establish UVC hardware compatibility, real codec performance or thermal behavior. Validate the APK on physical API 24/28 ARM32 and recent ARM64 phones, including a 16 KB device, with MJPEG and YUY2 cameras before claiming device support.

Native modifications preserve the original source/license notices. Before distribution, include the upstream Apache/BSD/LGPL/libjpeg notices and the corresponding modified source as required by those licenses. Do not substitute the original prebuilt native libraries after verification.
