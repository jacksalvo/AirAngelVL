# Play listing and declaration record

Updated September 6, 2026 from the shipping source, exact signed bundle and saved Console answers. Version 3 / 1.0.0, the approved store material and content declarations were submitted as 11 changes. Console shows Changes in review with quick checks running (up to 15 minutes). The requested release is a full production rollout, with managed publishing OFF. The app is not yet approved or verified as publicly available.

## Confirmed intended use and remaining publication choices

- Owner confirmed the public support email is `coppercanyonanesthesia@gmail.com`. This is separate from the Play account owner `jacksalvo@gmail.com`.
- Owner clarified that this app is a training and simulation companion for the AirAngel Video Laryngoscope nonprofit project, not a medical device or patient-care tool. It may support other UVC cameras; no combination is guaranteed. Keep the training-only purpose consistent across the app, screenshots, listing and policy.
- Owner confirmed there is no medical-device approval in any country and that this app is used for training. The listing explicitly prohibits clinical use and includes the health-app disclaimer. Absence of a clinical approval is not itself a blocker for the declared training-only purpose. Do not suggest authorization for patient care outside the United States. If the intended functionality or marketing later changes to clinical use, reassess the declarations and applicable requirements before that change ships.
- Owner confirmed free, worldwide availability with no age limits and asked to proceed with the first production release. Do not enable Restrict minor access. Target audience describes whom the app was designed for and is separate from availability/content rating; do not automatically mark every child age group merely to allow downloads. Complete the live forms truthfully for the USB camera/training purpose. [Audience guidance](https://support.google.com/googleplay/android-developer/answer/9867159?hl=en)

## Current Console state (September 6, 2026)

- AirAngel VL, package com.airangelvl, is created; Console app ID 4974624117466772766. The public source/helper is on main through commit 8211f07.
- The owner approved the Developer Program Policies and US export declarations, and disabling the optional installer check. Those creation steps are complete; automatic installer protection is OFF.
- Saved: public privacy-policy URL; sign-in/app access with no restrictions; Ads No; Advertising ID No; Government No.
- Saved Health apps category: Medical reference and education. The Console currently reports no regional requirements. This does not establish medical-device approval or authorization for clinical use.
- The owner completed the remaining forms. Saved audience boxes 9-12, 13-15, 16-17 and 18+ are checked; under-9 groups are unchecked. These answers were reviewed and left unchanged. The app does not implement an age gate.
- Final Data safety was read back: no data collected, no sharing with third parties, the correct privacy URL, and the owner-selected Families Policy commitment set to Yes.
- Console shows IARC completed September 6 at 7:06 PM, category All Other: ESRB Everyone, PEGI 3, all ages in Brazil and Germany, and no content descriptors. The earlier IARC-terms blocker is resolved by the owner's own completion. Do not enable Restrict minor access.
- Category: Education. All 177 availability selections are saved: 176 countries/regions plus Rest of World.
- Play App Signing is active. The uploaded certificate matches the established upload-key SHA-256 f0510dd557bab1398a8f4d48869f93beaba3cc608c58216d99ca18984ab8ac8e; this is distinct from the Google-managed delivery key.
- The exact version-3 / 1.0.0 bundle was uploaded manually, then Stage, Validate, RecoverCommit and CommitDraft completed through the API using the same edit. Exact listing text, icon, feature graphic, both screenshots, release notes and bundle SHA-256 75f3c4a65d762b531fd67d16e8ec6bf6b3dc8cf059116bfc4973e0f917a5ba40 were verified.
- The first commit returned HTTP 400 for unsupported changesNotSentForReview. Recovery kept the same edit and ERROR_IF_IN_REVIEW, then committed the draft successfully with the unsupported parameter omitted. Detailed API identifiers/responses remain in the local journal outside Git.
- The API commit initially saved a production draft. After the owner completed the remaining forms, the 1.0.0 release preview was confirmed and saved. Submit 11 changes and the Send changes for review confirmation succeeded. The changes cover Start full rollout, all 176 countries/regions plus Rest of World, the complete en-US listing and content declarations.
- Publishing overview shows Changes in review and a Remove changes control, with quick checks running for up to 15 minutes. Managed publishing is OFF. Submission is queued through those checks; no Google approval or public availability is verified.
- A non-blocking native debug-symbol warning remained in Console. Matching unstripped symbols exist locally for the eight source-built UVC libraries, with build IDs and stripped bytes matching the submitted AAB; no symbol ZIP is prepared or embedded, and matching DataStore symbols are not established. Future symbol upload would support crash diagnostics. The submitted candidate is unchanged; successful submission does not establish physical-device compatibility.

## Ready text

- App name: `en-US/title.txt` (11 characters; limit 30).
- Short description: `en-US/short-description.txt` (limit 80; verify with the included character check before upload).
- Full description: `en-US/full-description.txt` (limit 4,000; explicitly training and simulation only).
- Release notes: `en-US/release-notes.txt` (limit 500 per language).
- Privacy policy: `privacy-policy.txt` for the offline in-app screen. The public policy is https://jacksalvo.github.io/AirAngelVL/privacy-policy.html. GitHub Pages returned unauthenticated HTTP 200 on September 6, 2026; its HTML exactly matches docs/privacy-policy.html and store/privacy-policy.html. Publish future changes to both copies and the in-app text together.

Google specifies listing text limits and requires a public contact email. [Create and set up your app](https://support.google.com/googleplay/android-developer/answer/9859152?hl=en)

## Declaration answers and audit evidence

| Form item | Answer / current state | Evidence / limit |
| --- | --- | --- |
| Contains ads | No; saved | No ads or advertising SDKs in shipping dependencies. |
| Collects or shares the required user data types | No; final saved answer read back and submitted | Camera, USB information, settings and diagnostics are processed locally; no network permission or upload path found. Recheck final artifact. |
| Account creation | No | No account/login functionality. |
| Data deletion | No server-side account or data to delete | Users delete media through their gallery; private settings through Android app storage. Avoid claiming uninstall deletes gallery media. |
| Encryption in transit | Not applicable to app uploads | There are no app uploads. Do not claim an independently assessed encryption feature or select a security badge without evidence. |
| App access | All functionality available without login; saved as no restrictions | Reviewer needs external USB hardware, Camera permission, USB consent, and legacy Storage permission on Android 7-9. |
| Advertising ID | Not used; saved | No AD_ID permission or advertising ID implementation in shipping graph. |
| Health apps declaration | Medical reference and education; saved | This is medical training/simulation functionality, so do not select no health features. The owner defines the app as a training tool, not a regulated medical device. The current Console reports no regional requirements; this does not confer clinical approval. |
| Content rating / target audience | Owner completed IARC; ESRB Everyone / PEGI 3; target audience ages 9 and older | Brazil and Germany are rated for all ages; no content descriptors. Target audience is separate from a download age gate. Saved groups are 9-12, 13-15, 16-17 and 18+; the app has no age gate. |
| Category | Education; saved | Describe the training/simulation purpose consistently. A category does not confer medical-device approval. |

Google excludes processing confined to the device from its collection definition. Sharing has its own rules, including some transfers between apps; this app saves user-requested media through Android's shared-media service and does not contain an automated transfer to an advertising/analytics recipient. Independently configured gallery/cloud backup behavior is disclosed in the policy. [Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)

Every app needs an in-app privacy policy or link plus an active public policy URL, including apps that collect nothing. The policy needs an app/developer identity, contact mechanism, data handling and deletion/retention details. [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en)

The clarified use is training and simulation only. The description states that the app is not a medical device, does not diagnose/treat/cure/prevent conditions, and is not for patient care; it directs users to a qualified health professional for medical advice. Keep the saved Health apps declaration consistent with that educational context and keep external hardware requirements clear. Do not imply clinical approval, effectiveness, or authorization in any country. The Medical reference and education declaration is included in the submitted changes and awaits Google review. [Health policy](https://support.google.com/googleplay/android-developer/answer/16679511?hl=en), [Health declaration](https://support.google.com/googleplay/android-developer/answer/14738291?hl=en)

## Reviewer access instructions

No account, password, subscription, or in-app location restriction is required. Use a mannequin or simulation target; this app is for training only. Use an ARM Android phone running Android 7.0 or newer with USB host/OTG support. Connect a UVC USB camera through a suitable adapter, grant Camera permission, and approve Android's USB-access prompt. Wait for "Camera ready." Capture saves a JPEG. Record starts a silent video; Stop finalizes it to the gallery. On Android 7-9, grant Storage permission when saving. Settings adjusts image framing and color. Without a compatible connected USB camera, the app displays a connection prompt and capture/recording is unavailable. This is expected hardware-dependent behavior.

Document the exact known-working camera and adapter if the owner supplies them; do not infer the model from a screenshot.

## Assets

- High-resolution app icon: 512 x 512 PNG, up to 1,024 KB.
- Feature graphic: 1,024 x 500 JPEG or PNG without alpha.
- Two approved final screenshots are in graphics/phone-screenshots: 1964 x 982 and 2072 x 1036 RGB PNG. The owner authorized scripted neutral borders; every original pixel was preserved. verification.json records hashes and crop equality checks. The original files remain untouched in the task folder.
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

## Release checks and validation limits

1. Use the confirmed free, worldwide, unrestricted-access launch choices and validate description lengths. The owner-completed audience and rating answers were reviewed and submitted. Support email, project identity and training-only intended use are confirmed. Do not treat lack of clinical approval as a publication blocker for this declared training-only app.
2. Verify final release package, version, permissions, network-free dependency graph and branding from the artifact, not just sources.
3. Recheck both ABI contents and 16 KB alignment after the publishing toolchain changes.
4. Install the signed/minified candidate on the known-working phone/camera and confirm preview, capture, video playback and gallery saves. Preserve signing continuity and existing media.
5. Publish and open the privacy URL without authentication; confirm its text matches the offline policy.
6. The approved assets are uploaded and verified. The owner completed the IARC/content-rating, target-audience and Data safety forms, and their saved answers were reviewed and submitted. Verify all required onboarding/testing tasks from the live Console.
7. The exact bundle, full-production-rollout request, release notes, listing and declarations are submitted. Check Console for quick-check failures, review requests, approval and actual public availability before claiming publication.
