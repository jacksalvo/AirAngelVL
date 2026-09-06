# AirAngelVL

AirAngelVL displays a USB UVC camera feed and saves photos and silent H.264/MP4 video. Version 1.0.0 supports Android 7.0 (API 24) and newer, targets API 36, and packages ARM32/ARM64 native libraries. It is a training and simulation camera companion for the [Air Angel Video Laryngoscope nonprofit project](https://www.airangelblade.org/); other compatible UVC cameras may also work.

## License and contributing

Original AirAngelVL code is licensed under [GNU GPL version 3](LICENSE) (`GPL-3.0-only`). Third-party components retain their own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Fork this repository, create a branch, and follow [BUILDING.md](BUILDING.md) to build and validate changes. The patched USB dependency is included as source, so no submodule checkout is required. When reporting a camera issue, include the phone/Android version, camera model, USB adapter and reproduction steps; do not post private recordings, credentials or identifying device logs.

## Use

1. Open the app and allow Camera permission.
2. Connect a UVC camera through a USB host/OTG-capable phone and allow USB access.
3. Wait for **Camera ready** before capturing or recording.
4. Use Settings for original framing or a centered aspect-ratio crop, brightness, contrast, and saturation. These image settings persist and apply to preview, saved photos, and video.
5. Press Stop to save. Backgrounding, screen lock, USB removal, or loss of the preview also stops/finalizes recording. The app never resumes recording automatically.

Original framing fits the entire image without stretching. Recording dimensions and aspect ratio remain fixed within each file; stop recording to change the aspect ratio. Color tuning remains adjustable while recording. Saved output is capped at 720p to limit memory use on older phones. Photos use JPEG; video has no audio. Successful saves go to public `Pictures/AirAngelVL` or `Movies/AirAngelVL`. Android 7–9 additionally asks for Storage permission when saving; denying it leaves preview available.

The phone must supply USB host support, sufficient camera power, and usable UVC support in its OS/kernel. The app tries advertised MJPEG and YUYV modes and smaller/slower fallbacks; it cannot add missing phone hardware/kernel functionality. Thermal warnings use the OS status where available (Android 10+), not an estimated body or camera temperature. Recording starts with at least 1,000 MB free and stops when available space reaches 100 MB.

## Build and test

See [BUILDING.md](BUILDING.md) for the pinned JDK 17 / Gradle 8.11.1 / AGP 8.10.1 / NDK 27 setup.

```powershell
.\scripts\setup-toolchain.ps1
.\scripts\build.ps1 -Verify
```

- Debug: package `com.airangelvl.debug`, launcher **AirAngel VL**, signed with the local Android debug key. It can coexist with the original app.
- Release: package `com.airangelvl`, minified; signed for publication using the private upload key (see RELEASE_SIGNING.md). Do not uninstall an existing app just to work around a signing mismatch.
- Emulator validation: a separate x86_64/package build; it is not the ARM phone deliverable.

[VALIDATION.md](VALIDATION.md) records executed checks and limitations. [USB_ENDOSCOPE_TESTING.md](USB_ENDOSCOPE_TESTING.md) is the physical-device acceptance checklist. Passing builds and synthetic tests does not establish compatibility with an untested phone/camera combination.

Generated APKs, device logs, screenshots and machine-specific settings are kept out of Git. Run the build commands to create local artifacts. The validation record's artifact links refer to the original local verification workspace and become available as corresponding checks are reproduced; they are not committed downloads.

## Implementation

- `app`: foreground lifecycle, USB permission/discovery, serialized camera/recording controller, UI, media transactions, persisted settings, thermal monitoring.
- `camera-usb`: per-connection UVC backend; advertised-mode selection, first-frame confirmation, a shared GLES renderer, and a Surface-input AVC encoder.
- `native-uvc`: source-built ARM32/ARM64 UVC JNI libraries and their shrinker rules. The source is the preserved 3.2.7 external tree with its native compatibility patches.
- `core` and `diagnostics`: contracts and bounded synchronized diagnostic storage.

Wi-Fi, updater, and dynamic-feature folders remain preserved but are outside the shipping dependency graph. The application does not perform network discovery, automatic updates, or background recording.

## Intended use and privacy

This app is for training and simulation only. It is not a medical device and does not diagnose, treat, cure, or prevent any medical condition. Consult a qualified healthcare professional for medical advice, diagnosis, or treatment. Do not use this app for patient care.

The wider AirAngel project supports under-resourced settings, medical missions, and simulation training. Its system has not been reviewed or approved by the US FDA and must not be used for medical purposes in the USA.

The app contains no ads, analytics or tracking SDKs, requires no account, and has no internet permission. It processes and saves media locally; other gallery or backup apps may sync those files according to their own settings. Existing media is not migrated or deleted. Read the in-app privacy policy under Settings or the policy in `store/privacy-policy.txt`.


