# Google Play release helper

`scripts/google-play-release.ps1` stages the reviewed AirAngelVL listing, images, and bundle in an Android Publisher edit. It supports the `com.airangelvl` package and the local `codex-publisher` profile. It reuses `google-play.ps1` for DPAPI authentication without changing that helper or printing credentials. Configure another machine through `GOOGLE_PLAY.md`; never copy private keys into the repository.

The helper intentionally handles a first production **draft**, not a production rollout. It cannot create the initial app entry, choose the audience or countries, set the price, complete policy declarations, configure Play App Signing, or submit the draft for production review. Complete those supported Console flows separately using the user's current authorization. API access alone is not release authorization.

## Commands and boundaries

| Command | Effect |
| --- | --- |
| `SelfTest` | Offline checks for endpoint restrictions, media replacement, production status, changed files, initial edit-state write/cleanup failures, and sanitized errors. Does not read credentials or call Google. |
| `CheckFiles` | Offline verification of explicit AAB hash, text limits, image sizes, and distinct screenshots. Returns the planned files and hashes. It does not replace bundletool, signing, or device verification. |
| `ReadSnapshot -ConfirmNoOtherEdit` | Creates a temporary edit, reads bundles/listings/tracks/images, then deletes it. Does not commit. |
| `Stage -NewEdit -ConfirmNoOtherEdit` | Creates and persists an edit, uploads missing bundle/media, updates en-US text and one production release with status `draft`, then verifies the remote content. Does not commit. |
| `Stage -EditId ID` | Resumes the exact locally saved plan. Requires the same original input arguments and unchanged files. Already matching bundle/images are reused. |
| `Validate -EditId ID` | Verifies local files, exact remote draft/bundle/listing/images/notes, and calls Google's edit validation. Does not commit. |
| `RecoverCommit -EditId ID` | Resolves only a saved `CommitUncertain` attempt after a successful GET of the same active unexpired edit, exact remote candidate checks, unchanged local files, and Google validation. Saves `Validated` locally. Does not create an edit, change remote content, or commit. |
| `CommitDraft -EditId ID` | Repeats validation, commits with `changesNotSentForReview=true` and `changesInReviewBehavior=ERROR_IF_IN_REVIEW`, and retains release status `draft`. Does not start a production rollout. |
| `CommitDraft -EditId ID -AllowAutomaticReview` | Explicitly omits `changesNotSentForReview` for apps where Google rejects that parameter. Retains `ERROR_IF_IN_REVIEW` and the exact production draft. Store listing and other eligible changes may enter automatic review; use only with current authorization for that review/submission. |

Do not run `VerifyAccess`, `ListTracks`, `ReadSnapshot`, another publisher, or Console changes alongside an open release edit. Google allows one edit per user per app and other activity may invalidate the edit. The local mutex coordinates instances of this release helper; it cannot coordinate another machine, the Console, or `google-play.ps1`.

`-ConfirmNoOtherEdit` records that the caller checked for conflicting work; it is not proof that another remote edit does not exist. `ReadSnapshot` refuses to run while local edit metadata exists. Preserve any active edit and use its saved ID rather than creating a new one as an access test.

The helper uses only allowlisted HTTPS paths at `androidpublisher.googleapis.com` and disables redirects. Auth uses the existing helper's fixed Google OAuth endpoint. Uploads hash and transmit the same locked file stream. Error output contains at most a filtered 500-character Google error message; request headers, keys, and tokens are never logged.

Existing icon, feature graphic, or screenshots must match the candidate or its already uploaded prefix. Otherwise `Stage` fails before changing listing/media. After reviewing the remote snapshot, use `-ReplaceExistingMedia` only when replacing that media is authorized. This deletes and replaces the corresponding image type in the uncommitted edit; it does not delete local originals. This helper refuses to replace a different production release or to change a completed/in-progress release. For initial staging, it also accepts the pristine placeholder created by Console when Create release is clicked: exactly one release containing only `status: "draft"`. Any additional name, notes, empty/different version list, rollout setting, or unknown field prevents that placeholder exception. `Validate` and `CommitDraft` still require the complete exact candidate; an empty draft cannot pass those checks.

## Current candidate example

Run from the populated repository root. Review `PUBLISHING.md`, `store/PLAY_REVIEW.md`, the current screenshots, signing evidence, package, version, and release settings first. Do not reuse a historical hash for a newly built candidate.

```powershell
Set-Location -LiteralPath 'C:\Coding\Coding Projects\AAVL'
.\scripts\google-play-release.ps1 -Command SelfTest

$candidate = @{
    Bundle = '.\artifacts\AirAngelVL-play-release-75f3c4a65d76.aab'
    BundleSha256 = '75f3c4a65d762b531fd67d16e8ec6bf6b3dc8cf059116bfc4973e0f917a5ba40'
    VersionCode = 3
    ReleaseName = '1.0.0'
    Screenshots = @(
        '.\store\graphics\phone-screenshots\01-training-preview.png'
        '.\store\graphics\phone-screenshots\02-training-preview.png'
    )
}
.\scripts\google-play-release.ps1 -Command CheckFiles @candidate
```

Default text files are `store/en-US/title.txt`, `short-description.txt`, `full-description.txt`, and `release-notes.txt`. Default graphics are `store/graphics/icon-512.png` and `feature-graphic-1024x500.png`. The helper checks title/short/full/release-note limits of 30/80/4000/500 characters, icon dimensions of 512 x 512, feature graphic dimensions of 1024 x 500, and exactly two distinct 320-3840 pixel screenshots no wider/taller than 2:1. It reads but does not alter images.

After first-app setup is complete and no other edit is active:

```powershell
.\scripts\google-play-release.ps1 -Command ReadSnapshot -ConfirmNoOtherEdit
$staged = .\scripts\google-play-release.ps1 -Command Stage -NewEdit -ConfirmNoOtherEdit @candidate
$staged

# Keep the actual returned ID. Never type a guessed or historical edit ID.
.\scripts\google-play-release.ps1 -Command Validate -EditId $staged.editId

# Only when saving this exact draft is authorized:
.\scripts\google-play-release.ps1 -Command CommitDraft -EditId $staged.editId
```

There is no `Submit` command. Verify the saved draft and Publishing overview in Console before the separately authorized submission. A successful edit validation or draft commit is not an approval, public availability, policy acceptance, or guarantee that the app will be approved. Google's `changesNotSentForReview` documentation describes keeping changes for later Console review after rejection; the retained `draft` track status is the release-level boundary. Inspect Console for the actual listing/review state rather than inferring it solely from that query parameter.

## Persistence, interruptions, and recovery

Only nonsensitive edit metadata, listing text, reviewed file paths/hashes, and status are saved under:

```text
%LOCALAPPDATA%\Codex\google-play\release-edits\codex-publisher-com.airangelvl.json
```

Use PowerShell 7 on this host, and keep the same PowerShell major version for staging and resuming an edit: JSON serialization differences can change the stored plan fingerprint. A mismatch blocks the operation without committing.

No release state belongs in Git. The state includes the server edit ID and expiry, exact candidate plan, and phase. Resumption requires `-EditId` matching that file, identical plan inputs, unchanged files, and a live unexpired edit. In another session, read the saved JSON as nonsensitive metadata and resume `Stage -EditId <actual-id> @candidate` if staging was interrupted. No new edit is created automatically. A partial image upload can resume if remote image hashes form the original ordered prefix.

Staging failures leave the edit uncommitted and its state available for inspection. If the network fails during a request, its outcome can be uncertain: inspect/resume the same edit instead of blindly repeating uploads or creating another edit. If a file changed, reconstruct the exact original candidate or explicitly resolve the old edit before preparing a new one.

Before a commit request the helper records its review mode and saves `CommitUncertain`. After success it saves `CommittedDraft`. Both phases block automatic retry. A definite HTTP rejection also leaves the uncertainty marker until the server's actual edit state is verified. Never manually change the phase to bypass this protection.

If the same edit remains active after a rejected or interrupted commit, use the explicit `RecoverCommit -EditId <saved-id>` command. It checks the saved profile/package/plan fingerprint, unchanged files, and local expiry, then successfully GETs that same edit and requires its server expiry to remain valid. It verifies the exact remote bundle hash/version, production draft, listing, ordered image hashes, release name and notes, then calls Google validation and checks local files again. Only after every check succeeds does it save the local phase as `Validated` with a recovery timestamp. A missing, committed, expired, different, or changed edit cannot be recovered. Recovery never creates a new edit, modifies remote content, or commits; a separate explicit commit is required.

Google may reject the default hold query with HTTP 400 stating that changes are sent for review automatically and `changesNotSentForReview` must not be set. There is no automatic fallback. After successful recovery and only when automatic review is authorized, use `CommitDraft -EditId <saved-id> -AllowAutomaticReview`. This omits that single parameter while retaining `changesInReviewBehavior=ERROR_IF_IN_REVIEW` and the exact production `draft` status. The store listing and other eligible changes may enter automatic review. It does not promote the draft release to a rollout.

```powershell
# Use the actual saved ID; inspect the rejection and keep the same edit.
.\scripts\google-play-release.ps1 -Command RecoverCommit -EditId $staged.editId
# Separate action, only when automatic review/submission is authorized:
.\scripts\google-play-release.ps1 -Command CommitDraft -EditId $staged.editId -AllowAutomaticReview
```

If the edit cannot be recovered, establish the remote result in Console before further work. Preserve a copy of resolved state outside Git before archiving it to a distinct filename; only then create a later edit. Expired or invalidated edit metadata likewise needs deliberate resolution and archival. The helper never silently deletes active or historical edit metadata.

If `ReadSnapshot` cannot delete its temporary edit, it warns with the edit ID. Resolve that edit before publishing elsewhere. No commit is attempted during a snapshot.

## Verification record

September 6, 2026: the offline self-test passed 33 checks on PowerShell 7 and Windows PowerShell 5.1 with no network requests and no credential reads. `CheckFiles` accepted the reviewed version-code-3 AAB, listing files, icon, feature graphic, and the two updated padded screenshots. PowerShell parsing passed. The initial helper implementation used offline verification only. During subsequent publishing, Stage and Validate succeeded for the exact version-code-3 candidate and assets. Google explicitly rejected the first default commit with HTTP 400 because the review-hold parameter was not supported for this app. Subsequent live `RecoverCommit` verified the same active edit and exact candidate, and `CommitDraft -AllowAutomaticReview` succeeded while preserving local `CommittedDraft` state and production version 3 as a draft. The initial draft commit left release 1.0.0 / code 3 available in Console for 177 countries, with setup temporarily at 8/11 and changes not yet submitted. The owner then completed IARC content rating, target audience, and Data safety; the saved answers were verified, and the exact release preview was confirmed and saved. At approximately 2026-09-06 23:14 UTC, 11 changes were submitted through Console. The latest verified state is Changes in review, with quick checks running and managed publishing OFF. The app is not yet approved or live. These are the observed outcomes for this submission, not a guarantee about future automatic-review behavior.

## Official references

- [Edit workflow and concurrency](https://developers.google.com/android-publisher/edits)
- [Upload a bundle](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.bundles/upload) and [bundle hash fields](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.bundles)
- [Update a localized listing](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.listings/update)
- [Upload images](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.images/upload) and [image hash fields](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.images)
- [Track release statuses](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.tracks)
- [Validate an edit](https://developers.google.com/android-publisher/api-ref/rest/v3/edits/validate)
- [Commit an edit and review behavior](https://developers.google.com/android-publisher/api-ref/rest/v3/edits/commit)
