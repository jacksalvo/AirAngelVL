# Google Play API access

The official Android Publisher API supports app-bundle upload, listings, testing tracks, and production releases. Local Codex sessions use `scripts/google-play.ps1`; Chrome is needed for initial account setup or account-owner actions, not routine API authentication.

## Local configuration

Check the helper's `Status` command for local credential availability; this public document never contains a key or token. Account identifiers and verification results are recorded in `%LOCALAPPDATA%\Codex\google-play\README.md` outside Git. The owner's shared profile is `codex-publisher`; forks should configure their own profile.

The helper stores service-account credentials using Windows CurrentUser DPAPI under `%LOCALAPPDATA%\Codex\credentials`. Account configuration also remains outside the repository. The encrypted credential is tied to the importing Windows account; copying the public repository does not grant access.

Never paste service-account JSON or access tokens into chat, commit them, or upload them as build artifacts. Use a downloaded JSON file only for the import, verify authentication, then remove the task-created plaintext download. Keep signing/upload keys separate from the API credential.

```powershell
# No network request; reports whether the local profile exists.
.\scripts\google-play.ps1 -Command Status -Profile codex-publisher

# Requests a short-lived token and prints only the authentication result.
.\scripts\google-play.ps1 -Command TestAuthentication -Profile codex-publisher

# Requires an existing Play app with its first upload completed.
.\scripts\google-play.ps1 -Command VerifyAccess -Profile codex-publisher -PackageName com.airangelvl
.\scripts\google-play.ps1 -Command ListTracks -Profile codex-publisher -PackageName com.airangelvl

# Offline crypto checks; no Google request and no credentials required.
.\scripts\google-play.ps1 -Command SelfTest
```

For initial setup, use `-Command ImportCredential -Profile <profile> -ServiceAccountJson <downloaded-file> -PackageName <release-package> -DeveloperId <console-id>`. Import refuses to overwrite an existing profile unless `-ReplaceCredential` is explicitly supplied. The import does not delete its source file. `Status` is not proof of authentication, and `TestAuthentication` is not proof of Play app permissions; use `VerifyAccess` on an existing package to check both.

The helper runs in Windows PowerShell 5.1 and PowerShell 7. Prefer PowerShell 7 on the configured host. If Windows PowerShell's execution policy blocks the trusted local script, use `powershell.exe -NoProfile -ExecutionPolicy Bypass -File <helper-path> ...` for that child process; do not change global execution policy.

It performs OAuth JWT signing locally, restricts requests to the fixed Google token and Publisher endpoints, and never returns access tokens from its public commands. Import stages and reads back both protected files before activation, serializes imports, and rolls back handled activation failures. Do not rotate credentials during a publishing job. A future publishing implementation should reuse the protected credential handling, keep authentication in memory, and add only the operations required by the authorized release.

## Release boundaries

The helper provides authentication and access checks. It does not publish app updates. An access check creates a temporary edit, reads tracks, then deletes the edit without committing. Do not run this check concurrently with a publishing job using the same service account: creating another edit can invalidate its existing edit.

Before implementing a release operation, verify its package name, signed AAB, version code, track, release notes and rollout settings against the user's request. Keep credentials in memory only during requests. Record the outcome without tokens or private account data.

For AirAngelVL, preserve `minSdk 24` while completing the separate target-SDK migration and release-signing setup required for Play. Do not submit debug/validation builds or claim phone acceptance from build/test results alone. See `VALIDATION.md` for measured coverage.

## Official references

- [API setup and service accounts](https://developers.google.com/android-publisher/getting_started)
- [Edits workflow and initial-app limitations](https://developers.google.com/android-publisher/edits)
- [Tracks and staged rollouts](https://developers.google.com/android-publisher/tracks)
- [Current Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878)
