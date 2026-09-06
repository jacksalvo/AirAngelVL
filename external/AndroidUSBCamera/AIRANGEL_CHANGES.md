# AirAngelVL vendored-source record

Upstream: https://github.com/jiangdongguo/AndroidUSBCamera

Base: tag 3.2.7, commit 7fa7a99996e242a83c2c6258009b590d6ad51f8d.

This is a vendored source snapshot, not a submodule. Existing upstream notices and component-specific licenses are preserved. Original upstream Git metadata and the pre-repair source snapshot were preserved locally before publication; the parent AirAngelVL repository tracks the modified source files directly.

AirAngelVL's native-uvc module compiles libuvc's public Java API and JNI sources plus BuildCheck and HandlerThreadHandler from libuvccommon. Local modifications include Android receiver compatibility, USB control-block ownership/descriptor handling, supported-size handling, USB stream compatibility fixes, explicit native 16 KB alignment, and the min-SDK/native build configuration. The pre-existing libnative CMake alignment patch is retained even though that module is not a shipping input.

The original checkout already had the libausbc EGLEvn.kt and RotateType.kt files removed. That unfinished renderer/module remains excluded; AirAngelVL provides its own GLES renderer in camera-usb. Legacy binaries, demo media/APKs, caches and local build-arm64 directories are intentionally omitted from the source snapshot.

Use the root BUILDING.md instructions. Do not replace the built libraries with upstream or preserved prebuilt binaries: both ARM ABIs must be built from this same patched source and pass the root verification script.
