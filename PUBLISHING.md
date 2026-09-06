# AirAngel VL 1.0.0 publishing candidate

Prepared September 6, 2026. Nothing has been uploaded or submitted to Google Play.

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
- `store/PLAY_REVIEW.md`: proposed Ads, Data safety, app access and health-training declarations plus reviewer hardware instructions. No declarations were submitted.
- The nonprofit's thousands-of-clinicians figure is attributed to the nonprofit based on the owner's statement and refers to its wider hardware project. The reviewed website did not independently corroborate that count.

## Before submission

1. Recheck the minified candidate on the known-working camera and take two updated screenshots. The owner supplied updated screenshots with AirAngel VL branding and Camera ready on September 6. Approved neutral padding preserves every original pixel and meets Play's 2:1 limit; final PNGs are in store/graphics/phone-screenshots. Originals remain untouched in the task folder.
2. The privacy policy is live at https://jacksalvo.github.io/AirAngelVL/privacy-policy.html. GitHub Pages uses main /docs with HTTPS enforced. Deployment 34055714456 succeeded for commit 2660204; unauthenticated HTTP 200 and exact HTML content match were verified on September 6, 2026.
3. Owner confirmed free, worldwide, no age limits and asked to proceed. Complete the live audience/content-rating and production-release forms accordingly. Complete live content rating and health education/training declarations with the owner; the intended use is not patient care or a regulated clinical claim.
4. Create the first app entry, configure Play App Signing and record its delivery certificate, then upload the reviewed signed bundle and assets. The local upload key is distinct from a Google-managed app signing key; avoid treating a locally sideloaded APK as interchangeable with the future Play-signed installation.
5. Publish corresponding source/license notices and submit only after the exact store material, artifact and release settings have been reviewed. Recheck any new requirements shown by the live Console.

## Submission continuation (September 6, 2026)

The release source and approved store assets are public in commit 2660204. No AirAngel app has been created in Play Console and no API edit/upload has started. The Create app form contains AirAngel VL, com.airangelvl (available), English (United States), App and Free.

The owner approved screenshot padding. A separate question remains unanswered: approval of Google's Developer Program Policies and US export declarations, together with disabling the optional installer check that otherwise prompts sideloaded users to get the app from Play. Leave the declaration checkboxes untouched until that answer arrives. Do not infer approval from the screenshot answer.

The API staging workflow is documented in GOOGLE_PLAY_RELEASE.md. Use the exact reviewed bundle hash and approved PNG files. Do not run another edits-based access check or alter the app in Console while an API edit is open. This helper stages and commits a draft only; actual review submission remains a separate action. Check current live Console requirements and existing review/release state before proceeding.
