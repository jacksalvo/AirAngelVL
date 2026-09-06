# AirAngelVL agent guidance

Read `CLAUDE.md`, `BUILDING.md`, and `VALIDATION.md` for the architecture, toolchain, behavior to preserve, and actual test coverage. Preserve existing media, both ARM ABIs, native patches, and 16 KB alignment. Use `scripts/build.ps1` on Windows.

## Google Play access

Use `scripts/google-play.ps1` and `GOOGLE_PLAY.md` for reusable API access. Start with `Status`; do not search Chrome profiles, dump environment variables, print credentials, or ask the owner to paste keys into chat.

Credentials and account-specific configuration belong under `%LOCALAPPDATA%\Codex`, outside this public repository. The credential is protected with Windows CurrentUser DPAPI and is usable only by the Windows account that imported it. A fork or another computer must configure its own access. Never commit service-account JSON, private keys, tokens, signing material, or account-specific API responses.

API access does not itself authorize publishing, changing prices, replying to reviews, or changing policy declarations. Follow the user's current request and any authorization already given in the conversation. Before a release, prepare and verify the exact signed artifact, package, version code, target track, release notes, and rollout configuration. Do not repeatedly ask for approval already given for that exact action.

The helper's access check creates and deletes an uncommitted edit. Run it only when no publishing job is active for that service account; a new edit can invalidate that account's existing edit. Never commit an edit merely to test access.

AirAngelVL's release package is `com.airangelvl`; debug and validation packages are separate. API setup alone does not create a Play listing, configure signing, or establish Play release readiness. Check the live console and current target-SDK requirements before submission.

## Publishing candidate

The app is for AirAngel training and simulation, not patient care. Preserve the support address coppercanyonanesthesia@gmail.com (distinct from Play owner jacksalvo@gmail.com), offline privacy policy, no-network permission contract, and local-only media handling. Keep app/src/main/assets/privacy-policy.txt identical to store/privacy-policy.txt and update the public HTML copy too.

The production version targets API 36 and retains minimum API 24. Reuse the existing upload key with scripts/release-signing.ps1; never regenerate it for an update. Read RELEASE_SIGNING.md and store/PLAY_REVIEW.md before publishing. Keep private signing storage outside Git. Debug/validation package IDs remain separate, but visible branding is AirAngel VL in all variants. Owner-reported success with the earlier debug build does not replace testing the minified publishing candidate.