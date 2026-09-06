# Validation record — AirAngelVL 0.2.0

Completed September 6, 2026 in `C:\Coding\Coding Projects\AAVL`.

The compatibility and reliability changes are implemented. The ARM debug and minified release builds, 65 JVM/Robolectric tests, and dependency-wide lint pass. Runtime results include the documented 16 KB emulator platform limitation below. Physical USB-camera acceptance is still required; the runtime checks below use synthetic images and do not establish endoscope/phone compatibility.

Generated binaries, raw device logs, screenshots and test reports are intentionally not committed. The artifact paths below identify local evidence from the implementation run; build instructions reproduce these checks and generate corresponding files.

## Deliverables

| Artifact | Purpose |
| --- | --- |
| AirAngelVL-0.2.0-test-universal.apk (`artifacts/AirAngelVL-0.2.0-test-universal.apk`) | Installable, signed test app; ARM32 + ARM64; package `com.airangelvl.debug`, launcher **AirAngel VL Test**. Can coexist with the original. |
| AirAngelVL-0.2.0-release-unsigned-universal.apk (`artifacts/AirAngelVL-0.2.0-release-unsigned-universal.apk`) | Minified ARM32 + ARM64 release; package `com.airangelvl`. Requires the established signing key before distribution as an update. |
| SHA256SUMS.txt (`artifacts/SHA256SUMS.txt`) | Exact artifact checksums, including separate instrumentation/emulator artifacts. |

Phone test APK SHA-256: `3fa275bf554943939517ccbbe7af32932cf755565c76b817417c1dad23360942`.

Unsigned release SHA-256: `a6d24f97fce42377d35bf2fe4f7092e7b380379631549b1ec5fb960e9716d4df`.

Both application APKs declare minimum API 24, target API 34, version code 2 / version 0.2.0. The x86_64 validation APK and instrumentation APKs are test fixtures, not the phone application deliverables.

## Implemented behavior

- Restored the pinned JDK 17 / Gradle 8.7 / AGP 8.5.2 / NDK 27 build. Wi-Fi, updater and dynamic-feature sources remain preserved outside the shipping dependency graph.
- Built both ARM ABIs from the same preserved, patched 3.2.7-based UVC source. Removed duplicate-binary selection and private-field receiver workarounds; protected JNI entry points and callbacks from shrinking.
- Consolidated foreground USB discovery and permission handling, fixed older Android startup resources/APIs, and added visible permission, unsupported-hardware and retry states. Camera opening precedes capability discovery; preview readiness requires an actual first frame.
- Added a shared GLES renderer for preview, JPEG and Surface-input AVC. Image adjustments persist. Output is capped at 720p for memory compatibility; aspect and recording dimensions stay fixed within a recording, while color tuning can change live.
- Serialized start/stop/capture and lifecycle transitions, including cancelled starts and stale asynchronous completions. Recording stops/finalizes on background, lock, detach, surface loss, low storage, thermal limits or encoder failure. It never automatically resumes.
- Added owned media transactions, pending-row publication on API 29+, public gallery destinations and write permission on API 24–28, storage monitoring, bounded encoder completion, and cleanup of newly created unusable outputs.
- Added insets, 48 dp controls, large-text/display-density handling and serialized nonfatal diagnostic logging.

## Automated results

`scripts/build.ps1 -Verify` completed successfully. The final build log (`artifacts/verification.log`), test summary (`artifacts/unit-test-summary.json`), lint summary (`artifacts/lint-summary.json`), and release dependency graph (`artifacts/release-runtime-dependencies.txt`) preserve the evidence.

| Test area | Passing cases |
| --- | ---: |
| Camera/recording lifecycle and surface ownership | 14 |
| Activity startup: API 24, 28, 29, 30, 33, 34 | 6 |
| Media transactions: API 24, 28, 29, 34 | 24 |
| Image settings and successful USB-mode persistence | 2 |
| OS thermal status and listener lifecycle | 3 |
| Frame timing, rendering geometry and mode selection | 13 |
| Concurrent bounded diagnostic logging | 3 |
| **Total** | **65** |

No failures, errors or skipped cases. These include double Record, immediate Cancel, disconnect during startup, duplicate lifecycle stops, failed encoder/finalization/photo operations, invalidated surface replacement, media write/publication failure, idempotent cleanup, persistence reload, and slow/jittery frame timing. The core module has no standalone test cases; it is exercised through its consumers.

Lint scans the app and its shipping module dependencies: **0 errors, 119 warnings**. Of those, 74 are dependency/plugin update notices; the rest concern preserved helper code, logging/style, orientation/ChromeOS scope and cosmetic resources/layouts. There are no NewApi errors. The repair deliberately retains the agreed toolchain/SDK baseline; warnings have not been hidden by a baseline or blanket suppression.

The runtime dependency graph excludes JitPack/libausbc, MMKV, Play Core, Wi-Fi, updater and dynamic features. R8 output preserves the literal UVC class and all three native callback interfaces; see JNI/shrinker verification (`artifacts/release-jni-r8-verification.log`). The debug APK signature verifies successfully; the production release remains unsigned.

Both APKs pass exact ABI/library inventory and ELF/ZIP validation. Each contains four source-built UVC libraries plus DataStore's shared-counter library per ABI: **10 libraries total**, all with 16 KB ELF load alignment and APK ZIP alignment. See the debug (`artifacts/native-debug-verification.log`) and release (`artifacts/native-release-verification.log`) verification logs. These are separate checks, as required by [Android's page-size guidance](https://developer.android.com/guide/practices/page-sizes).

## Runtime results

- **Android 15 / API 35 emulator, x86_64, 4 KB pages:** both target-SDK-34 instrumentation tests pass. They exercise actual SurfaceTexture/GLES rendering, JPEG encode/decode, AVC Surface input and decoded pixel comparison, rotation/mirroring/tuning, slow-frame elapsed duration, and repeated UVC JNI create/destroy without opening USB hardware. App smoke checks pass permission denial → Retry → grant, truthful unsupported-USB state, settings after process restart, live density changes, 800×480 landscape at 200% text, scrollable settings, Home/lock/resume, and no crash/ANR during the run. Final UI evidence (`artifacts/runtime-validation/api35/README.md`).
- **Physical Pixel 10 Pro XL, Android 17 / API 37, ARM64, 4 KB pages:** both offscreen instrumentation tests pass. The observed encoder was `c2.google.avc.encoder`; its hardware-acceleration classification was not measured. This validates the native ARM load and shared graphics/encoding path on this phone, not USB capture or other codec vendors. Only a new temporary instrumentation package was installed, then uninstalled and verified absent. The main app, existing media, phone settings and actual cameras were not modified/opened. Pixel evidence (`artifacts/runtime-validation/pixel-api37/README.md`).
- **Android 17 / API 37.1 emulator, x86_64, actual 16,384-byte pages:** the UVC JNI load/create/destroy test passes. Graphics/UI validation is blocked by an emulator OS SurfaceFlinger crash loop in `mapper.ranchu.so` (`hasReadColorBufferDma` assertion), before the synthetic graphics test could create its cache output. A second supported GPU configuration produced the same platform failure; available data storage was 4.9 GB. No app/test behavior was weakened to obtain a pass. The exact image revision, emulator version and both failure logs are retained. 16 KB emulator evidence (`artifacts/runtime-validation/api37/README.md`).

The current stable major Android release was checked against [Google's Android 17 announcement](https://developer.android.com/blog/posts/android-17-is-here). The API 37.1 emulator's exact installed image/build is recorded separately; a reported release codename alone is not proof of a particular stable quarterly release.

## Preserved baseline and limits

The pre-change source and modified external library were copied to `C:\Coding\AAVL-backups\20260906-082310`: 1,775 files, approximately 83.7 MB, zero copy failures. Generated Gradle caches and the bundled Gradle distribution were excluded. Native patches and old prebuilt libraries remain preserved; the new build does not package those old binaries.

Both task-created emulators (5556 and 5558) were stopped and verified absent. The pre-existing emulator (5554) remains intact. The Pixel's temporary test package was uninstalled and verified absent; existing app installations and media were preserved. Runtime evidence and cleanup record (`artifacts/runtime-validation/README.md`).

The physical-device checklist remains in [USB_ENDOSCOPE_TESTING.md](USB_ENDOSCOPE_TESTING.md). Still required: actual MJPEG/YUYV cameras and failed advertised modes; USB permission/reconnect/unplug during initialization and recording; public-gallery saves and graceful finalization on actual camera streams; physical ARM32/API 24/28, low-memory, Samsung, Qualcomm and MediaTek combinations; and sustained use with the minified release and intended signing key. No startup-latency, frame-drop, battery, thermal-temperature or long-session performance claim is made.

A process kill, an unresponsive kernel USB call, or a vendor codec stuck in native code can prevent normal finalization. A stuck writer retains ownership of its unpublished target until it closes, rather than closing its descriptor underneath native code. No physical 16 KB ARM/UVC session has been validated.

[BUILDING.md](BUILDING.md) documents reproduction. Earlier failed setup, lint and emulator runs are retained as diagnostic history; the final checks above identify the current verified result.


