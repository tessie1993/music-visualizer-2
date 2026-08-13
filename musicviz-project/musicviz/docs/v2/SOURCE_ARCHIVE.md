# MusicViz 2.0 Source Archive

Provenance for any external code, shader or algorithm that enters the tree.
**Update this file before the code lands, not after.**

Licence status for every project named here lives in `LICENSE_LEDGER.md`;
this file records *what was taken and how it was changed*.

## Entries

| Date | Source project | Pinned revision | Licence | What was taken | Adapted files | Modifications | Notice action |
|---|---|---|---|---|---|---|---|
| — | — | — | — | — | — | — | — |

*(Empty at Phase 0.1. No external source has entered the 2.0 tree.)*

## Required before adding a row

1. Fetch the repository and read its `LICENSE` file — not its README, and not a
   remembered licence. Record the exact URL and the revision/tag pinned.
2. Confirm the licence is compatible with shipping this app (see
   `LICENSE_LEDGER.md`; PolyForm Strict, GPL, AGPL and unclear terms are
   prohibited).
3. Decide the use category honestly. Reimplementing a published algorithm from
   its paper is `math` and needs no notice. Porting someone's shader is
   `derived` and does.
4. Record what changed. "Adapted to GLES 3.0" is not a modification note;
   name the substantive differences.
5. Add the notice to `THIRD_PARTY_NOTICES` **and** the in-app asset in the same
   commit — `checkThirdPartyNotices` fails the build if they drift.

## Existing native provenance

`app/src/main/jniLibs/arm64-v8a/SHA256SUMS` is the provenance record for the
projectM binaries. Its checksums verify against the committed blobs, but its
header states the blobs predate provenance tracking and that `tools/pm_jni.c`
has been hardened since. **The shipped binary does not correspond to current JNI
source.** Rebuilding via `.github/workflows/native-libs.yml` and committing the
result with its run URL and source SHA is tracked in `RETIREMENT_LEDGER.md` as
release-blocking.
