# Quality Bar: Audio Player Side of an Android Music App

Clean-room benchmark derived from external research only. Gold standard: **Poweramp** (closed-source, custom 64-bit engine, DVC, hi-res output). Secondary references: **Auxio** (open source, Media3/ExoPlayer + taglib), **Musicolet** (multi-queue offline player), **RetroMusicPlayer** (open-source MVVM), plus AOSP/Media3 documentation and the nift4 "Android audio stack from a music player's perspective" deep-dive.

Each item states: the **Standard**, **Why** AAA players do it, and a concrete **Check** a reviewer can run against a codebase.

---

## 1. Playback Engine Standards

### 1.1 Gapless playback

- **Standard**: Consecutive tracks play with zero silence gap. Poweramp, Musicolet, and Auxio all treat gapless as table stakes. With ExoPlayer/Media3 this comes from the playlist API (`setMediaItems`/`addMediaItem` on one player instance), which parses gapless metadata (LAME/iTunSMPB encoder delay/padding) and trims decoder output. A custom pipeline must implement encoder-delay trimming and pre-buffering of the next track itself.
- **Why**: Albums mastered continuously (live, classical, electronic, concept albums) are audibly broken by even 50 ms gaps; every reference player advertises gapless explicitly.
- **Check**: The next track is enqueued into the *same* player instance before the current one ends (playlist API or `ConcatenatingMediaSource`), not "listen for `STATE_ENDED` → call `setMediaItem` → `prepare()`". Grep for `onPlaybackStateChanged`/completion listeners that construct a new player or re-prepare per track — that pattern guarantees a gap. One `ExoPlayer` instance must live for the whole session, not one per track.

### 1.2 Audio focus handling

- **Standard**: Request focus (`AUDIOFOCUS_GAIN` via `AudioFocusRequest`) before starting playback; on permanent `AUDIOFOCUS_LOSS` pause (do not release the player); on `LOSS_TRANSIENT` pause and auto-resume on `GAIN`; on `LOSS_TRANSIENT_CAN_DUCK` duck to ~20% (or rely on Android O+ auto-ducking); pause on `ACTION_AUDIO_BECOMING_NOISY` (headphones unplugged / BT disconnect). Media3 handles all of this when configured: `setAudioAttributes(attrs, /* handleAudioFocus= */ true)` and `setHandleAudioBecomingNoisy(true)`.
- **Why**: An app that talks over navigation prompts or keeps blaring through the speaker when headphones unplug is the single fastest way to lose users; Google has treated this as core citizenship since 2013.
- **Check**: Either (a) `setAudioAttributes(..., true)` + `setHandleAudioBecomingNoisy(true)` on the ExoPlayer builder, or (b) an explicit `AudioFocusRequest` + `OnAudioFocusChangeListener` covering all four focus events and a registered `ACTION_AUDIO_BECOMING_NOISY` receiver. Fail if playback starts without any focus request, if `AUDIOFOCUS_LOSS` triggers `release()` of the player, or if transient loss never resumes.

### 1.3 Output path, latency, and fidelity

- **Standard**: For a music player, ultra-low latency is *not* the goal (that's for instruments/games); the goals are (a) a clean output path and (b) responsive transport. The AOSP fidelity ranking is BitPerfect > Direct > Offload > Mixer > Spatializer. Poweramp differentiates by offering selectable outputs (AudioTrack / OpenSL / Hi-Res), DVC (volume applied inside its own DSP so the system mixer runs at full scale), float output, and configurable buffers. Baseline for a quality app on Media3: enable float output (`setEnableAudioFloatOutput(true)`) for >16-bit content, keep one `AudioTrack` alive across track changes, and expose the audio session ID (`player.audioSessionId`) so system/third-party equalizers can attach.
- **Why**: The stock MixerThread resamples and truncates to int16; hi-res players avoid gratuitous conversions. Reusing the AudioTrack avoids route re-negotiation glitches at track boundaries.
- **Check**: Play/pause-to-audible latency under ~200 ms in manual test; no `AudioTrack` recreation per track (log route changes); if the app claims hi-res/float support, grep for float output configuration or a `DefaultAudioSink` customization. If the app has an equalizer/visualizer, verify it uses the *player's* audio session ID, and that `Visualizer`/effects are released when playback stops (they hold native resources and record-permission surfaces).

### 1.4 Media3/ExoPlayer vs custom pipeline

- **Standard**: Media3 ExoPlayer is the reference engine for open-source quality players (Auxio uses a patched Media3; RetroMusic uses ExoPlayer/MediaPlayer hybrids). Custom engines (Poweramp's 64-bit DSP, AIMP's BASS-based core) are justified only by features ExoPlayer can't do (parametric EQ chains, DVC, hi-res direct paths). A custom pipeline must re-implement: gapless trimming, focus, becoming-noisy, offload, format coverage (MP3/AAC/FLAC/OGG/OPUS/WAV/APE at minimum), and error resilience for corrupt files.
- **Why**: ExoPlayer gives battle-tested decode, gapless, offload, and MediaSession glue for free; a half-custom pipeline usually delivers the bugs of both worlds.
- **Check**: If the codebase wraps `MediaPlayer` (the legacy API) for a feature-rich player, that is below the bar (no reliable gapless, poor error reporting). If custom `AudioTrack` + `MediaCodec`/`MediaExtractor`: verify explicit handling of encoder delay/padding, decoder EOS draining, sample-rate changes between tracks, and corrupt-file error paths (a thrown decode error must skip the track, not kill the service).

### 1.5 Background playback: MediaSession/MediaBrowser correctness

- **Standard**: The player and `MediaSession` live in a `MediaSessionService`/`MediaLibraryService` (Media3) or a foreground `Service` with `MediaSessionCompat` — never in an Activity. The service runs as foreground-service-type `mediaPlayback` while playing, drops foreground (but keeps the notification) when paused, and implements `onPlaybackResumption()` so the system's resumption notification and BT/Assistant "play" can restart the last queue after process death. A `MediaLibraryService` (browse tree) is required for Android Auto and the System UI resumption carousel.
- **Why**: This is the OS contract: sessions are how lockscreen, Auto, Wear, Assistant, and AVRCP see the app. Media3 auto-syncs player state → session → notification, eliminating whole classes of desync bugs that plagued `MediaSessionCompat` apps.
- **Check**: Manifest declares the service with `android:foregroundServiceType="mediaPlayback"`, the `androidx.media3.session.MediaSessionService` (or `MediaBrowserService`) intent-filter, and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission. UI code never holds the `ExoPlayer` directly — it connects through `MediaController`/`MediaBrowser` or a repository owned by the service layer. `onPlaybackResumption` (or `onGetRoot`/`onLoadChildren` legacy equivalents) is implemented, and killing the process while paused still leaves a working resumption path. Rotating the screen or swiping the app away must not stop audio.

### 1.6 Notification / lockscreen controls

- **Standard**: A `MediaStyle` notification bound to the session, with correct metadata (title/artist/album art), play/pause/next/previous, seekable progress bar on Android 10+ (comes free when `PlaybackState`/duration are correct), and proper dismissal semantics (dismissible when paused, not while playing). Media3's `MediaSessionService` publishes and updates this automatically; overriding it requires `MediaNotification.Provider` done right.
- **Why**: The notification *is* the app for most of a session; lockscreen and QS media controls are rendered by System UI purely from the session, so wrong state there means wrong state everywhere.
- **Check**: Notification actions go through the session (`MediaButtonReceiver`/session callbacks), not ad-hoc `PendingIntent`s mutating internal state. Position/`playbackSpeed`/`lastPositionUpdateTime` are set so the seekbar interpolates instead of being updated by a per-second timer. Album art posted to the notification is a downscaled bitmap, not the full-resolution artwork.

### 1.7 Bluetooth / AVRCP behavior

- **Standard**: Car head units, watches, and headphones read metadata and position over AVRCP 1.3+ straight from the `MediaSession`. Quality players: publish full `MediaMetadata` (title, artist, album, duration, art) on every track change; keep `PlaybackState` position accurate so displays interpolate; respond to `KEYCODE_MEDIA_*` (including play from a cold/dead process via media-button intent); respect absolute volume; and make "autoplay on BT connect" a user *option* (Poweramp: "resume on Bluetooth"), never unconditional.
- **Why**: AVRCP is a pure mirror of the session — apps with stale metadata show the previous song on car displays, and wrong position breaks steering-wheel seeking.
- **Check**: `MediaMetadata` includes `duration` (missing duration = no seekbar on BT displays); media-button handling works with the app process dead (Media3 `MediaButtonReceiver` declared + `onPlaybackResumption` implemented); no code that starts playback on `ACL_CONNECTED` without checking a user preference; `ACTION_AUDIO_BECOMING_NOISY` pauses on BT disconnect.

---

## 2. Library / Scanning Standards

### 2.1 MediaStore usage and its limits

- **Standard**: Two acceptable tiers. Tier A (fast, standard): query `MediaStore.Audio` with a narrow projection, off the main thread, honoring scoped storage (`READ_MEDIA_AUDIO` on 13+, no `MANAGE_EXTERNAL_STORAGE`). Tier B (correctness, Poweramp/Auxio-style): use MediaStore only for file *discovery*, then parse tags natively (taglib/ffmpeg) because MediaStore's metadata is unreliable (missing disc numbers, multi-artist, ReplayGain, original dates). Auxio made tag-parsing the *default and only* path after finding MediaStore data too broken; Poweramp scans folders directly.
- **Why**: MediaStore metadata errors (wrong artists, missing tags) are the top complaint against naive players; but raw file scanning without caching is unacceptably slow — so the choice must be deliberate and cached.
- **Check**: Library queries specify an explicit projection + selection (`IS_MUSIC != 0`), run in a background dispatcher (grep for `contentResolver.query` reachable from the main thread = fail), and never load all columns/`getBitmap` per row. If native tag parsing is used, there must be a persistent cache (Room/DB keyed by file + mtime) so rescans don't re-read every file.

### 2.2 Incremental scanning

- **Standard**: First scan builds the library; subsequent launches diff instead of rescan. Mechanisms: `MediaStore.getGeneration()`/`DATE_MODIFIED`-based delta queries, a `ContentObserver` on `MediaStore.Audio` for live changes, and a DB cache (Auxio's pipeline: Explore [discover + cache lookup] → Extract [parse only new/changed] → Evaluate [build library graph]). Library updates during playback must not interrupt the current track/queue.
- **Why**: A 20k-track library that fully rescans on every launch means a 30-second white screen; the references show the library instantly from cache and reconcile in the background.
- **Check**: A Room/SQLite (or equivalent) cache of tracks exists and is read *before* any scan on launch; scan work compares `DATE_MODIFIED`/generation and skips unchanged files; a `ContentObserver` (or explicit user-triggered rescan) handles added/deleted files; deleting a file that's in the queue is handled gracefully (skip, not crash).

### 2.3 Artwork handling

- **Standard**: Load art through an image pipeline with memory + disk LRU caches (Coil/Glide; Auxio uses Coil), decoded at target view size, keyed by album. Sources in priority order: embedded art → media-store/`ContentResolver.loadThumbnail` → optional network fetch (user opt-in only in offline players). Full-resolution art is decoded only for the now-playing screen; lists get thumbnails.
- **Why**: Artwork is the #1 memory and jank source in music apps; decoding full-size embedded FLAC art (often 1-3 MB PNGs) per list row OOMs on mid-range devices.
- **Check**: No `BitmapFactory.decodeX` of artwork on the main thread or without `inSampleSize`/target-size; an LRU/disk cache is configured; scrolling an album grid of 500+ albums allocates bounded memory (heap dump: bitmap bytes bounded by cache size, not list length); placeholder shown synchronously so lists don't pop.

### 2.4 Large-library performance expectations

- **Standard**: Benchmarks set by references: Poweramp and Musicolet handle 50k+ track libraries; Auxio explicitly targets large libraries with its cached pipeline. Expectations: cached library visible in < 1 s on launch; full cold scan of ~10k tracks in tens of seconds (with progress UI), incremental rescan in low seconds; search over the full library returns as-you-type (< 100 ms per keystroke, in-memory index); lists use `RecyclerView`/Lazy lists with `DiffUtil`/stable keys and fast-scroll.
- **Why**: Serious local-music users are exactly the users with huge libraries; that's who these apps compete for.
- **Check**: Library held as an in-memory model after load (not re-queried per screen); list adapters use `DiffUtil`/`ListAdapter` or Compose keys (grep `notifyDataSetChanged` = warning); search runs against in-memory structures, not per-keystroke DB/ContentResolver queries; a synthetic 10k-track library scrolls at 60 fps (no per-bind allocation/decoding).

---

## 3. UX Standards

### 3.1 Queue semantics

- **Standard**: An explicit, editable, persistent queue with: "Play next" (insert after current) vs "Add to queue" (append) — both present and distinct; reorder by drag and swipe-to-remove; shuffle as a *mode over the queue* that preserves the un-shuffled order for un-toggling; repeat off/all/one. The reference bar: Poweramp's queue is a temporary interruption that returns to the prior list when exhausted; Musicolet supports up to 20 concurrent queues each remembering its position. Minimum bar is one first-class queue; the differentiators show queue state is a domain model, not a UI afterthought.
- **Why**: Queue behavior is the most-argued-about UX surface in every player's forum; ambiguous semantics (where does "play next" insert? does shuffle destroy order?) generate permanent user distrust.
- **Check**: Queue is a serializable model owned by the playback layer (service/repository), mirrored into the player, and persisted (DB/file) on every mutation — not merely `player.getMediaItems()` state that dies with the process. Unit tests exist for insert-next/append/reorder/remove-current edge cases (removing the currently playing item must advance sanely). Shuffle keeps a mapping back to original order.

### 3.2 Seek behavior

- **Standard**: Scrubbing on the now-playing slider updates the position label live and seeks on release (or throttled during drag); seek latency within a local file feels instant (< ~250 ms); notification/lockscreen/BT seekbars work because `PlaybackState` carries position + speed + update-time; long-content niceties (per-track saved position, ±N s jump buttons) are optional but expected in Poweramp-class apps.
- **Why**: Seek is the highest-frequency direct manipulation in the app; laggy or lying seek bars read as a broken engine even when decode is perfect.
- **Check**: Progress UI derives from interpolated position (`position + (now - updateTime) * speed`) or Media3's built-in updater, not a 1 Hz poll posted to the main thread that also invalidates artwork; slider drag does not spam `seekTo` per pixel (grep for unthrottled `seekTo` in drag callbacks); `SEEK_TO` is in the session's enabled actions.

### 3.3 Resume-on-launch

- **Standard**: The app restores the last queue, track, position, shuffle/repeat modes on cold start — *paused*, never auto-playing. Playback state is persisted continuously (on pause/track change/periodic tick), not only in `onDestroy` (which never runs on swipe-kill). System-level resumption (boot, BT play button, media resumption carousel) restarts the same state via `onPlaybackResumption`. Auxio ships "reliable playback state persistence" as a headline feature; Poweramp resumes exactly where you left off; optional auto-resume on headset/BT connect is a setting.
- **Why**: Local-music listening is session-continuation: users expect the app to behave like a paused cassette deck, and unexpected autoplay (on launch or BT connect) is one of the most-hated behaviors on record.
- **Check**: A persistence write path triggered by pause/track-transition events (grep for save calls in listener callbacks, not just lifecycle teardown); restore path proves out under `adb shell am kill` → relaunch (queue + position intact); no `play()` call in cold-start restore code.

### 3.4 Widgets, lockscreen, external surfaces

- **Standard**: At least one resizable home-screen widget (art + title/artist + play/pause/next/prev) driven by the same session state; Poweramp ships multiple configurable widget sizes and lockscreen options. Lockscreen is the MediaStyle notification (post-Lollipop) — no custom lockscreen hacks. Android Auto via `MediaLibraryService` browse tree is the expected external surface for anything competing with Musicolet/Poweramp/Auxio (all three support Auto).
- **Why**: Widgets and Auto are where background-audio apps live; a widget that drifts out of sync with the notification is an instant-visible state-management bug.
- **Check**: Widget updates flow from playback-state callbacks (one code path with the notification), throttled to state *changes* — no per-second `AppWidgetManager.updateAppWidget` (battery) and no full-res bitmaps pushed through `RemoteViews` (TransactionTooLargeException risk: art scaled to widget dp size). If Auto is claimed: `automotive_app_desc`/media intent-filter present and `onGetLibraryRoot`/`onGetChildren` return a browse tree.

---

## 4. Performance Standards

### 4.1 Cold start

- **Standard**: Android vitals flags cold starts ≥ 5 s as excessive; competitive apps target TTID < 2 s on mid-tier hardware (references feel instant: cached library + last state render immediately). Baseline Profiles are the standard tool (documented 30-40% startup reductions). Startup must not block on library scan — show cached data, reconcile later.
- **Why**: A music player is opened dozens of times a day for 10-second interactions; startup cost dominates perceived quality.
- **Check**: `Application.onCreate`/first-Activity path contains no synchronous MediaStore query, DB migration, or tag scan (StrictMode + method trace on cold start); a baseline profile module exists (or a stated reason it doesn't); measured p95 TTID ≤ 2 s on a mid-tier device profile.

### 4.2 Memory footprint

- **Standard**: A local player should sit in the low hundreds of MB even with a big library: bitmap caches bounded (Coil/Glide default ~25% of app heap for memory cache), library model compact (no per-track bitmaps or duplicated strings), no leaked Activities/Visualizers/players. Poweramp runs comfortably on old devices; Auxio's "simple, rational" scope is partly a memory discipline.
- **Why**: The service lives for hours in the background; a fat resident set gets the process killed and playback state lost on low-RAM devices.
- **Check**: LeakCanary (or equivalent) in debug builds with zero known leaks; exactly one `ExoPlayer` instance app-wide (grep constructor call sites); `Visualizer`/`Equalizer`/audio-effect objects released symmetric with creation; heap dump after 30 min of background playback shows no monotonic growth.

### 4.3 Battery discipline for background audio

- **Standard**: Screen-off audio should cost roughly 1-2%/hour. Mechanics: foreground service only while playing; `ExoPlayer.setWakeMode(C.WAKE_MODE_LOCAL/NETWORK)` instead of hand-rolled wakelocks; audio offload (`Media3` offload mode) evaluated for screen-off playback (Qualcomm/Google work brought ExoPlayer within ~5% of MediaPlayer's DSP-offload power, but offload disables effects/speed/silence-skip — so it must be conditional); zero timers/animations running while UI is invisible; widget/notification updates event-driven.
- **Why**: Background audio is the app's whole job; a visualizer or progress timer left running screen-off will dominate the battery graph and earn vitals flags for excessive wakelocks.
- **Check**: No raw `PowerManager.newWakeLock` held across pause/stop (wake mode API or precisely scoped locks only); UI progress updaters and any visualizer capture stop in `onStop`/when screen off (grep lifecycle handling around `Visualizer.setEnabled` and `Handler.postDelayed` loops); foreground status dropped on pause (`stopForeground(STOP_FOREGROUND_DETACH)` semantics or Media3 default); Battery Historian / `dumpsys batterystats` over a 1-hour screen-off playback shows no non-audio wakelock dominance.

### 4.4 Main-thread discipline

- **Standard**: Main thread renders UI; everything else — scanning, tag parsing, DB, artwork decode, playlist I/O — is on background dispatchers. ExoPlayer's own contract: access the player from a single (usually main) thread, but its internal playback work happens on its playback thread, so player *control* on main is correct and cheap. Jank budget: 60 fps lists, no frames > 16 ms during scroll of large lists, no ANRs.
- **Why**: Jank in a music app is read as "cheap"; ANRs during scan are the classic failure of naive players.
- **Check**: StrictMode (disk + network on main) clean in debug; Room/DAO calls are `suspend`/Flow-based (no `allowMainThreadQueries` — grep it, fail if present); `ExoPlayer` touched from exactly one thread (Media3 asserts this — no `setApplicationLooper` hacks scattered around); Perfetto/systrace of a library scroll shows no main-thread decode/IO.

---

## 5. Architecture Standards (from Auxio, RetroMusic, and Media3 guidance)

### 5.1 Module / package boundaries

- **Standard**: Separate the three domains that change for different reasons: **music loading/library** (Auxio extracted this as the `musikr` module with its taglib internals), **playback engine + service** (player, session, queue), and **UI** (per-feature packages/screens). RetroMusic: MVVM + Jetpack components + DI (Koin); Auxio: `app` / `musikr` / patched `media` (Media3) modules. Dependencies point one way: UI → playback state / library repositories → engine; the engine never imports UI.
- **Why**: The playback service outlives every Activity; letting UI classes reach into the engine (or the engine hold view references) is the root cause of both leaks and state desync in low-quality players.
- **Check**: Package/module graph shows no import of Activity/Fragment/Compose types from service/engine/library packages (a lint or Konsist rule enforcing this is a plus); music loading code compiles without the UI module; the queue and playback-state types live in a non-UI layer.

### 5.2 Service/UI separation and the single source of truth

- **Standard**: The service owns the player; UI processes observe. Two proven shapes: (a) Media3-canonical — UI builds a `MediaController` against the `MediaSessionService`, and controller state *is* the UI's truth; (b) Auxio/RetroMusic shape — a singleton playback-state repository (in-process) that both service and ViewModels observe, with the service as the only writer of engine commands. Either way there is exactly one authority for "what is playing", and notification, widget, Auto, AVRCP, and in-app UI all render from it.
- **Why**: Every user-visible desync bug (notification says playing, app says paused; widget shows the previous track) is a two-sources-of-truth bug.
- **Check**: Grep for `ExoPlayer` usage in Activities/Fragments/Composables — must be zero (only `MediaController`/`Player` *interface* via the session, or a repository). State flows out as observable (`StateFlow`/`LiveData`/`Player.Listener`), commands flow in through one funnel (controller or repository methods). Process-death test: swipe-kill the UI while music plays → reopen → UI reflects live state within a frame, without restarting the service.

### 5.3 State management

- **Standard**: ViewModels per feature expose immutable observable state; playback position is derived/interpolated, not stored; queue + modes are persisted domain state (Room/DataStore) restored by the service, not the UI. Configuration changes and process death are non-events for playback. Auxio ships persistence of playback state as a feature; Media3 formalizes restore via `onPlaybackResumption`.
- **Why**: A music app is a long-running state machine with five simultaneous render targets; ad-hoc mutable singletons written from callbacks are how queues get corrupted.
- **Check**: State classes are immutable (`data class` copies / `StateFlow` emissions — mutation of shared lists in place is a fail); rotation during playback preserves scroll + now-playing without a hitch; the persistence schema includes queue order, current index, position, shuffle/repeat; restore is covered by a test.

### 5.4 Testability and error resilience

- **Standard**: Queue logic, library merging/diffing, and sort/search are pure and unit-tested (they need no device); engine integration is thin. Corrupt/deleted/unsupported files degrade to a skipped track with a logged reason — never a crashed service. Auxio's issue history shows loader robustness (weird tags, encodings, dates) is where quality players spend their effort.
- **Why**: The difference between a 4.7-star player and a 3-star one is what happens on the 1% of weird files and empty libraries, and that's only cheap to guarantee with pure, tested domain logic.
- **Check**: Unit tests exist for queue mutations and library diff/merge; player error listener (`onPlayerError`) advances to the next track and surfaces a non-fatal notice; empty-library, permission-denied, and all-files-deleted states each have designed UI (not a spinner or crash).

---

## Sources

- Poweramp official site / feature set: https://powerampapp.com/ ; Play listing: https://play.google.com/store/apps/details?id=com.maxmpz.audioplayer ; queue KB: https://forum.powerampapp.com/kb/en_us/guides/what-is-the-difference-between-playing-by-%E2%80%98category%E2%80%99-or-playlists-and-using-the-%E2%80%98queue%E2%80%99-r3/
- Android audio stack deep-dive (output threads, DVC-style tricks, float output, offload, A2DP): https://nift4.org/2025/08/09/android-audio-stack-music-player/
- Auxio repo + wiki (Media3 patched, taglib, musikr modules, gapless/ReplayGain, state persistence): https://github.com/OxygenCobalt/Auxio ; https://github.com/OxygenCobalt/Auxio/wiki/Architecture ; F-Droid: https://f-droid.org/en/packages/org.oxycblt.auxio/
- Musicolet (multi-queue, gapless, offline, Auto): https://www.linuxlinks.com/best-free-android-apps-musicolet-offline-music-player/ ; https://www.makeuseof.com/the-musicolet-app-made-me-love-owning-my-music-again/
- RetroMusicPlayer (MVVM, Jetpack, Koin): https://github.com/RetroMusicPlayer/RetroMusicPlayer
- Media3 background playback / MediaSessionService / resumption: https://developer.android.com/media/media3/session/background-playback ; https://developer.android.com/media/media3/session/control-playback ; https://android-developers.googleblog.com/2023/03/media3-is-ready-to-play.html
- Audio focus guidance: https://developer.android.com/media/optimize/audio-focus ; https://medium.com/androiddevelopers/audio-focus-3-cdc09da9c122 ; https://android-developers.googleblog.com/2013/08/respecting-audio-focus.html
- ExoPlayer gapless/audio features: https://medium.com/google-exoplayer/exoplayer-2-x-new-audio-features-cfb26c2883a ; offload/battery: https://developer.android.com/media/media3/exoplayer/battery-consumption ; https://proandroiddev.com/how-qualcomm-added-audio-offload-support-for-exoplayer-e13a9c41d4e7
- App startup vitals / baseline profiles: https://developer.android.com/topic/performance/vitals/launch-time ; https://medium.com/@sneha_71656/baseline-profiles-decoded-74b9c6c0d71c
- AVRCP/session mirroring: https://en.androidayuda.com/bluetooth-avrcp-what-is-it-and-what-is-this-profile-for/ ; https://gitlab.com/gateship-one/malp/-/work_items/190
