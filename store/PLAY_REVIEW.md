# Play listing and declaration draft

Prepared September 6, 2026 from the shipping source and existing merged release manifest. Review against the final signed bundle before submission. These files do not mean that a listing has been created or policy answers submitted.

## Confirmed intended use and remaining publication choices

- Owner confirmed the public support email is `coppercanyonanesthesia@gmail.com`. This is separate from the Play account owner `jacksalvo@gmail.com`.
- Owner clarified that this app is a training and simulation companion for the AirAngel Video Laryngoscope nonprofit project, not a medical device or patient-care tool. It may support other UVC cameras; no combination is guaranteed. Keep the training-only purpose consistent across the app, screenshots, listing and policy.
- Owner confirmed there is no medical-device approval in any country and that this app is used for training. The listing explicitly prohibits clinical use and includes the health-app disclaimer. Absence of a clinical approval is not itself a blocker for the declared training-only purpose. Do not suggest authorization for patient care outside the United States. If the intended functionality or marketing later changes to clinical use, reassess the declarations and applicable requirements before that change ships.
- Confirm price, distribution countries, target audience and release track/rollout. Source code does not determine these account choices.

## Ready text

- App name: `en-US/title.txt` (11 characters; limit 30).
- Short description: `en-US/short-description.txt` (limit 80; verify with the included character check before upload).
- Full description: `en-US/full-description.txt` (limit 4,000; explicitly training and simulation only).
- Release notes: `en-US/release-notes.txt` (limit 500 per language).
- Privacy policy: `privacy-policy.txt` for an offline in-app screen and a publicly accessible policy page with identical content. The project name identifies the app, as required; adding the final Play developer display name is optional if desired.

Google specifies listing text limits and requires a public contact email. [Create and set up your app](https://support.google.com/googleplay/android-developer/answer/9859152?hl=en)

## Proposed declarations from this audit

| Form item | Proposed answer | Evidence / limit |
| --- | --- | --- |
| Contains ads | No | No ads or advertising SDKs in shipping dependencies. |
| Collects or shares the required user data types | No | Camera, USB information, settings and diagnostics are processed locally; no network permission or upload path found. Recheck final artifact. |
| Account creation | No | No account/login functionality. |
| Data deletion | No server-side account or data to delete | Users delete media through their gallery; private settings through Android app storage. Avoid claiming uninstall deletes gallery media. |
| Encryption in transit | Not applicable to app uploads | There are no app uploads. Do not claim an independently assessed encryption feature or select a security badge without evidence. |
| App access | All functionality available without login | Reviewer needs external USB hardware, Camera permission, USB consent, and legacy Storage permission on Android 7-9. |
| Advertising ID | Not used | No AD_ID permission or advertising ID implementation in shipping graph. |
| Health apps declaration | Proposed: Medical Reference and Education, plus any other applicable health-training category in the live form | This is medical training/simulation functionality, so do not select no health features. The owner defines the app as a training tool, not a regulated medical device. Review the live options and save proposed answers for owner review; no form has been submitted by this audit. |
| Content rating / target audience | Complete using actual content and audience | Do not invent an IARC rating or select children as the target audience by default. |
| Category | Medical or Education, according to the live category options and owner preference | Describe the training/simulation purpose consistently. A category does not confer medical-device approval. |

Google excludes processing confined to the device from its collection definition. Sharing has its own rules, including some transfers between apps; this app saves user-requested media through Android's shared-media service and does not contain an automated transfer to an advertising/analytics recipient. Independently configured gallery/cloud backup behavior is disclosed in the policy. [Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)

Every app needs an in-app privacy policy or link plus an active public policy URL, including apps that collect nothing. The policy needs an app/developer identity, contact mechanism, data handling and deletion/retention details. [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en)

The clarified use is training and simulation only. The description states that the app is not a medical device, does not diagnose/treat/cure/prevent conditions, and is not for patient care; it directs users to a qualified health professional for medical advice. Complete the Health apps declaration for that educational context and keep external hardware requirements clear. Do not imply clinical approval, effectiveness, or authorization in any country. These are proposed publication disclosures, not a submitted Console declaration. [Health policy](https://support.google.com/googleplay/android-developer/answer/16679511?hl=en), [Health declaration](https://support.google.com/googleplay/android-developer/answer/14738291?hl=en)

## Reviewer access instructions

No account, password, subscription, or in-app location restriction is required. Use a mannequin or simulation target; this app is for training only. Use an ARM Android phone running Android 7.0 or newer with USB host/OTG support. Connect a UVC USB camera through a suitable adapter, grant Camera permission, and approve Android's USB-access prompt. Wait for "Camera ready." Capture saves a JPEG. Record starts a silent video; Stop finalizes it to the gallery. On Android 7-9, grant Storage permission when saving. Settings adjusts image framing and color. Without a compatible connected USB camera, the app displays a connection prompt and capture/recording is unavailable. This is expected hardware-dependent behavior.

Document the exact known-working camera and adapter if the owner supplies them; do not infer the model from a screenshot.

## Assets

- High-resolution app icon: 512 x 512 PNG, up to 1,024 KB.
- Feature graphic: 1,024 x 500 JPEG or PNG without alpha.
- At least two phone screenshots: JPEG or PNG without alpha, dimensions 320-3,840 pixels, longest side no more than twice the shortest. Keep originals. The user's Pixel captures may need fitting/cropping if their aspect exceeds 2:1.
- Screenshots should represent the final release UI. Prefer new captures after removing the Test label. If preparing layout edits, preserve the actual camera content and do not invent a successful state or obscure a material warning. Label mannequin imagery as simulation if used to avoid implying patient validation.
- Add concise alt text for each approved screenshot; write it after inspecting the selected image.
- Do not advertise guaranteed compatibility, guaranteed save recovery, clinical approval, or guaranteed absence of all vulnerabilities. The audit found no ad/tracking implementation, but "malware-free" is not an independent security certification.

The asset formats above are mandatory; four high-resolution screenshots are an additional recommendation for some discovery placements. [Preview asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en)

## Source audit evidence

- `settings.gradle.kts`: only app, core, camera-usb, native-uvc, diagnostics included. Wi-Fi/updater sources are retained but do not ship.
- `app/src/main/AndroidManifest.xml` and existing merged `usbOnlyRelease` manifest: CAMERA, WRITE_EXTERNAL_STORAGE capped at API 28, and AndroidX signature-only internal receiver permission. No Internet, microphone, location, contacts, advertising ID, broad media-reading or background recording permission.
- `app/build.gradle.kts`, module dependency files: no advertising, analytics or automatic crash-reporting SDK. Standard AndroidX/Hilt/coroutines/Timber plus local USB library and xlog.
- `MediaStoreRepository.kt`: writes new user media to public Pictures/AirAngelVL and Movies/AirAngelVL; does not enumerate other photos or upload data. File names contain capture time and a random suffix, not an account or advertising identifier.
- `ImageSettingsRepository.kt`: brightness/contrast/saturation/aspect in private DataStore. Despite its class name, `InMemorySettingsRepository.kt` persists successful USB modes in a separate private `camera_formats` DataStore; other compatibility/profile state is in memory.
- `DiagnosticsLogger.kt`: bounded 1,000-event in-memory buffer; only shipping caller observed logs `media.saved:<result type>`. `UsbMetricsCollector.kt`: local frame-rate counters. No export UI or upload implementation. USB native wrapper can log hardware/error details to Android's local log.
- `XLogWrapper.init` is not called by shipping code. Its unused file-printer capability is not evidence of persistent log exports. Avoid saying the app has a diagnostic-export feature.
- `allowBackup=false` and explicit exclusions now cover private app data in the legacy backup rules and Android 12+ cloud-backup/device-transfer rules, including DataStore files. These rules do not control independently configured backup of shared gallery media.
- Owner clarified that the new app is for AirAngel training and simulation only, with no medical-device approval in any country. Repository documents and in-app About text must reflect that purpose. Owner reports the connected Pixel successfully previewed/captured/recorded; that is one actual combination, not universal compatibility evidence.
- The [AirAngel homepage](https://www.airangelblade.org) corroborates its not-for-profit resource-center mission, under-resourced settings, training purpose, Android/OTG/separate-camera requirement, FDA non-approval and US medical-use prohibition. It does not corroborate an adoption count. The listing attributes the thousands-of-clinicians figure to the nonprofit, based on the owner's statement, and explicitly separates the wider project's history from this new training app, its user count and its permitted use; the website did not independently corroborate that count.

## Final checks before submission

1. Confirm distribution countries, price, target audience and release track/rollout, and validate description lengths. Support email, project identity and training-only intended use are confirmed. Do not treat lack of clinical approval as a publication blocker for this declared training-only app.
2. Verify final release package, version, permissions, network-free dependency graph and branding from the artifact, not just sources.
3. Recheck both ABI contents and 16 KB alignment after the publishing toolchain changes.
4. Install the signed/minified candidate on the known-working phone/camera and confirm preview, capture, video playback and gallery saves. Preserve signing continuity and existing media.
5. Publish and open the privacy URL without authentication; confirm its text matches the offline policy.
6. Upload assets and complete actual Play account declarations. Verify all required onboarding/testing tasks from the live Console.
7. Review exact bundle, track, rollout, release notes, listing and declarations before submitting the release.
