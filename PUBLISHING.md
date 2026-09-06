# AirAngel VL 1.0.0 publishing record

Updated September 6, 2026. Version 3 / 1.0.0 and 11 production/listing/declaration changes were submitted to Google Play. Console confirms Changes in review, with quick checks running (up to 15 minutes). The submission requests a full production rollout. Managed publishing is OFF. Google has not approved the release, no rollout is verified as live, and the app is not yet published.

## Candidate and verified checks

- Package `com.airangelvl`, version name `1.0.0`, version code `3`; minimum API 24, compile/target API 36; ARM32 + ARM64.
- Signed Play bundle: `artifacts/AirAngelVL-play-release-75f3c4a65d76.aab`.
- Bundle SHA-256: `75f3c4a65d762b531fd67d16e8ec6bf6b3dc8cf059116bfc4973e0f917a5ba40`.
- Signed/minified phone APK: `artifacts/AirAngelVL-1.0.0-release-universal.apk`.
- APK SHA-256: `a15009da2dec98ea56022248265a3b3ad6e1074c1f57cbe0a7e32082db0852f7`.
- Upload certificate SHA-256: `f0510dd557bab1398a8f4d48869f93beaba3cc608c58216d99ca18984ab8ac8e`. Reuse the existing private key through `scripts/release-signing.ps1`; see RELEASE_SIGNING.md.
- Signed debug/release build tasks and Play AAB build pass. All 65 existing JVM/Robolectric tests pass. Dependency-wide lint: zero errors, 75 warnings. The tests model APIs 24-34; passing them is not a target-36 device test.
- Bundletool validates the exact AAB, production manifest, native inventory, 16 KB bundle setting and a generated universal APK. Both ARM ABIs have the expected five native libraries each. Their ELF and APK ZIP alignment pass. Direct debug and signed release APK alignment also pass.
- APK and AAB signatures verify against the intended upload certificate. The key is self-signed, as expected for Android app signing; this is not a public CA certificate or a security audit.
- Final APK label is AirAngel VL, with no testing label. CAMERA and legacy storage permissions are present; no Internet, microphone, advertising ID, location or contacts access. Legacy WRITE_EXTERNAL_STORAGE implies read access on those Android versions; the app does not enumerate or upload other media.
- Native UVCCamera and JNI callback interface names remain unchanged by R8. This static check does not replace exercising UVC callbacks on the minified app.
- The offline privacy asset matches store/privacy-policy.txt, and the public HTML contains the same policy text.

Evidence stays outside Git under `artifacts`: publishing-signed-verify.log, publishing-lint-refresh.log, publishing-bundle-verification.log, publishing-debug-native.log, publishing-release-native.log, publishing-candidate.json and bundle-verification/20260906-150458-8a0b8053.

## Phone acceptance

The owner reported that the earlier 0.2.0 debug build connected to the camera and captured images/video on the Pixel 10 Pro XL. Camera model/adapter and sustained-use measurements were not supplied; do not infer them from the simulation screenshots.

The signed/minified 1.0.0 APK was installed wirelessly on the same Pixel and launched successfully to Android's Camera permission prompt. This is a separate production package from the earlier debug app. Existing media and the debug app were preserved. Preview, photo capture, video playback/gallery saves, background finalization and disconnect behavior still need a quick recheck on this exact candidate. The target SDK and shrinker changed since the owner's earlier test.

## Store material

- `store/en-US/`: title, short description, full description and release notes. Copy describes training/simulation only, the AirAngel nonprofit, local processing, no ads/tracking/data scraping, external USB requirements, other-camera compatibility limits and the stated USA medical-use restriction.
- `store/privacy-policy.txt` and `store/privacy-policy.html`: matching offline and publishable policy. Public support: coppercanyonanesthesia@gmail.com. Gallery/cloud-backup behavior from other apps is disclosed.
- `store/graphics/`: 512 x 512 icon and 1024 x 500 feature graphic, RGB PNG without alpha; editable SVG sources reuse the app's branding.
- `store/PLAY_REVIEW.md`: source-audited declaration answers, current Console save state and reviewer hardware instructions. The owner completed the remaining rating, audience and Data safety forms. Their saved answers were checked and included in the submission; Google review remains pending.
- The nonprofit's thousands-of-clinicians figure is attributed to the nonprofit based on the owner's statement and refers to its wider hardware project. The reviewed website did not independently corroborate that count.

## Release preparation and remaining validation

1. Recheck the minified candidate on the known-working camera. The owner supplied updated screenshots with AirAngel VL branding and Camera ready on September 6. Approved neutral padding preserves every original pixel and meets Play's 2:1 limit; final PNGs are in store/graphics/phone-screenshots. Originals remain untouched in the task folder.
2. The privacy policy is live at https://jacksalvo.github.io/AirAngelVL/privacy-policy.html. GitHub Pages uses main /docs with HTTPS enforced. Deployment 34055714456 succeeded for commit 2660204; unauthenticated HTTP 200 and exact HTML content match were verified on September 6, 2026.
3. Owner confirmed free, worldwide, no age-based access restriction and asked to proceed. The owner completed the remaining Console forms, including IARC. Target audience is saved as ages 9 and older, distinct from a download age gate. The saved audience and final Data safety answers were reviewed before submission; the app does not add an age gate. The health education/training declaration is saved; the intended use is not patient care or a regulated clinical claim.
4. The app entry is created, Play App Signing is active, and the reviewed signed bundle and assets are uploaded. The upload certificate matches the verified local key. Record the separate Google-managed app-signing certificate before relying on delivery-signature continuity; do not treat a locally sideloaded APK as interchangeable with a Play-signed installation.
5. Corresponding source/license notices and approved store assets are public. The reviewed release and store material are submitted. Check the live Console for quick-check failures, review requests, approval and actual availability before claiming the app is published.

## Submission continuation (September 6, 2026)

The release source, approved store assets and publishing helper are public on main through commit 8211f07 (the production/store/docs content was first published in 2660204). AirAngel VL, package com.airangelvl, was created in Play Console on September 6, 2026; Console app ID 4974624117466772766. It uses English (United States), App, Free and the Education category. Availability includes all 177 selections: 176 countries/regions plus Rest of World.

The owner explicitly approved Google's Developer Program Policies and US export declarations and disabling the optional installer check. Those creation steps are complete, and automatic installer protection is OFF. The owner's confirmed launch choices remain free, worldwide and no age-based access restriction. Target audience and content rating must describe the app's actual design and content; do not enable Restrict minor access.

The Console has saved the public privacy-policy URL, unrestricted sign-in/app access, no ads, no advertising ID and no government affiliation. The Health apps declaration is saved as Medical reference and education; the Console currently reports no regional requirements. This is a report of the current Console workflow, not a medical-device approval or legal determination. The owner reported completing the remaining forms. Console shows IARC completed September 6 at 7:06 PM, with category All Other, ESRB Everyone, PEGI 3, all-ages ratings for Brazil and Germany, and no content descriptors. The saved audience has ages 9-12, 13-15, 16-17 and 18+ checked, with under-9 groups unchecked; these owner-selected answers were left unchanged. The app does not implement an age gate. Final Data safety was read back and confirms no data collected or shared with third parties, the correct privacy URL, and the owner-selected Families Policy commitment set to Yes. The earlier unanswered IARC-terms blocker was resolved by the owner's own completion.

The initial manual upload of the exact version-3 bundle succeeded. Play App Signing is active, and the uploaded certificate matches SHA-256 f0510dd557bab1398a8f4d48869f93beaba3cc608c58216d99ca18984ab8ac8e. The API workflow then completed Stage, Validate, RecoverCommit and CommitDraft using the same edit. It verified the exact en-US listing text, icon, feature graphic, both approved screenshots, release notes and bundle SHA-256 75f3c4a65d762b531fd67d16e8ec6bf6b3dc8cf059116bfc4973e0f917a5ba40.

The first draft-commit request returned HTTP 400 because this app did not support changesNotSentForReview. Recovery retained the same edit and ERROR_IF_IN_REVIEW and committed successfully with that unsupported parameter omitted. That API operation committed a draft. After the owner completed the remaining Console forms, the version-3 / 1.0.0 release preview was confirmed and saved. The reviewed 11 changes included Start full rollout, all 176 countries/regions plus Rest of World, the complete en-US listing and content declarations. Submit 11 changes and the Send changes for review confirmation completed successfully. Publishing overview now shows Changes in review and a Remove changes control, with quick checks running for up to 15 minutes. Managed publishing is OFF. This is a successful submission queued through quick checks, not Google approval or verified public availability.

The Console retained a non-blocking native debug-symbol warning. Matching unstripped symbols exist locally for all eight source-built UVC libraries across the two ARM ABIs under native-uvc/build/native-obj/local; their build IDs and stripped bytes match the exact submitted bundle. No symbol ZIP was prepared or embedded in the AAB, and matching DataStore symbols have not been established. This is a future crash-diagnostics task: the submitted candidate is unchanged, the warning did not prevent submission, and it does not change the physical-device validation limits above.

The API staging workflow and recovery behavior are documented in GOOGLE_PLAY_RELEASE.md. Detailed API edit identifiers and responses remain in the local publishing journal outside Git. Use the exact reviewed bundle hash and approved PNG files. Do not run another edits-based access check or alter the app in Console while an API edit is open. Staging and committing a draft do not submit an app for review; check current live Console requirements and review/release state before continuing.
