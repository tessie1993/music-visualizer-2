# Shipping MusicViz to Google Play

Status of every requirement, what the repo now handles automatically, and what
only you can do from a browser. Work top to bottom.

---

## 1. Hard blockers

### 1.1 16 KB page size — **DONE, but needs an on-device pass**

Google Play requires 16 KB page-size support for every app targeting Android 15
or newer (MusicViz targets 36). The bundled libraries were built for 4 KB pages
and have been rebuilt:

```
                     before      after
libprojectM-4.so     0x1000  →  0x4000     12.4 MB → 1.9 MB
libprojectmjni.so    0x1000  →  0x4000     12.0 KB → 8.8 KB
```

The size drop is because the committed binaries were never stripped; the
rebuild strips them, which is also what AGP ships anyway. Alignment cannot be
patched into an existing `.so`, so this required a full recompile.

Done by the **"Rebuild native libs (16 KB aligned)"** workflow (Actions → run
manually), which clones projectM v4.1.7, applies
`tools/projectm-v417-render-fbo-backport.patch`, builds with NDK r28+ and
`-Wl,-z,max-page-size=16384`, strips, verifies, and commits the result back to
the branch. It refuses to commit unless every LOAD segment is ≥ 16384, the
SONAME is unversioned, and every `external fun` in `PMBridge.kt` is exported.

> **Still owed: the MilkDrop device checks.** This is a fresh build of the
> render engine from source, not a repackage — a different NDK, a different
> compiler. Green CI only proves the libraries link, align and export the right
> symbols; it cannot prove a `.milk` preset still renders. Run items 1–3 and
> 19–20 in `docs/DEVICE_CHECKS.md` before shipping.

`android.yml` re-checks alignment on every push and `release.yml` hard-fails on
it, so this cannot silently regress.

### 1.2 Signing key — **you have to create it**

The build reads the upload key from `keystore.properties` locally or from env
vars in CI (see `keystore.properties.example`). Nothing is committed.

```bash
keytool -genkeypair -v -keystore musicviz-upload.jks \
  -alias musicviz -keyalg RSA -keysize 4096 -validity 10000
```

Back the `.jks` and its passwords up somewhere you will still have in five
years. Then add four repository secrets (Settings → Secrets and variables →
Actions):

| Secret | Value |
| --- | --- |
| `MUSICVIZ_KEYSTORE_BASE64` | `base64 -w0 musicviz-upload.jks` |
| `MUSICVIZ_KEYSTORE_PASSWORD` | keystore password |
| `MUSICVIZ_KEY_ALIAS` | `musicviz` |
| `MUSICVIZ_KEY_PASSWORD` | key password |

Push a `v1.0.0` tag (or run the workflow manually) and `release.yml` produces
`app-release.aab` plus `mapping.txt`.

### 1.3 Developer account

- Google Play Console account, one-off **$25** fee.
- Identity verification: personal accounts need ID and address; organisation
  accounts need a **D-U-N-S number**, which can take a couple of weeks — start
  this first if it applies.
- **Personal accounts created after November 2023 must run a closed test with
  at least 12 testers who stay opted in for 14 continuous days before the
  Production track unlocks.** Budget for this: it is a wall-clock delay, not a
  paperwork step. Recruit the testers before you finish the build.

### 1.4 Privacy policy URL

`docs/privacy-policy.html` at the repo root is ready to publish. Enable GitHub
Pages (Settings → Pages → deploy from branch `main`, folder `/docs`) and the
policy lands at:

```
https://tessie1993.github.io/music-visualizer-2/privacy-policy.html
```

That exact URL is already wired into the app's About screen
(`PRIVACY_POLICY_URL` in `ui/AboutSettings.kt`). If you host it elsewhere,
change both.

---

## 2. Already handled in this repo

| Item | Where |
| --- | --- |
| Release build type with R8 + resource shrinking | `app/build.gradle.kts` |
| R8 keep rules for the projectM JNI symbols | `app/proguard-rules.pro` |
| Signing config from `keystore.properties` / env | `app/build.gradle.kts` |
| Uncompressed, page-aligned `.so` packaging | `packaging.jniLibs.useLegacyPackaging = false` |
| Version bumped to **1.0.0 (code 24)** | `app/build.gradle.kts` |
| `android:label` moved to `@string/app_name` | `res/values/strings.xml` |
| Backup / device-transfer rules (cache and crash file excluded) | `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` |
| Predictive back opt-in | `android:enableOnBackInvokedCallback="true"` |
| In-app open-source licenses (LGPL-2.1 obligation for libprojectM) | `ui/AboutSettings.kt` + assets copy task |
| Privacy policy link in-app | `ui/AboutSettings.kt` |
| Signed-AAB pipeline + 16 KB gate | `.github/workflows/release.yml` |
| Native rebuild pipeline | `.github/workflows/native-libs.yml` |
| 16 KB-aligned, stripped native libraries | `app/src/main/jniLibs/arm64-v8a/` |
| Background playback + lock-screen controls | `playback/PlaybackService.kt` |

R8 is newly enabled, so **install the release build on a real device and
exercise MilkDrop presets, export and the equalizer** before uploading. A
missing keep rule shows up as a runtime `UnsatisfiedLinkError`, not a build
failure.

---

## 3. Play Console: exact answers

### Data safety form

MusicViz has no `INTERNET` permission, so nothing can be transmitted. Answer:

- **Does your app collect or share any of the required user data types?** → **No**
- **Is all of the user data collected by your app encrypted in transit?** → n/a
- **Do you provide a way for users to request that their data is deleted?** → n/a
  (nothing is collected; uninstall or *Clear data* removes everything local)
- Data processed only on the device is explicitly **not** "collection" under
  Play's definition — the audio analysis, presets and library edits all qualify.

### Advertising ID

- **Does your app use advertising ID?** → **No** (no ads, no analytics SDK).

### Content rating questionnaire (IARC)

Category: **Utility / Productivity / Communication / Other**. Everything is
"No": no violence, sexuality, profanity, drugs, gambling, user interaction,
location sharing, digital purchases, or unrestricted internet. Expected result:
**Everyone / PEGI 3**.

### Foreground service declaration — required since the 1.0.0 playback change

MusicViz now runs a `mediaPlayback` foreground service so audio survives a
locked screen. Play Console requires every foreground service type to be
declared under **App content → Foreground service types**, and rejects
releases that use one without it. Answer:

- **Type used:** Media playback
- **What it's for:** "Continues playing the user's own music, with lock-screen
  and notification transport controls, while the app is in the background."
- **Video/demo:** the form asks for a short screen recording showing the
  feature in use — record the lock screen with the controls visible while a
  track plays.

### App content declarations

| Question | Answer |
| --- | --- |
| Ads | No ads |
| App access | All functionality available without an account |
| Target audience | 13+ (avoids the Families programme's extra requirements) |
| News app | No |
| COVID-19 contact tracing | No |
| Data safety | See above |
| Government app | No |
| Financial features | None |
| Health | None |

---

## 4. Store listing assets — you need to create these

| Asset | Spec |
| --- | --- |
| App name | ≤ 30 chars, e.g. `MusicViz — Music Visualizer` |
| Short description | ≤ 80 chars |
| Full description | ≤ 4000 chars |
| App icon | 512 × 512 PNG, 32-bit, no alpha padding tricks |
| Feature graphic | 1024 × 500 PNG/JPEG, no transparency |
| Phone screenshots | 2–8, min 320 px, max 3840 px on the long edge, 16:9 or 9:16 |
| 7"/10" tablet screenshots | Optional, but without them the listing is marked phone-only |
| Category | Music & Audio |
| Contact email | required, shown publicly |

The launcher icon in the app is an adaptive vector — export a 512 × 512 PNG of
it for the listing rather than drawing a new one, so the store and the launcher
match. Screenshots are the whole pitch for a visualizer: capture MilkDrop, the
fluid scene, WATER and the Now Playing screen.

**Screenshot rule that catches people out:** no device frames with fake status
bars, no added marketing text that misrepresents the app, and everything shown
must exist in the build you upload.

---

## 5. Release sequence

1. Rebuild the native libs (§1.1), commit, device-check.
2. Create the upload key, add the four secrets (§1.2).
3. Publish the privacy policy (§1.4).
4. Create the app in Play Console; opt in to Play App Signing (default).
5. Tag `v1.0.0` → download `app-release.aab` and `mapping.txt` from the
   workflow artifact.
6. Internal testing track first: upload the AAB, install through Play on a real
   device, confirm the R8 build behaves.
7. Upload `mapping.txt` under the release's *App bundle explorer →
   Deobfuscation files*, or crash traces stay obfuscated.
8. Fill in the store listing, content rating, data safety and app content.
9. Closed test with 12+ testers for 14 days if your account requires it (§1.3).
10. Promote to Production. First review usually takes a few days; new personal
    accounts often take longer.

---

## 6. Recommended before 1.0, not required by Play

- **ABI coverage.** Only `arm64-v8a` ships. That covers essentially every
  modern phone, but excludes x86_64 — Chromebooks and emulators cannot install
  the app, and Play Console will show a reduced device count. Add the ABI to
  `abiFilters` and to the native rebuild workflow if you want that reach.
- **Tablet / foldable screenshots**, otherwise the listing is flagged as not
  optimised for large screens.
- **Pre-launch report**: after the first internal-track upload, Play runs the
  app on physical devices automatically. Read it — it catches GL crashes on
  chipsets you do not own.
- **`docs/DEVICE_CHECKS.md`** should be run in full against the *release*
  build, not just debug.
