# Music Player Plan — Poweramp inventory and what MusicViz actually needs

Owner constraints: plan only (no implementation yet); NO online/streaming/cloud music
functions — network features are listed for completeness and marked out of scope.

## Summary

Complete Poweramp feature inventory (from powerampapp.com, the Play Store listing via APKPure mirror, forum.powerampapp.com changelog threads, and the powerampapi GitHub docs) cross-referenced against MusicViz's shipped code (docs/PERFECTION_PLAN.md Workstream F; ui/ and playback/ sources). Headline conclusions: (1) MusicViz already has Poweramp's speed/pitch, skip-silence, fade, system-EQ+bass/loudness, queue, playlists, favourites, sleep timer, tag editor, MediaSession lock-screen/BT plumbing — and its visualizer (on-device projectM/Milkdrop, 57 GPU scenes, export, wallpaper, capture) strictly exceeds Poweramp's Milkdrop-preset visualization, which is Poweramp's weakest area and MusicViz's moat. (2) The genuine gaps are Poweramp's table stakes: folder browsing, verified gapless+crossfade, widgets+seek notification, library category completeness, Android Auto, ReplayGain, backup/M3U, headset/BT polish, stats/smart playlists. (3) Poweramp's audiophile stack (hi-res output paths, USB-DAC/DSD, 64-band parametric EQ, AutoEQ, DVC, resampler/dither) is its identity, not MusicViz's — skip; MusicViz's identity investment goes to the visualizer, casting the visualizer to TV, and the waveform seekbar. (4) All network features (internet radio/stream URLs, album-art download; Poweramp has no Subsonic/UPnP at all) are marked out-of-scope-online per the hard constraint; scrobbling and LRCLIB lyric fetch are flagged as online-adjacent needing owner sign-off. Ranked need-list is in the priority-list item.

## Feature-by-feature verdicts

### Playback: hi-res output paths (AAudio/OpenSL, 24/32-bit up to 384 kHz), Direct Volume Control, resampler/dither config, float32 pipeline

VERDICT: skip (niche — conflicts with our identity). This is Poweramp's core audiophile differentiator: a custom native engine bypassing Android's mixer for bit-perfect-ish output. MusicViz is a visualizer-first player on Media3/ExoPlayer whose engine exists to feed a PCM tap into the renderer (PlaybackEngine.kt builds ExoPlayer with TapRenderersFactory); a bespoke native output path would fork the entire audio stack for a buyer we aren't courting. PERFECTION_PLAN.md already defers 'hi-res/USB-DAC bit-perfect output' explicitly. Our audio-quality story is instead: clean Media3 output + ReplayGain + no ads/subscription.

*Source: powerampapp.com; PERFECTION_PLAN.md §F deferred list; playback/PlaybackEngine.kt*

### Playback: USB DAC exclusive driver (PCM to 768 kHz), DSD64–1024 native/DoP, DSD remastering

VERDICT: skip (niche). Hardware-exclusive USB audio drivers and DSD are deep audiophile territory with per-device support matrices and a support burden Poweramp built over a decade. Zero overlap with the visualizer purchase motive. Explicitly on the plan's deliberately-deferred list.

*Source: powerampapp.com (USB Exclusive Driver, DSD); PERFECTION_PLAN.md §F*

### Playback: gapless playback

VERDICT: need (top table-stake). Poweramp advertises gapless prominently. Media3 gives MusicViz gapless mostly for free, but the plan flags it must be VERIFIED per-format — especially our custom AIFF extractor path — and then advertised. Effort: small (test matrix + store copy), which is why it ranks near the top on value/effort.

*Source: powerampapp.com; PERFECTION_PLAN.md F2; playback/PlaybackEngine.kt (custom AIFF extractor)*

### Playback: crossfade + fade-in/out + silence removal at track boundaries

VERDICT: partial have / need remainder. MusicViz already ships fade-on-pause/resume/skip (PlaybackSettings.kt fadeMs) and skip-silence. True track-to-track crossfade is the missing piece; Media3 has no built-in crossfade so it needs a dual-player or mixing-processor design (plan F2 phase 2). Medium effort; ship after gapless verification since fade-on-pause already covers the most-felt case.

*Source: APKPure/Play listing; PERFECTION_PLAN.md F2; ui/PlaybackSettings.kt*

### Playback: speed/tempo and pitch control

VERDICT: have. Poweramp added tempo control in the 90x builds; MusicViz already ships speed 0.5–2.0x and pitch ±6 semitones with snap (PlaybackSettings.kt, analysis/PlaybackMath.kt). Parity done.

*Source: forum.powerampapp.com changelog; ui/PlaybackSettings.kt; analysis/PlaybackMath.kt*

### Playback: ReplayGain (track+album gain, prevent-clipping)

VERDICT: need. Poweramp table stake for anyone with a tagged library; loudness jumps between albums are a churn driver. Plan F6: RG 2.0 track+album + R128 for Opus via a custom ExoPlayer AudioProcessor (Auxio's pattern, reimplemented), plus the differentiator of scanning untagged files with our existing offline analyzer. Fold in a peak limiter for the prevent-clipping half. Medium effort, high credibility payoff.

*Source: powerampapp.com; PERFECTION_PLAN.md F6*

### Playback: repeat modes and shuffle scopes (repeat list/track/advance-category; shuffle songs/lists/all)

VERDICT: partial have / need small. Basic repeat/shuffle exist with the queue (PlaybackQueue.kt/QueueOps.kt). Poweramp's per-category shuffle scopes are power-user sprawl — skip the full matrix (G12 anti-clutter rule) — but plan F12's favourite/recency-weighted shuffle with a 'true random' toggle is the version of this that fits us and is cheap on top of HistoryStore.

*Source: powerampapp.com; playback/QueueOps.kt; PERFECTION_PLAN.md F12, G12*

### DSP/EQ: 64-band parametric + graphic EQ with configurable filter types

VERDICT: skip (Poweramp's niche, not ours). MusicViz has the platform AudioFx graphic EQ + device presets + bass boost + loudness (EqualizerSettings.kt, gracefully degrading when unsupported). A 64-band parametric engine is Poweramp's headline audiophile feature and is explicitly on our deferred list; for a visualizer-first app the marginal buyer gains nothing and settings sprawl is our named risk. Keep the simple EQ; revisit only if reviews demand it.

*Source: powerampapp.com (64-band parametric EQ); ui/EqualizerSettings.kt; PERFECTION_PLAN.md deferred list + G12*

### DSP/EQ: per-device / per-output EQ preset auto-assignment

VERDICT: need (low priority, cheap rider). Poweramp auto-switches EQ per output (phone speaker vs BT headphones vs car). Once F9's Bluetooth device detection exists (resume-on-connect), remembering an EQ preset per audio route is a small delta with real offline value — speaker vs headphone curves genuinely differ. Implement as 'remember last preset per route', not a settings tree.

*Source: powerampapp.com (per-device EQ and presets); PERFECTION_PLAN.md F9*

### DSP/EQ: AutoEQ headphone-model preset database

VERDICT: skip (niche + maintenance burden). Poweramp bundles AutoEQ correction curves for hundreds of headphone models. Valuable only downstream of a parametric EQ we're not building, and the database is a treadmill. Out of proportion for us.

*Source: powerampapp.com; forum.powerampapp.com (builds 1000-1002 AutoEq)*

### DSP/EQ: tone controls (bass/treble), Stereo eXpand, mono mode, balance, reverb

VERDICT: mostly skip. Bass boost + loudness: have (EqualizerSettings.kt); treble is reachable via EQ bands. Stereo expand and reverb: skip — effects-for-effects'-sake, off-identity (reverb on music playback is a gimmick). Mono/balance: skip with a note — Android system accessibility settings provide both globally; duplicating them is clutter unless accessibility feedback asks.

*Source: forum.powerampapp.com (Equalizer/Tone/Stereo expand, Reverb/Tempo effects); ui/EqualizerSettings.kt*

### DSP/EQ: limiter/compressor

VERDICT: need (folded into ReplayGain work). Poweramp ships a limiter so EQ boost + gain don't clip. MusicViz will need exactly this the moment ReplayGain pre-amp and EQ coexist — implement as an automatic peak-protection stage inside the F6 AudioProcessor, not a user-facing toggle.

*Source: powerampapp.com; PERFECTION_PLAN.md F6*

### Library: folder browsing / folders-hierarchy with per-folder play/shuffle/queue

VERDICT: need (#1 gap). The most-cited must-have in every player comparison; Poweramp treats folders as a first-class view. MusicViz's library (LibraryScreen/TrackLibrary) has no folder view. Plan F1, low-medium effort since scanning already exists. Highest value/effort ratio of all gaps.

*Source: powerampapp.com (folder & library views); PERFECTION_PLAN.md F1; ui/LibraryScreen.kt*

### Library: category completeness — album artist, genre, composer, year views; multi-artist tag separators; natural sort; per-list sort persistence; search everywhere; grid/list toggle

VERDICT: need. Poweramp offers all of these; the plan's F8 lists them with 'A; B' separator handling called the #1 concrete power-user ask. Compilations breaking without album-artist is a visible library bug to affected users. Medium effort, mostly data-layer + list chrome on existing screens.

*Source: APKPure/Play listing; PERFECTION_PLAN.md F8*

### Library: smart/auto categories (recently added, recently played, most played) and smart playlists

VERDICT: need (cheap — data already recorded). Poweramp's newer builds ship these as library categories. MusicViz's HistoryStore already records permanent play history, so most/never-played, recently-added and a Wrapped-style recap (plan F12) are low-effort, high-delight. Pairs with weighted shuffle.

*Source: powerampapp.com (smart playlists); PERFECTION_PLAN.md F12; ui/HistoryStore.kt*

### Library: ratings (5-star + like/dislike) and top/low-rated lists

VERDICT: skip stars, have the rest. MusicViz has Favourites (FavouritesStore.kt) which covers the like case; a 5-star system adds a second parallel rating vocabulary and list clutter for a small cohort. If demand appears, add thumbs-down for shuffle exclusion before stars.

*Source: powerampapp.com; ui/FavouritesStore.kt; PERFECTION_PLAN.md G12*

### Library: tag editor with album-art editing; bulk/multi-select editing

VERDICT: have single-track / need bulk. TrackInfoEditor.kt ships. Poweramp edits tags and artwork in-place; the gap is multi-select bulk editing (plan F14: fix-an-album-in-one-pass) plus M4A edge cases. Medium effort on the existing editor.

*Source: APKPure/Play listing (tag editor); ui/TrackInfoEditor.kt; PERFECTION_PLAN.md F14*

### Library: album/artist art downloading

VERDICT: out-of-scope-online (listed for completeness). Poweramp fetches missing art from the internet. Hard constraint excludes online functions. The offline version — embedded-tag art, folder images, pick-from-gallery in the tag editor — is in scope and partly exists (Artwork.kt); make sure gallery-pick is wired in the editor.

*Source: powerampapp.com (album art downloading); ui/Artwork.kt*

### Library: cue sheet (.cue) support for tracks split by index files

VERDICT: skip (niche). Serves the one-FLAC-per-album ripping workflow, an audiophile-archivist pattern outside our audience. Costly to do correctly (virtual tracks through the whole stack: queue, history, tags, visualizer timing).

*Source: APKPure/Play listing (CUE data)*

### Library: playlist file support — m3u/m3u8/pls/wpl import/export, file-playlist rescan

VERDICT: need m3u/m3u8, skip pls/wpl. Playlists UI just shipped (MusicPlaylistStore.kt + B1); portability is the trust half. Plan F7 specifies M3U/M3U8 via SAF into user-visible storage — scoped-storage export failure is a named Poweramp complaint we can pointedly avoid. pls/wpl are legacy remnants; import-only tolerance at most.

*Source: APKPure/Play listing; PERFECTION_PLAN.md F7; ui/MusicPlaylistStore.kt*

### Library: queue (user queue, add-next/add-last); Poweramp single queue vs multiple named queues

VERDICT: have / skip multiples. MusicViz has a full queue (PlaybackQueue.kt, QueueOps.kt). Multiple named queues (a Musicolet signature, not even a Poweramp feature) stay deliberately deferred. Polish path is G5's peeking 'Up Next' sheet with play-next vs add-last split — UX, not new capability.

*Source: playback/QueueOps.kt; PERFECTION_PLAN.md deferred list + G5*

### UI: skins/themes (built-in + third-party skin APKs), layout rearrangement

VERDICT: skip (conflicts with identity). Poweramp's third-party skin ecosystem is its answer to a dated default UI. MusicViz's 8 curated mineral themes + palettes + crystal design system ARE the brand; an open skin surface would dissolve it and G12 explicitly chooses curation over toggles. Light/dark + theme choice: have.

*Source: powerampapp.com (themes, layouts, skins); PERFECTION_PLAN.md G12; ui/AppTheme.kt*

### UI: gestures (swipe album art to change track, configurable list/press actions)

VERDICT: need the 20% (swipe-to-skip on art/now-playing), skip the configurability matrix. Swipe-track-change is muscle memory for players; cheap to add to the player panel. Poweramp's fully remappable gesture system is sprawl we don't want.

*Source: powerampapp.com (gestures); PERFECTION_PLAN.md G12*

### UI: home-screen widgets (multiple sizes, configurable) + MediaStyle notification with seek

VERDICT: need. Poweramp ships several resizable widgets; MusicViz ships none — plan F5 calls for 2+ Glance widgets plus a verified seekable MediaStyle notification, and notes the crystal design language could make the best-looking widgets on the platform. High daily visibility, medium-low effort.

*Source: APKPure/Play listing (widgets); PERFECTION_PLAN.md F5*

### UI: lock-screen controls (system) and Poweramp's own custom lockscreen

VERDICT: have system / skip custom. MediaSession already drives the system lock screen, notification and BT buttons (documented in PlaybackEngine.kt). Poweramp's proprietary lockscreen replacement is a legacy-Android artifact; modern Android media controls make it redundant.

*Source: playback/PlaybackEngine.kt session comment; powerampapp.com*

### UI: visualization (Milkdrop .milk v1/v2 preset support, spectrum, VU meter)

VERDICT: have — and strictly superior; this is the moat. Poweramp translates .milk presets via an internal Lua/GLES pipeline as a nice-to-have side feature. MusicViz runs projectM (the Milkdrop engine) natively PLUS 57 first-party GPU scenes, video export, live wallpaper, second-display output, performance takes, system-audio capture and a photosensitivity limiter — none of which Poweramp has. Market this comparison explicitly.

*Source: github.com/maxmpz/powerampapi vis presets docs; PERFECTION_PLAN.md §0 market position*

### UI: waveform seekbar

VERDICT: need (identity-aligned parity). Poweramp's waveform seekbar is one of its most-copied UI signatures. Plan G7 already specifies our version — a refracting crystal line amplitude-modulated by live analysis, with a scrub tooltip. Medium effort; doubles as brand signature rather than parity chore.

*Source: Poweramp v3 UI (forum/reviews); PERFECTION_PLAN.md G7*

### UI: lyrics — embedded (SYLT/USLT) and .lrc synced display; Poweramp does fetch via third-party plugin only

VERDICT: partial have / need local-first completion; flag the online half. Lyrics.kt exists; complete local support (LRC files alongside tracks + embedded tags, synced highlight) is squarely in scope and beats Poweramp's plugin-dependent story. Plan F11's LRCLIB auto-fetch is ONLINE-ADJACENT: it is metadata, not music streaming, but under the hard no-online constraint it needs explicit owner sign-off before scoping; lyrics-inside-the-visualizer stays the unique in-scope payoff.

*Source: APKPure/Play listing (synced lyrics via plugins); PERFECTION_PLAN.md F11; ui/Lyrics.kt*

### Platform: Android Auto

VERDICT: need (deal-breaker class). Poweramp ships full Auto browse + controls. Plan F3: Media3 MediaLibraryService with the Apache-2.0 androidx session demos as reference. A hard purchase decider for commuters; medium-high effort but well-trodden. The visualizer obviously stays off the car screen — this is pure player parity.

*Source: powerampapp.com; PERFECTION_PLAN.md F3*

### Platform: Chromecast

VERDICT: need (v2.0, as a differentiator not parity). Cast is local-network rendering, not cloud music, so it survives the constraint. Poweramp casts audio; plan F10's version casts audio AND a WebGL (butterchurn, MIT) visualizer receiver driven by beat/FFT messages — answering the #1 visualizer wish ('visuals on the TV'). High effort; DreamService screensaver is the cheap sibling to ship first.

*Source: powerampapp.com (Chromecast); PERFECTION_PLAN.md F10*

### Platform: Bluetooth/AVRCP metadata, resume-on-connect, pause-on-disconnect, multi-press headset actions

VERDICT: partial have / need polish. Pause-on-unplug and auto-resume toggles ship (PlaybackSettings.kt); MediaSession gives baseline BT control. The F9 gap: opt-in resume-on-BT-connect, double/triple-press actions, and verified AVRCP metadata on car head units (a silent-failure area that erodes trust). Low-medium effort.

*Source: APKPure/Play listing (headset/Bluetooth); PERFECTION_PLAN.md F9; ui/PlaybackSettings.kt*

### Platform: launcher shortcuts, Assistant/voice integration, intent/Tasker API, scrobbling broadcasts

VERDICT: mixed. App shortcuts (long-press icon → shuffle-all/resume): need, trivial. Assistant playback: mostly free via Media3 session — accept what's free, build nothing. Public intent/Tasker API: skip (tiny cohort, forever-support surface). Scrobbling: Poweramp only broadcasts to third-party scrobblers; plan F13 wants built-in Last.fm/ListenBrainz — ONLINE-ADJACENT, needs owner sign-off under the no-online constraint; cheapest compliant step is emitting the standard scrobble broadcast locally.

*Source: powerampapp.com; PERFECTION_PLAN.md F13*

### Formats: MP3, FLAC, ALAC, WAV, APE, DSD, Opus, TAK, MKA, AIFF, WebM + m3u/pls radio streams

VERDICT: have core / skip exotics / streams out-of-scope-online. Media3 covers mp3, aac/m4a-alac, flac, ogg, opus, wav, webm and MusicViz adds its own AIFF extractor (a Poweramp-matching rarity — advertise it). APE/TAK/MKA/DSD: skip — vanishingly rare outside audiophile archives and the ffmpeg-extension route drags in licensing and size. HTTP radio streams (m3u/pls URLs): listed for completeness, out-of-scope-online per constraint. Note Poweramp itself has NO Subsonic/UPnP/cloud sources — full parity is already compatible with offline-only.

*Source: APKPure/Play listing (formats); playback/PlaybackEngine.kt (AIFF)*

### Backup: settings + EQ preset export/import; playlists as files

VERDICT: need. Poweramp exports/imports settings and presets. Plan F7 goes further: one-file full backup (settings + playlists + favourites + presets + palettes + EQ) atop the existing AtomicWrite store layer, plus M3U export. Trust infrastructure for a paid app — people who tag and rate for years need to believe nothing is lost on a new phone. Medium effort, disproportionate goodwill.

*Source: powerampapp.com (export/import presets and settings); PERFECTION_PLAN.md F7; ui/AtomicWrite.kt*

### Misc: sleep timer (with play-last-song-to-end option)

VERDICT: have / need small finish. MusicViz ships a 15/30/45/60-min timer (PlaybackSettings.kt). Poweramp adds finish-current-track; plan F4 adds that plus track-count variant and fade-out — a few hours of work that turns parity into a differentiator vs Musicolet.

*Source: forum.powerampapp.com (sleep timer); ui/PlaybackSettings.kt; PERFECTION_PLAN.md F4*

### Misc: ringtone assignment, share track, per-app Poweramp Equalizer companion product

VERDICT: skip all three. Ringtone-setting is a declining-relevance feature with WRITE_SETTINGS permission friction — off-brand for a minimal-permissions privacy stance. Plain share-file is trivial but our F15 social-format visual export is the on-identity version of 'share'. The separate per-app Equalizer product is a different business, not a feature gap.

*Source: powerampapp.com; PERFECTION_PLAN.md F15 + moats list*

### Business model note: Poweramp = one-time purchase + 14-day trial, no ads

VERDICT: have (and it is the #1 stated moat). MusicViz matches the one-time-purchase, no-ads, minimal-permissions model — subscription fatigue is the loudest deal-breaker in this category. Keep it loud in every release; consider Poweramp-style full-featured trial as a store-conversion experiment, not a product feature.

*Source: powerampapp.com; PERFECTION_PLAN.md §0 + moats*


## Ranked build order

### Ranked NEED list (value/effort) — the player-parity build order

1) F1 Folder browsing with per-folder play/shuffle/queue — most-cited must-have, scanner already exists, low effort. 2) F2a Gapless verified per-format (incl. our AIFF path) + advertised — near-free, table stake. 3) F5 Widgets (2+ Glance sizes) + seekable MediaStyle notification — daily visibility, crystal-design showcase, medium-low effort. 4) F4+ Sleep-timer finish (finish-track, track-count, fade) — hours of work, closes a checklist line. 5) F8 Library completeness (album-artist, genre/composer/year, 'A; B' separators, natural sort, per-list sort persistence, search, grid/list) — medium effort, fixes visible library wrongness. 6) F9 Headset/BT polish (resume-on-connect opt-in, multi-press, AVRCP verification) + per-route EQ preset memory as a cheap rider. 7) F12 Stats + smart categories + weighted shuffle — HistoryStore already records the data; low-medium effort, high delight, feeds a shareable Wrapped recap. 8) F7 Backup/restore one-file + M3U/M3U8 SAF export — trust infrastructure; do before asking anyone to migrate phones. 9) F3 Android Auto (MediaLibraryService) — deal-breaker class, medium-high effort, liftable Apache-2.0 reference code. 10) F6 ReplayGain 2.0 + auto peak limiter, later offline scanner for untagged files via existing analyzer — medium effort, audio-credibility anchor. 11) F2b Crossfade (dual-player/mixing processor) — medium; after gapless. 12) G7 Waveform crystal seekbar — parity chore turned brand signature. 13) F14 Bulk tag editing on existing editor. 14) F11 Local synced lyrics (LRC/SYLT) fully offline; LRCLIB auto-fetch ONLY after owner sign-off (online-adjacent). 15) Launcher shortcuts + swipe-art gesture — trivial sweep item. 16) F10 Chromecast audio + WebGL visualizer receiver + DreamService screensaver — v2.0 differentiator, high effort, local-network so constraint-compatible. 17) F13 Scrobbling — cheap but online-adjacent; needs owner sign-off, or ship the offline-compatible scrobble broadcast only. SKIPPED on identity/niche grounds: hi-res/USB-DAC/DSD stack, DVC/resampler/dither, 64-band parametric EQ, AutoEQ database, reverb/stereo-expand/mono/balance, skins ecosystem, custom lockscreen, cue sheets, 5-star ratings, APE/TAK/MKA/DSD formats, ringtone, intent/Tasker API, multiple named queues. OUT-OF-SCOPE-ONLINE per hard constraint: internet radio/stream URLs, album-art downloading, any cloud/streaming/Subsonic/UPnP (Poweramp ships none of the latter either — full Poweramp parity is achievable fully offline).

*Source: PERFECTION_PLAN.md §F + release sequencing; powerampapp.com; APKPure/Play listing*
