# Physical-device acceptance

Record a separate result for each phone/Android/camera/adapter combination. The APK declaring API 24+ or an emulator test passing is not a physical-device result.

## Record the environment

- APK filename and SHA-256, application ID, version, debug vs minified release, signing certificate.
- Phone model, Android/API version, SoC, available RAM, 32-/64-bit OS and supported ABIs.
- 4 KB / 16 KB runtime page size, USB host/OTG setting and power/adapter/hub configuration.
- Camera model/VID/PID, advertised MJPEG/YUYV modes/frame intervals and selected mode.

## Minimum matrix

| Target | Runtime | Camera coverage | Status |
| --- | --- | --- | --- |
| ARM32, Android 7/API 24 | Physical low-memory phone | MJPEG; YUYV if available | Pending |
| ARM32 or ARM64, Android 9/API 28 | Physical phone | Photos/video/legacy storage permission | Pending |
| ARM64, Android 10/API 29 | Physical phone | MediaStore and reconnect | Pending |
| ARM64, Android 11/API 30 | Physical phone | Preview lifecycle and permissions | Pending |
| ARM64, Android 13–16 / API 33–36 | Physical phones | Permissions, gallery, repeated lifecycle | Pending |
| Samsung, recent Android | Physical One UI phone | Supported camera set and AVC colors | Pending |
| Qualcomm and MediaTek | Physical phones | Codec fallback; sustained recording | Pending |
| Pixel, Android 17 / API 37 (current stable major release) | Physical phone | Permissions, lifecycle, gallery | Owner reports USB connection, preview, photos and video worked on Pixel 10 Pro XL with 0.2.0 debug; final minified 1.0.0 recheck pending |
| ARM64, 16 KB pages | Physical supported device | Minified release native load and capture | Pending |

## Cases

1. Launch without a camera; grant/deny/regrant Camera and USB permissions. Cancel permission dialogs. Test returning from App Info after permanently denied permission.
2. Attach while open, attach before opening, unplug during connection and during mode fallback. Repeat ten connect/disconnect cycles. Unsupported/underpowered hardware must show a recoverable error instead of false readiness.
3. Confirm first-frame readiness and unscaled geometry with an asymmetric reference target. Exercise MJPEG-only, YUYV-only, different format-specific sizes, slow (5/15fps) cameras, and a mode that advertises but cannot start.
4. Capture/save photos on API 24/28 and 29+. Deny legacy Storage permission and verify preview continues. Confirm destination, image dimensions, orientation, color adjustments and gallery visibility.
5. Double-tap Record; immediately Cancel; disconnect or lock during startup; begin a second recording after each case. There must be one active encoder and a clear result.
6. During recording, capture a photo, change image tuning, press Home, lock, switch apps, rotate landscape orientation, remove USB and destroy/recreate the preview. Verify each completed output plays, REC clears, and recording does not automatically resume.
7. Compare video/photo pixels to the same preview frame with brightness/contrast/saturation extremes and aspect crops. Verify colors across different encoder vendors and file durations at slow input rates and under frame drops.
8. Inject codec setup failure, no output/EOS, null MediaStore insert/stream, full storage and publication failure. Verify visible errors, bounded waits, released resources, and no published corrupt media.
9. Record at least 10 minutes on representative devices. Measure first-frame/start/stop times, playback duration, memory growth, dropped frames, remaining space and OS thermal status. Do not label OS thermal status as temperature.
10. Use small landscape screens, cutouts, gesture and three-button navigation, large text (up to 200%), and TalkBack. All controls and status/recovery actions must remain reachable.
11. Repeat preview/photo/video on the **minified release** signed with the intended test/release key. Check native loads, JNI callbacks and 16 KB runtime behavior.

A forcibly killed process, an unresponsive kernel USB call, or a vendor codec stuck in native code can prevent normal finalization. Keep such failures separate from graceful background/lock/disconnect behavior. Preserve completed media; only the app's newly created unfinished targets may be removed.


The current stable major release was checked on September 6, 2026 against [Google's Android 17 release announcement](https://developer.android.com/blog/posts/android-17-is-here), dated June 16, 2026. The publishing candidate targets API 36; this remains separate from its runtime OS coverage.

