# MusicViz — Privacy Policy

**Last updated: 30 July 2026**

MusicViz is an offline music visualizer for Android. This policy describes
what the app does with your information. The short version: MusicViz collects
nothing, sends nothing and has no servers.

## Information the app does not collect

MusicViz does **not** collect, transmit, sell or share any personal
information. Specifically, the app has:

- no user accounts, sign-in or registration;
- no analytics, telemetry, crash-reporting or advertising SDKs;
- no advertising identifiers;
- no `INTERNET` permission — the app is technically incapable of sending your
  data anywhere.

## Information the app accesses on your device

To do its job MusicViz reads data that never leaves your device:

| What | Why | Where it goes |
| --- | --- | --- |
| Audio files and their metadata (title, artist, album, genre, year, duration) | To list, play and analyse your music | Read from device storage; stays on the device |
| Audio waveform data | Beat, tempo and spectrum analysis that drives the visuals | Processed in memory; a compact analysis cache is written to the app's private storage |
| Folders you pick | Optional music folders and an optional preset-mirror folder | Access is granted by you through the Android file picker and can be revoked at any time |
| Your presets, playlists, track edits and settings | To remember your setup between sessions | The app's private storage on your device |
| Crash details | The in-app "last crash" report you can read and copy yourself | A file in the app's private storage; nothing is sent automatically |

### Permissions

- **`READ_MEDIA_AUDIO`** (Android 13+) / **`READ_EXTERNAL_STORAGE`**
  (Android 12 and below): required to find and play the music already on your
  device. Nothing else is read.

MusicViz requests no camera, microphone, contacts, location or network
permission.

## Video export

When you export a visualization, MusicViz writes an MP4 file to your device's
`Movies/MusicViz` folder. The file stays on your device unless *you* choose to
share it through Android's share sheet — at which point the app you pick (for
example a messaging or cloud-storage app) handles it under its own privacy
policy.

## Backups

If Android backup is enabled on your device, your MusicViz presets, playlists
and settings may be included in your Google account backup, handled by Google
under Google's privacy policy. The analysis cache and crash file are excluded.
You can disable this in your device's backup settings.

## Children

MusicViz contains no ads, no in-app purchases and no user-generated content
from other people. It collects no data from anyone, including children.

## Deleting your data

All MusicViz data lives in the app's private storage. Uninstalling the app, or
using Android's *Settings → Apps → MusicViz → Storage → Clear data*, removes
it. Exported videos are ordinary files in your Movies folder and are deleted
like any other file.

## Third-party components

MusicViz bundles open-source libraries (including libprojectM, AndroidX/Jetpack
and JTransforms). None of them collect or transmit data in this app. The full
notices are in the app under **Settings → Export & About → Open source
licenses**.

## Changes to this policy

If this policy changes, the updated version will be published at this address
and the "last updated" date above will change.

## Contact

Questions about this policy: **tessie-1993@hotmail.com**
