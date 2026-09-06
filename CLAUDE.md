# Development guidance

AirAngelVL 1.0.0 is a USB-only Android camera app for AirAngel training and simulation. The supported build contract is minimum API 24, compile/target API 36, and a universal ARM32/ARM64 APK. See BUILDING.md for the pinned toolchain and commands; use scripts/build.ps1 on this Windows host.

## Scope and ownership

- app owns foreground lifecycle, USB discovery/permissions, CameraSessionController serialization, UI, transactional media saving, persisted settings and OS thermal monitoring.
- camera-usb owns each UVC connection, mode negotiation, the shared GLES renderer, and Surface-input AVC recording.
- native-uvc builds the preserved 3.2.7-based external UVC source with its native patches. Preserve 16 KB ELF and APK alignment, both ARM ABIs, and JNI consumer keep rules.
- core and diagnostics provide contracts and synchronized bounded diagnostics.
- camera-wifi, updater and feature_wifi_rec remain source-only and are excluded from the shipping build. Do not re-enable unfinished modules as part of a USB fix.

## Behavior to preserve

Obtain Camera permission before USB permission. Report ready only after a valid first frame. Preview, JPEG and video use the same image transform. Keep output dimensions and aspect fixed within each recording; color tuning may change live. Recording is silent and stops/saves on background, screen lock, detach or surface loss. Never resume recording automatically.

Keep recording start/stop serialized and cancellation safe. Complete or abort each newly created media transaction exactly once, close owned resources, and preserve existing media. Save to public Pictures/AirAngelVL and Movies/AirAngelVL; request legacy write permission only on API 24–28. Always leave Stop available during recording, including thermal and storage warnings.

## Verification

Run scripts/build.ps1 -Verify and scripts/verify-native.ps1 on both APKs after relevant changes. Test lifecycle races, slow frame timestamps, graphics/encoder output, media failures and permission recovery. Use explicit ANDROID_SERIAL for isolated emulator testing; preserve personal devices and pre-existing emulators.

VALIDATION.md records actual outcomes. USB_ENDOSCOPE_TESTING.md records remaining physical acceptance. Do not claim startup latency, frame-drop rate, sustained-use performance, temperature thresholds or phone compatibility without measurements. OS thermal status is not a temperature measurement.

The debug app is com.airangelvl.debug (AirAngel VL), separate from the original. The minified release retains com.airangelvl and requires the established signing key for an in-place update. The x86_64 validation app is a separate emulator artifact.

