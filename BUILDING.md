# Building the USB application on Windows

The shipping application is `usbOnly`, version **1.0.0 / code 3**, with minimum Android 7.0 / API 24. The release keeps package `com.airangelvl`. The signed debug APK is **AirAngel VL**, package `com.airangelvl.debug`, so it can be installed alongside the original application. Wi-Fi, updater and the dynamic-feature source folders remain preserved but are excluded from `settings.gradle.kts`. The old `full` variant is not a shipping build.

## Toolchain

- Temurin JDK **17.0.20.1+1** (portable; no global Java settings required).
- Gradle **8.11.1**, obtained by the wrapper and checked against its pinned SHA-256.
- Android Gradle Plugin **8.10.1**; Kotlin **1.9.24**.
- Android SDK platform **36**, build-tools **35.0.0** (build and 16 KB ZIP verification).
- Android NDK **27.0.12077973**.

On this PC the SDK is `C:\Android\Sdk` and the portable JDK is `C:\Coding\AAVL-toolchains\jdk-17.0.20.1+1`. Android Studio's bundled Java 25 is incompatible with this Gradle/Kotlin baseline. The setup script writes the ignored project-local `.gradle/config.properties` `java.home` entry used by the existing `#GRADLE_LOCAL_JAVA_HOME` setting, preserving other properties. It does not change global IDE or Java settings.

```powershell
.\scripts\setup-toolchain.ps1
.\scripts\build.ps1 -Verify
```

`setup-toolchain.ps1` downloads the pinned official Adoptium archive, verifies its SHA-256 before extraction, and checks the installed SDK packages. Use `-ToolchainDirectory` and `-SdkRoot` for alternate locations. `build.ps1` accepts `-JavaHome`, `-SdkRoot`, and `-Tasks`; it sets process-local environment variables and writes only the ignored `local.properties` SDK path. It restores the caller's Java/SDK environment afterward.

The command-line build has been verified using the wrapper's process-local JDK TCP-loopback fallback because AF_UNIX connections fail on this Windows host. It does not change Windows networking or global Java options. Android Studio Quail 4 (2026.1.4) successfully imported and synced the earlier API 34 revision on September 6, 2026 using the project-local JDK 17 setting. The API 36 publishing migration is verified through the CLI; sync the changed Gradle files in Studio before its next Run. Its module list now matches `settings.gradle.kts`; do not manually add excluded source folders back to the build. The debug APK was rebuilt through `scripts/build.ps1`, installed over wireless ADB on the physical Pixel 10 Pro XL, and launched to the camera-permission prompt. This does not establish USB-camera compatibility or verify the IDE Run build path.

For Android Studio testing, open the repository root, use the `app` run configuration and `usbOnlyDebug` variant, and select the physical **Google Pixel 10 Pro XL**. A similarly named **Pixel 10 Pro XL** emulator may also be listed. The phone app is **AirAngel VL**. Grant its camera permission on the phone, then connect the USB camera. If an IDE build encounters a host networking error, build with `scripts/build.ps1 -Tasks ':app:assembleUsbOnlyDebug'` and install `artifacts/app-usbOnly-debug.apk` with `adb -s <phone-serial> install -r`; obtain the current serial from `adb devices -l`.

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

The build wrapper copies completed APKs into `artifacts` so a later emulator build cannot replace the ARM delivery APKs. Versioned deliverables are `AirAngelVL-1.0.0-debug-universal.apk` and `AirAngelVL-1.0.0-release-universal.apk` (when signing is configured); `SHA256SUMS.txt` records their checksums. The verifier checks every packaged native library's ABI, 16 KB ELF load alignment, exact library inventory, duplicate names and 16 KB APK ZIP alignment. It fails on unexpected transitive native libraries. The normal build is unsigned when signing credentials are absent. Use `scripts/release-signing.ps1 -Command Build -Verify` for the signed APK and Play bundle; see [RELEASE_SIGNING.md](RELEASE_SIGNING.md). Its production application ID is unchanged.

## Google Play App Bundle

The default build and `-Verify` also run `:app:bundleUsbOnlyRelease`. For only the Play bundle and its packaging verification:

```powershell
.\scripts\build.ps1 -Tasks ':app:bundleUsbOnlyRelease'
.\scripts\verify-bundle.ps1 -Bundle '.\artifacts\app-usbOnly-release.aab'
```

`build.ps1` copies the bundle to `artifacts/app-usbOnly-release.aab` and a filename containing the first 12 characters of its SHA-256. The checksum manifest includes AAB files. When an artifact alias already contains different bytes, the helper preserves its previous contents in `artifacts/archive` under the full old SHA-256 before refreshing the alias. APK filenames come from AGP output metadata, supporting unsigned and signed release outputs without assuming a signing setup.

`verify-bundle.ps1` downloads official Google bundletool **1.18.3** into ignored `.toolchains`, checks the pinned SHA-256, validates the bundle structure and release manifest, and requires the exact ARM32/ARM64 library inventory and `PAGE_ALIGNMENT_16K`. It generates a universal APK from that exact bundle, then checks every ELF and APK ZIP offset using `verify-native.ps1`. Evidence is saved under a fresh `artifacts/bundle-verification` directory on each run. The generated APK uses a local debug signature only for packaging validation and must not be distributed as the production release. Bundle verification does not establish an upload signature or Play App Signing continuity; those must be checked separately on the final signed AAB.

The toolchain follows [AGP 8.10 compatibility](https://developer.android.com/build/releases/agp-8-10-0-release-notes); generated-APK and bundle checks follow [Android's page-size guidance](https://developer.android.com/guide/practices/page-sizes) and [bundletool documentation](https://developer.android.com/tools/bundletool).

## Separate emulator build

```powershell
.\scripts\build.ps1 -Validation
.\scripts\verify-native.ps1 -Validation -Apk '.\artifacts\app-usbOnly-x86_64-validation-debug.apk'
.\scripts\build.ps1 -Validation -Tasks ':camera-usb:assembleUsbOnlyDebugAndroidTest'
```

The explicit validation profile (`-PvalidationAbis=x86_64`) builds native code into a separate generated directory, uses package `com.airangelvl.validation`, and disables app release variants. It is an emulator artifact, not the ARM delivery APK. Connected test commands must select only the disposable emulator using process-local `ANDROID_SERIAL`; do not target connected personal phones or existing emulator instances.

## Validation boundaries

`-Verify` builds debug, minified release and the release App Bundle, runs Android lint including every active project dependency and the generated UVC Java source, and runs the app, USB, core and diagnostics unit-test tasks. The USB instrumentation APK uses the target API configured by its module, and uses the selected ARM or x86_64 ABI set. Local tests do not establish UVC hardware compatibility, real codec performance or thermal behavior. Validate the APK on physical API 24/28 ARM32 and recent ARM64 phones, including a 16 KB device, with MJPEG and YUY2 cameras before claiming device support.

Native modifications preserve the original source/license notices. Before distribution, include the upstream Apache/BSD/LGPL/libjpeg notices and the corresponding modified source as required by those licenses. Do not substitute the original prebuilt native libraries after verification.
