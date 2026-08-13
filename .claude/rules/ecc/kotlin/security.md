# Kotlin / Android Security

Applies to `**/*.kt`. Subordinate to
`musicviz-project/musicviz/docs/v2/MASTER_PLAN.md`.

Scoped to what this app actually is: an **offline, local-first** Android music
player and visualizer. It ships **no `INTERNET` permission**, no network client,
no WebView, no accounts, no Room/SQLDelight, and no auth tokens. Guidance about
TLS pinning, HTTP timeouts, WebView hardening, SQL injection, or token refresh
does not apply here — and adding a network dependency is a user-decision
boundary under the master plan, not a routine change.

## Secrets

- Never hardcode API keys, tokens, credentials, or keystore passwords.
- Local development secrets go in git-ignored `local.properties`; release values
  come from CI secrets via `BuildConfig` fields.
- Never commit a keystore.

## Input validation at the boundary

The real untrusted inputs are user-supplied files and system-supplied URIs:
imported `.milk` presets, user shaders, SAF document trees, `.lrc` sidecars,
saved takes, and the analysis cache.

- Sanitize any user-derived filename before it reaches the filesystem — the
  repo already has `SafeFileName` and a test for it; use it rather than
  hand-rolling path handling.
- Parse, don't validate: convert raw input into typed domain objects once, at
  the boundary. Durable formats carry a schema version and defined
  corrupt/unknown-field behavior per the master plan.
- A corrupt or truncated file must degrade to a reported, recoverable state —
  never a crash, and never a silent success.

## Runtime permissions and capture

- Microphone and playback capture start only from explicit user action with the
  platform permission or projection token in hand.
- No captured PCM is persisted unless the user explicitly started a supported
  recording or export, and none is ever transmitted.
- Permission or projection revocation produces a typed state and releases
  resources.

## Data protection

- User-authored data (presets, takes, playlists, history) is written atomically
  with a last-known-good backup. A failed secondary write (for example a SAF
  mirror) is reported separately, never as overall success.
- Clear sensitive data from memory when no longer needed.

## ProGuard / R8

- Keep rules for every serialized model and reflection-reached type.
- Test release builds — obfuscation can break serialization silently, and the
  release path is where preset/take migration bugs surface.

## Native boundary

- The projectM JNI boundary stays dynamically linked, with its LGPL notices
  intact. Verify shipped `.so` provenance against `SHA256SUMS` before release.
