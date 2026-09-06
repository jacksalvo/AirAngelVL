# Release signing

The first public release uses package `com.airangelvl` and a new upload key. Use the local signing helper for future release builds; do not generate a fresh key for each release. Debug builds keep their separate application IDs and debug certificates.

```powershell
.\scripts\release-signing.ps1 -Command Status
.\scripts\release-signing.ps1 -Command VerifyKey
.\scripts\release-signing.ps1 -Command Build -Verify
```

`Build -Verify` also runs the wrapper's complete lint and unit-test checks. `Build` invokes the normal build wrapper for `:app:bundleUsbOnlyRelease` and `:app:assembleUsbOnlyRelease`. It supplies signing values only in the current process and its build children, then restores the caller's environment. It does not upload an artifact or publish a release. Continue with the artifact, native alignment, signing and device checks in `BUILDING.md` and the release checklist before upload.

## Local storage

The helper stores the key under `%LOCALAPPDATA%\Codex\google-play\airangelvl-signing`, outside Git:

- `upload-key.p12`: password-protected PKCS12 keystore, RSA 3072, alias `airangelvl-upload`, certificate validity 10,000 days.
- `password.dpapi`: a cryptographically random 48-byte password protected with Windows CurrentUser DPAPI.
- `upload-certificate.pem`: the public certificate, suitable for upload-certificate registration if required.
- `metadata.json`: non-secret certificate fingerprint and creation metadata.

Directory and file ACLs allow only the creating Windows account and SYSTEM. `VerifyKey` verifies those permissions, decrypts the password, opens the keystore and compares its certificate with the recorded SHA-256 fingerprint. It never returns passwords or private key contents. Run it as the Windows account that created the setup. Do not print environment variables, use Gradle debug logging, commit signing material, or copy raw credentials into chat.

`CreateKey` is for a first setup only, and refuses if the signing directory already exists. A failed or partial setup is preserved for diagnosis rather than replaced. A public-repository fork must create its own signing identity. The production owner must preserve this existing identity; do not run `CreateKey` as a repair for an inaccessible credential.

## Unsigned builds and other build environments

Without signing environment values, the normal build wrapper still produces unsigned release artifacts. Gradle rejects partial signing configuration. All four values must be supplied together by a secure credential mechanism:

- `AAVL_UPLOAD_STORE_FILE`
- `AAVL_UPLOAD_STORE_PASSWORD`
- `AAVL_UPLOAD_KEY_ALIAS`
- `AAVL_UPLOAD_KEY_PASSWORD`

Use the helper on this Windows account instead of setting persistent user or system environment variables. It disables Gradle configuration caching through the existing build wrapper, keeping decrypted signing values out of a configuration-cache snapshot.

## Play App Signing and recovery

The local upload key authenticates an uploaded bundle. With Play App Signing, Google signs delivered APKs with the app signing key. Record both public certificate fingerprints when Play App Signing is configured. A locally built APK signed with the upload key may have a different signing identity from the APK delivered by Play, so do not present it as an interchangeable update to a Play installation. See [Android's signing documentation](https://developer.android.com/studio/publish/app-signing).

The current DPAPI credential is tied to this Windows account and computer. Copying `password.dpapi` to another machine is not a portable backup. Before migrating or retiring this PC, arrange a secure, independently recoverable keystore/password backup or an intentional upload-key reset. The helper does not export plaintext passwords or create a portable backup automatically. If an upload key is lost after Play App Signing enrollment, Google provides an upload-key reset process that preserves the Play app signing identity; see the [official recovery guidance](https://developer.android.com/studio/publish/app-signing#reset_upload_key).
