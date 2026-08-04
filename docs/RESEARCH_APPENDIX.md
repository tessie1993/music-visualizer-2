# Research Appendix — Perfecting MusicViz

Produced 2026-08-04 by four parallel web-research agents (workflow `perfect-app-research`).
Each item carries its source. Priorities: MUST = table-stakes / legal, SHOULD = strong value,
COULD = differentiator or niche. This file is the evidence base for `PERFECTION_PLAN.md`.

## Competitor & feature-parity research

Research across the 10 leading Android local players (Poweramp, Musicolet, Symfonium, Neutron, AIMP, BlackPlayer EX, Gramophone, Oto, foobar2000, Winamp) shows a consistent table-stakes core that buyers treat as the definition of a 'complete' player: full playlist UI, folder/filesystem browsing, gapless + crossfade, ReplayGain volume normalization, Android Auto, sleep timer, home-screen widgets, quick search/sorting everywhere, and playlist/settings backup (M3U). MusicViz is missing or hasn't surfaced most of these — the playlist UI (backend exists) and folder browsing are the two loudest gaps, since even free players (Musicolet, Gramophone, Oto) ship them. Second-tier differentiators buyers cite: Chromecast, scrobbling, smart playlists, bulk tag editing, hi-res/USB-DAC output, and per-device EQ profiles. On the visualizer side MusicViz already outguns every mobile competitor (on-device projectM + 57 GPU scenes, video export, system-audio capture, safety limiter, performance takes are genuine moats); the real visualizer gaps are social-video export formats (vertical 9:16, 4K60, text/logo overlays — the Avee/Specterr use case) and a browsable community preset gallery (Synesthesia's marketplace model).

### [MUST] Playlist management UI (backend exists, no UI)

Every competitor — Poweramp (several playlist types), Musicolet (add to multiple playlists from notification/widget/lockscreen), AIMP, Oto, Gramophone, Symfonium — ships full playlist create/edit/reorder/add-from-anywhere UI. MusicViz has the backend only; this is the single most visible 'incomplete player' signal. Ship: playlist list/detail screens, add-to-playlist from track context menu and now-playing, drag reorder, and add-to-playlist from the notification like Musicolet.

Source: https://krosbits.in/musicolet/

### [MUST] Folder / filesystem browsing

Cited constantly as a reason to pick Poweramp ('browse by folders, not just library tags'), Musicolet (two types of folder browsing, move/copy/rename files in-app), Gramophone (folder + filesystem view called out in reviews as 'what most players don't have'), AIMP (smart playlists generated from folder structure). Users with curated file trees will not adopt a player without it. Ship a folder tab with per-folder play/shuffle/queue.

Source: https://f-droid.org/en/packages/org.akanework.gramophone/

### [MUST] Gapless playback (verified + advertised)

Table stakes in every top player: Poweramp, AIMP ('flawless gapless'), foobar2000 mobile, Oto, Symfonium, Musicolet, BlackPlayer EX. ExoPlayer supports gapless for properly tagged files — verify it works across MusicViz's formats (incl. AIFF), fix edge cases, and list it explicitly; buyers search for the word.

Source: https://alternativeto.net/feature/gapless-playback/

### [MUST] Crossfade (manual-skip and auto, configurable duration)

Poweramp, BlackPlayer EX, AIMP have full crossfade; Oto has fade-in/out on pause/resume. Needs a custom mixing path on top of ExoPlayer (two-player crossfade or audio processor). Include fade-on-pause/seek as the cheap first step.

Source: https://alternativeto.net/feature/crossfading

### [MUST] ReplayGain / volume normalization

Poweramp, AIMP (ReplayGain or peak-based), foobar2000 mobile (full RG playback AND scanning), Gramophone (ReplayGain 2.0) all normalize loudness across tracks. MusicViz should read RG2 tags (track+album gain, R128 for opus) and offer an on-device scanner for untagged files — foobar2000-style scanning is a differentiator within the must.

Source: https://alternativeto.net/feature/replay-gain

### [MUST] Android Auto support

A hard purchase requirement for a large cohort — How-To Geek's 2026 tester made 'works with Android Auto' a filter criterion; Poweramp, Musicolet, AIMP, Oto, BlackPlayer EX all support it (Winamp shipped CarPlay at launch). MusicViz already uses ExoPlayer, so a MediaLibraryService/MediaBrowser tree (library, playlists, queue) gets most of the way. Without it the app is disqualified for commuters.

Source: https://www.howtogeek.com/i-tested-the-best-personal-music-players-on-android-and-this-is-the-one-im-sticking-with/

### [MUST] Sleep timer (time-based AND track-count, with fade-out)

Universal: Poweramp, BlackPlayer EX, Oto, Musicolet — which differentiates with TWO timer types (minutes and number-of-songs) plus finish-current-track option. Cheap to build, absence is repeatedly noticed in reviews.

Source: https://krosbits.in/musicolet/

### [MUST] Home-screen widgets + rich lockscreen/notification controls

Poweramp ships customizable widgets; Musicolet markets 'stunning widgets' and a lockscreen showing queue and lyrics; BlackPlayer EX and Oto include widgets. MusicViz's crystal-glass design language could make the best-looking widgets on the platform — ship at least 2 sizes (compact + artwork) using Glance, plus verified MediaStyle notification with seek.

Source: https://krosbits.in/musicolet/

### [MUST] Backup/restore: M3U playlist import/export + settings backup

doubleTwist auto-backs-up playlists as .m3u, Pulsar exports playlists as an M3U zip, Samsung Music has one-tap backup/restore, Poweramp exports/imports EQ presets. For a paid app this is trust infrastructure: users must survive phone migration without losing playlists, favourites, play history, EQ and scene presets. Ship M3U/M3U8 import (auto-import from a folder) and export, plus a single-file full backup (settings + playlists + presets). Note PlayerPro's cautionary tale: use SAF so exports land in user-visible storage.

Source: https://playlistsync.app/

### [MUST] Library completeness: sort options, album-artist/genre/composer views, grid/list toggle, search on every screen

Musicolet puts a quick-search box on every list and offers multiple sort orders for songs/albums/artists/genres/folders; Gramophone does natural sorting, list+grid views and genre/date browsing; Poweramp has deep library views. Audit MusicViz's library tab against this: album-artist (critical for compilations), genre, composer, year, recently-added, most/least-played, and per-list sort persistence.

Source: https://krosbits.in/musicolet/

### [MUST] Headset & Bluetooth polish: configurable buttons, resume-on-connect, AVRCP metadata

Musicolet advertises earphone controls (multi-click actions) and customizable previous-button behavior; Poweramp exposes Bluetooth controls, codec handling and resume-on-connect. These invisible behaviors decide daily-driver retention. Verify MusicViz handles: play on BT/wired connect (opt-in), pause on disconnect, correct AVRCP title/art on car units, multi-press headset actions.

Source: https://powerampapp.com/

### [SHOULD] Chromecast output

Poweramp, Oto Music, Neutron (casts WITH its DSP applied), and Symfonium (Chromecast + UPnP/DLNA + Kodi, casting local files) support casting; even the projectM Android app casts visuals to TV. For MusicViz the killer version is casting the VISUALIZER+audio to the TV — it already has an external-display renderer, so a Cast path (audio via Cast, or full scene via Remote Display/screen mirroring guidance) both closes the parity gap and showcases the moat.

Source: https://symfonium.app/android-music-player-sonos-chromecast-dlna/

### [SHOULD] Last.fm / ListenBrainz scrobbling

Long-tail but vocal audience; alternativeto tracks it as a named feature across players, and Symfonium's most-requested-features board shows sustained demand for local-file scrobbling. Local-library listeners are disproportionately scrobblers. Implement native Last.fm + ListenBrainz submission with offline cache, or at minimum broadcast the Simple Last.fm Scrobbler / Pano Scrobbler intent API (near-zero effort).

Source: https://support.symfonium.app/t/add-scrobbling-feature-when-playing-local-files-for-listenbrainz-last-fm/11888

### [SHOULD] Smart playlists (rule-based, auto-updating)

Poweramp has smart playlist creation, AIMP generates smart playlists from folder structure, Symfonium's rule engine (filters on metadata, ratings, playback history, always up to date) is a top reason users pay for it. MusicViz already tracks favourites and history — rules like 'added last 30 days', 'played > N times', 'never played', 'genre = X AND year > Y' are mostly queries over data it already has.

Source: https://support.symfonium.app/t/smart-playlists/327

### [SHOULD] Bulk tag editing (multi-select)

MusicViz has per-track tag editing; Musicolet differentiates with bulk tag editing of many songs at once plus advanced multi-select with invert. Album-level fixes (artist misspellings, genre assignment, artwork for whole album) are the actual use case — extend the existing editor to multi-select album/artist/folder scope.

Source: https://krosbits.in/musicolet/

### [SHOULD] Hi-res output, USB DAC support, wider format support (DSD/APE/CUE)

Poweramp's headline is 24/32-bit 96-384kHz output, USB DAC direct drivers, DSD64-1024; Neutron's whole pitch is its 32/64-bit engine bypassing the OS resampler; AIMP wins on rare formats (APE, MPC, CUE sheets, tracker modules). This is why audiophiles pay. MusicViz on stock ExoPlayer resamples to the AudioTrack default — offer float/hi-res output where the device supports it, and add APE/CUE. Full bit-perfect is a large lift; treat DSD as 'could'.

Source: https://powerampapp.com/

### [SHOULD] Per-device EQ profiles + AutoEQ preset import

Poweramp assigns EQ presets per output device (speaker/wired/each BT device) and its standalone Equalizer app's AutoEQ compatibility (headphone-specific correction curves) is widely praised. MusicViz has an EQ already — auto-switching presets when output changes, plus importing AutoEQ GraphicEQ text files, converts it from checkbox to selling point.

Source: https://en.todoandroid.es/Poweramp-equalizer--the-vital-tool-for-audio-lovers/

### [SHOULD] Social-format video export: vertical 9:16 / 1:1 presets, 4K60, quality tiers

Specterr exports up to 4K 60fps and sells vertical/square formats for TikTok/Shorts/Reels; Avee's whole Pro market is exporting visualizer videos for YouTube/TikTok. MusicViz already records mp4 — add aspect-ratio presets (16:9, 9:16, 1:1), resolution/fps tiers, and estimated file size. This turns the existing exporter into the mobile Specterr and is the highest-leverage visualizer investment.

Source: https://specterr.com/

### [SHOULD] Text/logo/artwork overlay layer + lyric-video mode in scenes and exports

The Trap Nation/Avee/Specterr formula is visualizer + album art disc + artist/track text + background image/logo; Specterr also sells lyric videos. MusicViz has scenes and synced lyrics but no compositing layer for branding. Add an overlay editor (track title/artist auto-text, user logo/image, album art element with beat-reactive scale) usable live and in exports.

Source: https://aveeplayer.com/

### [SHOULD] In-app community preset gallery / marketplace

Synesthesia ships 80 scenes plus a Scene Marketplace; Avee's longevity comes from its template ecosystem (Velosofy, VizFile sharing sites). MusicViz has shareable preset links but no discovery surface — an in-app browsable gallery (featured/trending presets, one-tap install, submit-your-own) compounds the existing link system into a retention loop.

Source: https://getsynesthesia.com/features

### [SHOULD] Word/syllable karaoke lyrics + TTML/SRT support

Gramophone (free, open source) supports LRC, TTML and SRT with word/syllable karaoke highlighting — the new bar for lyric display. MusicViz has line-synced lyrics; word-level timing (enhanced LRC/TTML) plus an online lyrics fetcher (Oto downloads lyrics in-app; LRCLIB is the common source) upgrades an existing strength.

Source: https://play.google.com/store/apps/details?id=org.akanework.gramophone&hl=en_US

### [COULD] Multiple simultaneous queues

Musicolet's signature feature ('the only Android player with multiple queues', up to 20) and the top reason its 20M users keep it. A 2-3 queue version (e.g. 'main + discovery') would be a rare paid-app implementation, but it's a workflow luxury, not a blocker.

Source: https://krosbits.in/musicolet/

### [COULD] Internet radio + podcasts

AIMP streams internet radio and 20,000+ podcasts; Winamp's relaunch bundles a Hotmix radio tab; Poweramp added an internet radio service. Off-mission for a local player+visualizer except one angle: radio streams are great visualizer fuel and MusicViz can already capture system audio, so a simple stream-URL opener is a cheap 'could'.

Source: https://gzmato.com/blog/post/aimp-2025-review-best-free-music-player-windows-android

### [COULD] Network/cloud sources: UPnP/DLNA, WebDAV, Subsonic/Jellyfin/Plex, Google Drive

Symfonium's entire business is playing from Plex/Emby/Jellyfin/Subsonic/cloud with offline cache; foobar2000 mobile plays from UPnP/FTP/WebDAV; Winamp mobile added Google Drive. Big engineering surface — only pursue if MusicViz targets the self-hosted crowd; a WebDAV/Subsonic client would be the minimal viable slice.

Source: https://play.google.com/store/apps/details?id=app.symfonik.music.player

### [COULD] Edge-lighting / always-on-display ambient mode

Muviz Edge built a large free+IAP business on visualizing around screen edges and AOD clock-visualizer screensavers over any app's audio. MusicViz already captures system audio and renders live wallpaper — an AOD/screensaver mode (dimmed, burn-in-safe scene while docked/charging) is an adjacent surface competitors can't match in visual quality.

Source: https://play.google.com/store/apps/details?id=com.sparkine.muvizedge&hl=en

### [COULD] MIDI/OSC control + Shadertoy/ISF shader import (VJ tier)

Synesthesia's pro tier sells MIDI-mappable controls, OSC in/out, and importing Shadertoy/ISF shaders. MusicViz already has LFO modulation, performance takes and second-display output — MIDI controller support (USB/BLE MIDI is native on Android) plus user shader import would make it the only pocket VJ rig, monetizable as a pro add-on.

Source: https://getsynesthesia.com/features

### [COULD] Android TV / Google TV build

projectM ships a dedicated Android TV visualizer app and Chromecast support; no quality music-player+visualizer exists on TV. MusicViz's scenes are living-room content — a TV build (or at least verified Cast) opens a second distribution channel.

Source: https://play.google.com/store/apps/details?id=com.psperl.projectMTV&hl=en-US

### [COULD] Album-art-derived palettes + artist metadata auto-fetch

Muviz Edge links lighting palettes directly to album art; Oto auto-fetches artist images and biographies. MusicViz's palette system could auto-extract a palette from the current track's artwork (Palette API) so every song themes the scene — small effort, very visible. Artist bio/image fetch is cosmetic parity.

Source: https://www.sparkine.com/muviz-edge/

### [COULD] Small utilities: ringtone setting, share track, full-screen artwork viewer

Poweramp offers set-as-ringtone; Musicolet has direct song sharing and album-art zoom/save-to-gallery. Individually trivial, collectively part of 'feels finished'. Batch these into a polish pass.

Source: https://audio.mediaio.net/play/poweramp-music-player.html

### [SHOULD] MOAT: On-device projectM + 57 GPU scene engine inside a real player

No competitor combines both halves: Poweramp's visualization is limited MilkDrop v1/v2 preset rendering bolted onto a player; the projectM app is a visualizer with no player; Avee's visuals are simple 2D spectra. MusicViz's 57 scenes (fluid sim, cymatics, hyperspace, curl flow) with beat/key/chord-driven modulation is desktop-Synesthesia-class on mobile. Protect and market as the headline differentiator; the parity work above is what removes the excuse not to switch.

Source: https://github.com/maxmpz/powerampapi/blob/master/poweramp_vis_presets_example/readme.md

### [SHOULD] MOAT: Reliable on-device video export (visuals+audio)

Avee is the only mobile competitor exporting visualizer videos, and its export is notoriously flaky (freezes mid-export, silent/corrupted files per user reviews); Specterr is web-only, watermarked and daily-capped on free tier. A rock-solid, watermark-free export is a purchase driver for musicians/creators — invest in reliability testing and market it against Avee's weakness.

Source: https://www.smileblogs.com/article/2220

### [COULD] MOAT: System-audio capture ('visualize anything'), live wallpaper, second display, performance takes, safety limiter

System-audio visualization is Muviz Edge's entire product but MusicViz does it with full scenes; live wallpaper matches projectM; second-display output and recordable parameter automation (performance takes) exist nowhere else on mobile; the photosensitivity limiter is unique in the entire category (desktop included) and is both an accessibility credential and a store-listing differentiator. Keep, document, and market these — they justify the price once table-stakes parity is reached.

Source: https://play.google.com/store/apps/details?id=com.psperl.projectM&hl=en_US

## User demand research (Reddit, forums, review patterns)

Research across r/androidapps threads (via pullpush archive, since reddit.com blocks direct fetch), the Poweramp forum, projectM's GitHub tracker, r/milkdrop, r/vjing, Spotify's Idea Exchange, and 2025/26 player roundups shows a consistent picture. On the player side, users' loudest wishes are Android Auto, Chromecast, real playlist UI with working M3U import/export, Musicolet-style multi-queue handling, smart/rating-weighted shuffle, multi-artist tag support, listening stats, scrobbling, auto-fetched synced lyrics, and folder-first browsing. The loudest deal-breakers are subscriptions (one-time purchase is a selling point MusicViz already has), ads, cloud/internet requirements (Musicolet's zero-internet-permission is its single most-praised trait), cluttered/steep UI (Poweramp v3's chief criticism — a direct risk for MusicViz's 57-scene depth), crashes (projectM Android's dominant review complaint), and battery drain from visualizers/live wallpapers. On the visualizer side, the clearest unmet demands are: visualize streaming audio (a decade of 'visualizer for Spotify' requests — MusicViz's system-audio capture is the answer and should lead marketing), put visuals on the TV (cast/Google TV app/screensaver mode — projectM TV is loved for exactly this), lock-screen/AOD visuals (Muviz Edge's whole business), curated 'good presets' + preset persistence, smooth beat-synced transitions, an on-device preset editor, watermark-free WYSIWYG video export (Avee's weak spot), and party/VJ conveniences like BPM sync and remote control. MusicViz already covers several wishes (system-audio capture, live wallpaper, export, photosensitivity limiter) — the gaps are distribution surfaces (Auto, cast/TV, AOD), playlist UI, stability/battery discipline, and progressive-disclosure UX.

### [MUST] Android Auto support

Repeatedly cited as a hard deal-breaker in player-comparison threads — the HowToGeek 6-player test rejected otherwise-good apps solely for lacking it, and Musicolet's absence from Android Auto (certification requires Play distribution + media-app review) is its most-cited gap. MusicViz is a paid Play app already using ExoPlayer/MediaSession, so a MediaBrowserService + Auto certification is achievable and would remove the #1 objection local-player users voice.

Source: https://www.howtogeek.com/i-tested-the-best-personal-music-players-on-android-and-this-is-the-one-im-sticking-with/

### [MUST] Chromecast / cast audio (and visuals) to TV

Chromecast was Poweramp's most-demanded feature for ~3 years (requested 2016, shipped 2019) and Retro Music gets praised specifically for having it. For MusicViz this is double-value: cast audio like a normal player, and cast the visualizer output to the living-room TV — the thing party/ambient users say they want most.

Source: https://forum.powerampapp.com/topic/10592-chromecast/

### [MUST] Playlist UI with reliable M3U import/export

MusicViz has playlists backend-only with no UI — this is table stakes in every 'best player' thread. Note the specific complaint pattern: Poweramp users report playlist export 'failing miserably' into scoped-storage restricted folders. Ship a playlist UI plus import/export that handles Android scoped storage correctly (SAF folder picker, verify written files).

Source: https://www.techjunkie.com/poweramp-android/

### [SHOULD] Musicolet-style queue management (multiple queues, queue protection)

Musicolet's multi-queue system is the single most-praised feature in local-player threads ('closest thing to a perfect music player'), and the mirror complaint appears constantly: accidentally tapping a track wipes the current queue. MusicViz already has a queue — add 'add to queue vs play now' guards and consider named/multiple queues.

Source: https://www.reddit.com/r/androidapps/comments/1jhxj89/

### [SHOULD] Smart shuffle and rating-weighted playlists

In the r/androidapps open feature-request thread (140 upvotes), weighted playlists ('a 5-star song plays 5x as often as a 1-star song') and better shuffle were top asks; Poweramp's shuffle algorithm is a named complaint. MusicViz has favourites + history — a 'true random' toggle plus favourite/recency-weighted shuffle is cheap and differentiating.

Source: https://www.reddit.com/r/androidapps/comments/ph1iue/

### [SHOULD] Multi-artist tag separators and richer sorting

The #1 concrete request in the r/androidapps feature-request thread: split 'Artist A; Artist B' tags so both artists appear in the library, plus sorting by year/album-artist/genre. MusicViz already has a tag editor; adding configurable tag separators and more sort keys addresses a persistent power-user complaint (also: M4A tag-reading bugs are a recurring gripe).

Source: https://www.reddit.com/r/androidapps/comments/ph1iue/

### [SHOULD] Listening statistics (play counts, permanent history, stats dashboard)

Requested in feature-request threads (play counts per song/album, 'permanent history', app stats dashboard) and a recurring 'looking for a player that tracks listening' post genre. MusicViz already records history — surfacing a Wrapped-style stats screen is low-cost, high-delight, and shareable.

Source: https://www.reddit.com/r/androidapps/comments/ph1iue/

### [SHOULD] Auto-fetched synced lyrics

Built-in lyrics downloading (LRCLIB-style) is called out as a praised feature in player threads, and lyrics-forward minimal players (e.g. Lotus in the r/androidapps 'Best Apps of May 2025' post, 273 upvotes) win recommendations on it. MusicViz has a synced lyrics panel — adding auto-fetch closes the loop; lyrics rendered inside the visualizer would be unique.

Source: https://www.reddit.com/r/androidapps/comments/1kkw2fk/

### [SHOULD] Folder-first browsing and folder playlists

AndroidPolice's Musicolet piece calls out apps 'adding AI recommendations and cloud syncing before perfecting basic functions such as folder sorting'; Oto Music gets dinged for paywalling folder shortcuts. Folder browse/exclude, and 'make playlist from folder', are baseline expectations for the offline-library crowd MusicViz targets.

Source: https://www.androidpolice.com/dont-settle-for-mediocre-android-music-player/

### [COULD] Last.fm scrobbling + PC/NAS library reach

Scrobbling and desktop-sync are recurring 'must have' mentions in player-recommendation threads (Symfonium's rise is built on network/NAS libraries; Poweramp forum has standing requests for Subsonic/cloud sources). For MusicViz: a simple Last.fm/ListenBrainz scrobbler is cheap goodwill; NAS/Subsonic is a bigger bet to defer.

Source: https://forum.powerampapp.com/forum/4-poweramp-feature-requests/

### [COULD] Audiophile output options (hi-res/USB DAC, parametric EQ)

Poweramp's most-quoted praise is EQ quality ('the only player where I can hear a difference with the EQ off versus on') and its forum's top asks are hi-res/bit-perfect output and finer EQ steps; Neutron/UAPP exist purely for this niche. MusicViz shouldn't chase bit-perfect, but a parametric EQ upgrade and explicit hi-res output labeling would defuse the audiophile objection.

Source: https://forum.powerampapp.com/topic/29581-a-wish-to-add-new-features-and-improve-the-sound-quality/

### [MUST] Deal-breaker: subscriptions — one-time purchase is a moat

Subscription fatigue is loud and mainstream ('almost everything gets locked behind unending recurring payments'); Avee Player's move to subscription generated visible resentment. MusicViz's one-time purchase is a top-3 marketable attribute — say it in the Play listing's first line and never undermine it with subscription add-ons.

Source: https://www.tomsguide.com/phones/most-of-my-favorite-apps-are-ditching-one-time-payments-for-subscriptions-this-is-a-problem

### [MUST] Deal-breaker: ads in the experience

Every roundup dings ad-carrying free tiers (Pi Music Player, BlackPlayer free) and 'completely ad-free' is a headline praise point for winners (Oto). MusicViz is paid so this is mostly a messaging point — but it also means the trial/demo strategy must never involve ads.

Source: https://www.howtogeek.com/i-tested-the-best-personal-music-players-on-android-and-this-is-the-one-im-sticking-with/

### [MUST] Deal-breaker: cloud/internet requirements and privacy creep

Musicolet's single most-praised trait across Reddit and press is zero internet permission — no analytics, no telemetry. Users explicitly reject 'AI recommendations and cloud syncing' bloat. MusicViz needs internet for preset links/updates, but should offer a clearly-documented offline mode, minimal permissions, and a 'no telemetry / no account' privacy stance in the listing.

Source: https://www.androidpolice.com/dont-settle-for-mediocre-android-music-player/

### [MUST] Deal-breaker: cluttered UI / steep learning curve (the Poweramp trap)

Poweramp v3 — the category leader — is now routinely described as 'cluttered', 'dated', with unintuitive navigation ('a learning curve to knowing what action triggers what'); users on r/androidapps reject it as overcomplex despite its power. MusicViz's 57 scenes + LFOs + per-scene params carry the same risk: default to a curated simple surface (randomizer, palettes, a few hero scenes) with progressive disclosure into deep customization.

Source: https://www.reddit.com/r/androidapps/comments/1jhxj89/

### [MUST] Deal-breaker: crashes and instability (projectM's Achilles heel)

projectM Android's dominant review/issue themes are crashes, memory leaks, and preset errors (GitHub #900, #825, #407: crashing on resize, random exits, certain presets killing the app), plus 'doesn't save presets' and permission prompts that don't stick. Since MusicViz ships projectM presets and heavy GPU work, per-preset crash quarantine, GL context loss recovery, and low-RAM device testing are competitive necessities.

Source: https://github.com/projectM-visualizer/projectm/issues/900

### [MUST] Deal-breaker: battery drain and heat from visuals

Live wallpapers are 'videos constantly playing' that users abandon over battery ('people get pretty upset if a live wallpaper drains half of their battery in an hour'); projectM 'requires a lot of processing power'; MakeUseOf flags Visualisator's wallpaper mode for battery drain. MusicViz needs a visible battery-saver mode (FPS cap, resolution scale, pause-when-hidden), AMOLED-friendly dark scenes, and honest defaults for the wallpaper.

Source: https://xdaforums.com/t/do-live-wallpapers-on-android-take-up-a-lot-of-battery.2271677/

### [MUST] Deal-breaker: library scanning, scoped storage and metadata bugs

Recurring complaints across threads: M4A tags misread, SD-card corruption handling, playback stopping until app restart, exports written to inaccessible restricted folders. These 'boring' reliability items are what flip 5-star reviews to 1-star; a robust SAF-based scanner and tag reader is invisible until it's missing.

Source: https://www.reddit.com/r/androidapps/comments/1jhxj89/

### [SHOULD] Deal-breaker: watermarks and paywalled/broken export (Avee's weak spot)

In the visualizer-video niche, Avee Player draws complaints for watermarks persisting even after paying and for exports that don't match in-app audio (EQ not applied to exported video). MusicViz's video export must be watermark-free and strictly WYSIWYG (visuals + audio FX exactly as heard), and should add vertical 9:16 export for Shorts/TikTok — the main use-case pulling users to Avee.

Source: https://free-apps-android.com/avee-music-player/

### [SHOULD] Deal-breaker: dated design

'Dated design' is a recurring kill-shot in reviews (BlackPlayer, even Poweramp's skin ecosystem). Winners are praised for modern Material You looks (Oto, Retro). MusicViz's 'luminous crystal glass' language is a differentiator — but it must also respect platform conventions (predictable back gesture, standard nav) since Poweramp gets dinged for unintuitive back behavior specifically.

Source: https://www.howtogeek.com/i-tested-the-best-personal-music-players-on-android-and-this-is-the-one-im-sticking-with/

### [MUST] Visualizer wish: visualize Spotify/streaming audio (system-audio capture)

A decade of demand: r/androidapps 'visualizer with bars that will work with Spotify' threads, Muviz's 235-upvote launch built on visualizing any app's audio, and perennially recurring Spotify Idea Exchange requests for a built-in visualizer that Spotify never ships. MusicViz already does system-audio capture — this should be the lead marketing message ('visualize anything playing: Spotify, YouTube, games'), with reliability work on the AudioPlaybackCapture path since apps can opt out (fall back to mic gracefully).

Source: https://www.reddit.com/r/androidapps/comments/482mji/

### [MUST] Visualizer wish: visuals on the TV — cast, Google TV app, screensaver mode

projectM's Android TV app is beloved precisely as ambient living-room art ('my TV stopped feeling like hardware I barely touched') including Daydream/screensaver integration; VJ-lite apps (V4M, GoVJ, 100VJ) all sell AirPlay/HDMI party output; vizz.fm markets 'put it on the big screen' for parties. MusicViz has external-display support — extend to Chromecast streaming of the GL output and/or a companion Google TV build with a screensaver mode and a 'party mode' (auto-cycling curated scenes, no UI chrome).

Source: https://www.makeuseof.com/use-projectm-music-visualizer-smart-tv-app/

### [SHOULD] Visualizer wish: lock screen / AOD / edge visuals

Muviz Edge's entire successful product is a visualizer on the navbar, screen edges, and Always-On Display; XDA history shows persistent appetite for ambient-display music visuals. A low-power AOD/lock-screen visual mode (dim, slow, AMOLED-black scenes) would let MusicViz visuals live beyond the open app — no full-featured player currently offers this.

Source: https://play.google.com/store/apps/details?id=com.sparkine.muvizedge

### [SHOULD] Visualizer wish: curated 'good presets' + preset persistence

projectM's community explicitly asked for a curated list of good default presets (GitHub #169) because thousands of mediocre .milk files bury the gems; Play reviewers complain projectM 'does not save presets' and that the desktop's ~60k preset library isn't accessible on Android. MusicViz should ship a hand-curated hero rotation per music genre/energy, persist user preset state flawlessly, and let users import .milk packs.

Source: https://github.com/projectM-visualizer/projectm/issues/169

### [SHOULD] Visualizer wish: smooth beat-synced transitions between scenes

Shader-based smooth blending between presets was a requested-and-shipped projectM feature (GitHub #727), and MilkDrop 3's headline features are live blending/double-preset mixing and beat-reactive hardcuts — the things r/milkdrop celebrates. MusicViz should crossfade between its 57 scenes and offer beat-quantized auto-switching ('changes on the drop'), which reviewers consistently read as polish.

Source: https://github.com/projectM-visualizer/projectm/issues/727

### [COULD] Visualizer wish: party/VJ controls — BPM sync, remote control, tempo lock

projectM's tracker has standing asks for a remote-control API (frontend-sdl-cpp #74) and Ableton Link integration (#451); r/vjing shows hobbyists cobbling together TouchDesigner/Unreal rigs and valuing open, controllable tools. A lightweight 'party remote' (phone controls visuals on the TV/second display) plus tap-tempo/BPM lock would make MusicViz the easy alternative to pro VJ stacks for house parties.

Source: https://github.com/projectM-visualizer/frontend-sdl-cpp/issues/74

### [COULD] Visualizer wish: on-device preset/scene editor and shareable creations

An in-app preset editor is a long-open projectM request (GitHub #220) and r/milkdrop threads seek tools with real-time preset editing; the preset-creator community (r/milkdrop preset packs, Poweramp .milk preset repos) thrives on sharing. MusicViz already has parameter presets + shareable links — a deeper 'remix this scene' editor plus a community gallery would convert its customization depth into network effects.

Source: https://github.com/projectM-visualizer/projectm/issues/220

### [COULD] Visualizer wish: photosensitivity safety as a surfaced differentiator

MakeUseOf explicitly warns that Visual Sounds' fast flashing requires 'caution for photosensitive conditions' — reviewers do notice this. MusicViz already ships a photosensitivity limiter; surface it in onboarding and the Play listing (family/party safe), since no competitor markets one.

Source: https://www.makeuseof.com/best-music-visualizers-android/

### [COULD] Visualizer wish: LED/ambient-light output (sACN/Art-Net to WLED)

r/milkdrop requested MilkDrop drive ESP32/WLED LED strips via E1.31/Art-Net — the party crowd wants room lighting to follow the visuals. Niche but zero competitors on Android; a simple 'sync dominant color/beat to WLED' UDP output would be a cult feature for the exact demographic that buys visualizer apps.

Source: https://www.reddit.com/r/milkdrop/comments/1kjksk1/

### [SHOULD] Visualizer wish: Winamp/MilkDrop nostalgia as an acquisition hook

The MilkDrop revival economy is real: Webamp, vizz.fm ('Winamp Visualizer — MilkDrop, Modernized'), MilkDrop 3, and projectM all trade on Winamp nostalgia, and Poweramp forum users literally call Poweramp 'just MilkDrop 2.x for Android'. MusicViz already embeds projectM/MilkDrop presets — target 'winamp visualizer android' / 'milkdrop android' search terms and consider a retro spectrum-bars overlay style (Poweramp's PowerMilk bars were popular enough that projectM users requested them, GitHub #720).

Source: https://vizz.fm/winamp-visualizer/

## Open-source prior art & licence notes

Surveyed 10+ open-source Android music players, the core visualizer/creative-coding ecosystem, and concrete playback/platform techniques for MusicViz. Key legal picture: every serious FOSS Android player (Auxio, RetroMusic, Metro, Gramophone, Vinyl, Odyssey, Music Player GO) is GPL-3.0 and Symphony is AGPL-3.0 — all are idea-mines only, zero code reuse for a closed-source paid app; Namida uses a custom EULA. Safely reusable (with attribution) MIT sources exist on the visualizer side: butterchurn, PavelDoGreat's WebGL-Fluid-Simulation (which MusicViz's fluid sim likely derives from — attribution notice is mandatory), and Vidvox's ISF-Files shader collection. Three compliance items are urgent for a paid app: (1) Shadertoy's default licence is CC BY-NC-SA 3.0 — non-commercial — so the 22 fragment-shader styles need a provenance audit; (2) projectM is LGPL-2.1 and must stay dynamically linked with licence text shipped; (3) MilkDrop community preset packs sit on an informal 'assumed public domain, author can request removal' basis, worth documenting. Top feature/architecture borrowings: Auxio's taglib-based library indexer and ReplayGain-via-AudioProcessor pattern, Media3 MediaLibraryService for Android Auto (androidx/media demos are Apache-2.0 and copyable — UAMP is archived as of Jan 2026), RetroMusic/Namida playlist and queue UX to fill MusicViz's missing playlist UI, Gramophone's word-level karaoke lyrics, LRCLIB auto-lyrics fetching, Amplituda-style waveform seekbar, DreamService screensaver as a cheap sibling to the existing live wallpaper, and a butterchurn-based Cast Web Receiver as the only realistic Chromecast path for GL content.

### [SHOULD] Auxio — the architecture reference for local-library players (GPL-3.0, ideas only)

Kotlin/Media3 player whose standout is a custom music loader: it parses tags natively via taglib (C++ via NDK) instead of trusting MediaStore, giving correct disc numbers, multi-artist, release types, precise dates, and SD-card-aware folder handling — exactly the metadata quality a paid player is judged on. Also uses a patched Media3 fork for custom playback features and implements automatic gapless playback. Study its loader design and unified artist model for MusicViz's track library; GPL-3.0 means patterns only, no code.

Source: https://github.com/OxygenCobalt/Auxio

### [SHOULD] ReplayGain via a custom ExoPlayer AudioProcessor (reimplement, don't copy)

Media3/ExoPlayer has no built-in ReplayGain (see google/ExoPlayer#9796). The proven pattern is Auxio's ReplayGainAudioProcessor.kt (app/src/main/java/org/oxycblt/auxio/playback/replaygain/): a BaseAudioProcessor that reads RG tags (MP3/FLAC/OGG/OPUS/MP4), converts dB gain to a PCM multiplier, and scales samples with clipping clamps. MusicViz already has an audio-FX pipeline, so slotting an RG processor in is low-effort and a frequently-demanded feature for local players. Implement from the ReplayGain 2.0 spec — the Auxio source is GPL.

Source: https://github.com/google/ExoPlayer/issues/9796

### [MUST] Shadertoy provenance audit of the 22 fragment-shader styles (legal must)

Shadertoy's default licence is CC BY-NC-SA 3.0 — non-commercial, share-alike — per shadertoy.com/terms; only shaders whose authors explicitly declare a permissive licence in the shader code may inform a paid app. Since MusicViz sells for money on Play, audit every fragment-shader scene for Shadertoy-derived code; anything traceable to a default-licensed shader must be rewritten from first principles or replaced with ISF/MIT sources. This is the single biggest licence exposure identified.

Source: https://www.shadertoy.com/terms

### [MUST] projectM LGPL-2.1 compliance check

projectM's core is deliberately LGPL-2.1 'to permit closed-source applications to use it as a shared library'. For MusicViz that means: keep libprojectM dynamically linked (never statically linked into the app binary on Android without meeting LGPL relink terms), ship the LGPL text + attribution in the licences screen, and publish any modifications made to the library itself. Verify the current integration meets this — it is cheap now and expensive later.

Source: https://github.com/projectM-visualizer/projectm

### [MUST] MilkDrop preset packs: informal 'assumed public domain' — document your position

The Cream of the Crop pack (projectM's default, ~10k presets) ships a LICENSE.md that is not a real licence: 'each preset author holds the full copyright' but the packs are 'safe to assume... in the public domain' after decades of free distribution, with a takedown-on-request provision. For a paid app this is tolerable but should be documented: record which pack/version ships, keep the attribution file, and honor removal requests. Same caution applies to butterchurn-presets (repo is MIT but the underlying community presets carry the same informal status).

Source: https://github.com/projectM-visualizer/presets-cream-of-the-crop

### [MUST] WebGL-Fluid-Simulation (MIT) — confirm attribution and mine the extras

Pavel Dobryakov's GPU Navier-Stokes sim is MIT-licensed, so derivation is fine — but MIT requires retaining the copyright/permission notice: make sure MusicViz's licences screen credits it if the fluid scene derives from this code. Then mine what the repo does beyond basic advection: dye dissipation curves, bloom, and 'sunrays' radial light-shaft post-processing, all cheap wins for the fluid scene's look on mobile GPUs.

Source: https://github.com/PavelDoGreat/WebGL-Fluid-Simulation

### [MUST] Playlist UI: borrow RetroMusic + Namida patterns to close MusicViz's gap

MusicViz has playlist backend but no UI — the fastest reference set: RetroMusic (GPL-3.0) for create/edit/import with drag-to-reorder and playlist artwork; Namida for auto-generated smart playlists ('Most Played' by time range, history with configurable listen thresholds — a play only counts after N seconds/percent). Ship: manual playlists with drag-reorder, M3U import/export, and history/most-played smart playlists driven by the existing history feature. Ideas only — RetroMusic is GPL, Namida is a custom EULA.

Source: https://github.com/RetroMusicPlayer/RetroMusicPlayer

### [MUST] Android Auto via Media3 MediaLibraryService — use androidx/media demos (Apache-2.0, code copyable)

UAMP was archived Jan 9 2026; the living, Apache-2.0 reference is the androidx/media repo's session demos showing MediaLibraryService + MediaSession for Auto/Wear/TV browsing trees. Auto support is table stakes for a paid Play music player (RetroMusic, Vinyl, Auxio all have it) and Apache-2.0 means you can lift the service scaffolding directly. Expose library as browsable tree (albums/artists/playlists/favourites), and note visuals are irrelevant in the car — this is purely the player half.

Source: https://github.com/androidx/media

### [SHOULD] Gramophone — modern minimal Media3 player with word-level karaoke lyrics (GPL-3.0, ideas only)

Kotlin, Material 3/Monet, Media3-as-git-submodule (a clean way to carry playback patches, cf. Auxio's fork approach). Its lyric engine is the differentiator: LRC, TTML and SRT with word/syllable-level karaoke synchronization — a concrete upgrade path for MusicViz's synced lyrics panel (line-level today, presumably). Also full ReplayGain 2.0 and natural sorting. GPL-3.0: study the TTML/karaoke UX, implement independently.

Source: https://github.com/FoedusProgramme/Gramophone

### [SHOULD] Namida — queue and stats UX worth stealing (custom EULA, strictly ideas only)

Flutter player with the most inventive UX in the space: waveform seekbar animated by audio peaks, 'insert after latest inserted' queue semantics (sequential add-next that doesn't reverse order), repeat-track-N-times, persistent queue sessions, listen-threshold-based history, and related-track recommendations from listening history. Licence is an EULA permitting personal use/contribution only — highest contamination risk in the list, so treat as a UX catalogue, never open the code side-by-side while writing yours.

Source: https://github.com/namidaco/namida

### [SHOULD] Waveform seekbar via Amplituda (Apache-2.0 + LGPL FFmpeg — usable directly)

To match Namida's waveform seekbar legally: Amplituda is an Android library (Apache-2.0, bundling LGPL-2.1 FFmpeg binaries) that extracts amplitude arrays from audio files fast (~1s for a 3.5-min track), with caching and compression options. Because it is Apache-licensed it can be used as a dependency in a closed-source paid app (keep FFmpeg dynamically linked per LGPL). A waveform seekbar doubles as a natural fit for MusicViz's visual identity.

Source: https://github.com/lincollincol/Amplituda

### [SHOULD] butterchurn (MIT) — a second MilkDrop engine to study, and the Cast vehicle

MIT-licensed WebGL 2 MilkDrop implementation using AssemblyScript/WASM for per-frame equation execution. Two uses: (1) study how it compiles preset equations to shaders — informative if MusicViz ever wants preset hot-editing or a lighter-weight preset engine alongside LGPL projectM; (2) it is the practical payload for a Chromecast Web Receiver (see Cast item). MIT means code reuse is genuinely allowed with attribution — rare in this space.

Source: https://github.com/jberg/butterchurn

### [COULD] Chromecast for GL content: custom CAF Web Receiver running butterchurn

There is no way to cast an OpenGL ES surface directly (Cast Remote Display API is long deprecated), so the realistic pattern is: build a custom Cast Application Framework Web Receiver page that runs butterchurn (or bespoke WebGL scenes styled like MusicViz), and stream either the audio URL or beat/FFT feature messages from the phone over the Cast media/message channels. Prior art exists (butterchurn-based apps with one-click Chromecast, e.g. xoxodin/butterchurn-visualizer ecosystem). Sell it as 'visuals on the TV' — a differentiator no closed competitor does well.

Source: https://github.com/xoxodin/butterchurn-visualizer

### [SHOULD] ISF-Files (MIT) — 200+ legally-safe shaders to adapt for new scene styles

Vidvox's ISF-Files repo: 200+ ISF 2.0 generators and filters under MIT — the legally safe counterpart to Shadertoy for sourcing new fragment-shader scene styles and post-FX (kaleidoscopes, feedback, color grading). ISF is plain GLSL fragment shaders plus a JSON block describing uniforms — trivially convertible to MusicViz's GLES3 scene format, and the declared-parameters model maps directly onto MusicViz's per-scene parameter customization system.

Source: https://github.com/Vidvox/ISF-Files

### [COULD] Chladni/cymatics sims — physical-particle upgrade for the cymatics scenes

MusicViz's 11 cymatics substyles are presumably analytic (plate-mode nodal patterns). Repos worth studying for a physically-driven variant where 'salt' particles migrate to nodal lines over time: schroffl/chladni-simulation (WebGL spring-mesh plate), PettaBoy/Cymatics-Simulator-Chladni (live demo), flutomax/ChladniPlate2 (multi-waveform superposition with amplitude/frequency-ratio/phase params — good parameter-space ideas), plus the Shadertoy 'chladni emulator cymatics salt' (3tsSDr — check its licence before reading closely). Driving plate frequency from detected key/chord would be a unique tie-in to MusicViz's existing music analysis.

Source: https://github.com/schroffl/chladni-simulation

### [COULD] audioMotion-analyzer — design bar for a 'pro spectrum' scene (AGPL-3.0, do not port)

The best-looking open spectrum analyzer: octave-band modes (1/24 to full octave), log/linear/Bark/Mel frequency scales, A/B/C/D and ITU-R 468 weighting filters, LED/luminance bars, radial mode, mirroring. AGPL-3.0 — the most aggressive copyleft here, absolutely no code reuse — but its feature list is a ready-made spec for a high-end spectrum scene in MusicViz, which currently leans artistic rather than analytical. Bark/Mel scaling plus weighting filters makes spectra 'feel' right to musicians.

Source: https://github.com/hvianna/audioMotion-analyzer

### [COULD] Odyssey — artwork bulk-fetch and bookmarks patterns (GPL-3.0, ideas only)

Two borrowable ideas: (1) bulk artist/album artwork downloading from MusicBrainz + Last.fm + Fanart.tv with local caching — fixes the 'library full of gray placeholders' problem that hurts a visual-first app most; (2) bookmarks that save playlist + playback position, which makes long-form audio (mixes, live sets, audiobooks) first-class — highly relevant since visualizer users often play hour-long DJ mixes.

Source: https://github.com/gateship-one/odyssey

### [COULD] Vinyl Music Player / Phonograph lineage — smallest codebase showing RG + Auto + SD-write (GPL-3.0)

Vinyl (fork of kabouzeid/Phonograph) is the most compact codebase demonstrating the trifecta MusicViz's player half needs: ReplayGain, Android Auto, and SD-card write access for tag editing via Storage Access Framework — its issue tracker documents the SAF pain points for editing tags on removable storage, directly relevant to MusicViz's existing tag editor. Metro (MuntashirAkon/Metro, GPL-3.0) is the de-Googled RetroMusic fork — mainly useful as a map of which RetroMusic 'pro' features users value enough to fork for.

Source: https://github.com/VinylMusicPlayer/VinylMusicPlayer

### [COULD] Symphony — the only pure Jetpack Compose FOSS player (AGPL-3.0, ideas only)

Kotlin + Jetpack Compose + Material You, so architecturally the closest sibling to MusicViz's Compose UI: study its screen/navigation structure and how it keeps Compose lists fast on large libraries. Its origin feature — filename/path-based sorting — is a cheap, frequently-requested addition to any folder view. AGPL-3.0: strictly no code, patterns only.

Source: https://github.com/zyrouge/symphony

### [SHOULD] LRCLIB — free synced-lyrics auto-fetch for the lyrics panel

LRCLIB (lrclib.net) is a free, no-key API with ~3M synced lyrics; the official lrcget client (MIT) shows the matching flow (track/artist/album/duration query, LRC download, tag or sidecar storage). Wiring auto-fetch into MusicViz's existing synced lyrics panel removes the biggest friction (users don't have LRC files). Namida, Gramophone-family apps and many others already use it — proven at scale. Check API ToS for commercial-app courtesy limits, but it is explicitly free-for-everyone.

Source: https://github.com/tranxuanthang/lrcget

### [COULD] Crossfade: Media3 won't do it for you — dual-player or mixing-processor pattern

Gapless is native in Media3 (works when files carry gapless metadata), but crossfade remains an open feature request since 2021 (androidx/media#2; ExoPlayer #3438/#4414 history). Proven approaches: two ExoPlayer instances with volume ramps (what commercial players do), or a custom mixing AudioProcessor (ExoPlayer #11317 discussion). Worth adding as a queue setting — but note the interaction with MusicViz's visualizer audio tap: dual-player crossfade means two audio sessions, so the system-audio-capture path is the safer feed for visuals during transitions.

Source: https://github.com/androidx/media/issues/2

### [COULD] DreamService screensaver — third surface for visuals after live wallpaper and second display

Android's DreamService (screensaver while docked/charging, and the Android TV screensaver) is a natural, low-effort surface for MusicViz scenes: reuse the live-wallpaper renderer, add a dream.xml + service. References: googlearchive/androidtv-daydream (deprecated but shows the wiring; successor samples in github.com/android/tv), dsandler/android-daydream-samples, and AOSP DreamService source. 'Your music becomes your screensaver while charging' is a marketable bullet no competitor pushes.

Source: https://github.com/googlearchive/androidtv-daydream

### [COULD] awesome-audio-visualization + three.js demo mining list

willianjusten/awesome-audio-visualization is the curated index of the whole space — use it as the standing scouting list for new scene concepts. Standout three.js repos for techniques transferable to GLES3: tgcnzn/Interactive-Particles-Music-Visualizer (audio-synced particle choreography, based on ARKx's Coala Music work), dcyoung/r3f-audio-visualizer (clean audio-feature-to-parameter mapping architecture), Eronne/threejs-audio-visualizer (shader-driven). Check each repo's licence before borrowing code; most are MIT but verify per-repo.

Source: https://github.com/willianjusten/awesome-audio-visualization

## Design research (Pinterest/Dribbble/Mobbin, 2026 platform direction)

Design research for MusicViz's "luminous crystal glass" identity, from Pinterest/Dribbble/Behance/Mobbin pattern mining, 2026 platform-direction sources (Apple Liquid Glass backlash coverage, Material 3 Expressive docs/deep-dives, dark-first OLED guides), and customization-heavy UI references (Halide, Koala, Elastic OSC, Serum/Vital-style preset UX, projectM, Vythm, Resolume). Note: Pinterest and Dribbble block direct page fetches, so Pinterest/Dribbble pattern claims are inferred from search-result snippets, ideas-page titles, and secondary write-ups rather than rendered boards; platform and glassmorphism specs come from fully fetched articles. Convergent findings: (1) glassmorphism survives in 2026 only as a disciplined 'surgical' material — 8-16px blur, 2-3 glass surfaces per view, 1px rgba(255,255,255,0.2) borders, a rgba(0,0,0,0.3) scrim under any text, 4.5:1 contrast tested against the brightest visualizer frame, and a reduce-transparency toggle (the exact concession Apple was forced into with iOS 26.1's 'Tinted' control); (2) the strongest now-playing conventions are ambient artwork-derived color (blur+stretch art as glow, 3-color extraction palette), bottom-sheet mini-player ecosystems, peeking-artwork queue affordances, and wavy/waveform seekbars (now first-party in Android 13+/M3 Expressive); (3) Material 3 Expressive's 35-shape morphing system and spring physics are an unusually good native fit for a gem-silhouette brand; (4) for exposing 200 parameters, the proven stack is preset-first browsing with live thumbnails and search, 4-8 macro knobs per scene, XY performance pads with motion recording, randomize-with-parameter-locking, Halide-style gesture disclosure over the canvas, and search-in-settings once a screen exceeds ~15 options. Items below are ranked by priority and tied to MusicViz screens.

### [MUST] Adopt a written glass-surface spec: 8-16px blur, max 2-3 glass panels per view, 1px rgba(255,255,255,0.2) border, dark scrim under text

The single most consistent finding across glassmorphism guidance: glass reads as glass only when scarce. Concrete rules from Clay's implementation guide: blur radius 8-16px (never >20px), 10-15% surface opacity in light mode and 20-30% in dark mode, 1px semi-transparent light stroke to define the shape, box-shadow 0 4px 30px rgba(0,0,0,0.1), and a rgba(0,0,0,0.3) scrim layer between the background and any glass panel carrying text. Critically for MusicViz: contrast must be verified against the LIGHTEST, most saturated visualizer frame, not an average one — the live visuals behind the Now Playing chrome are the worst-case background imaginable. Codify this as a Compose 'CrystalSurface' component so every sheet, dialog and panel inherits the same optics. Applies to: Now Playing, Visuals hub overlay chrome, Customize tabs, queue sheet.

Source: https://clay.global/blog/glassmorphism-ui

### [MUST] Learn from the Liquid Glass backlash: never put text on unscrimmed glass, and ship 'Reduce transparency / Increase contrast' toggles

Apple's Liquid Glass measured as low as 1.5:1 contrast (WCAG minimum is 4.5:1) and was walked back across three betas, culminating in a user-facing 'Tinted' control in iOS 26.1 that tones the effect down. The documented failures: text fading into background layers, glassy reflections/motion obscuring what is interactive, and motion effects triggering vestibular discomfort. For MusicViz this is existential — its background is animated by design. Adopt: an in-app 'Panel opacity / frost strength' setting (fits naturally beside the existing photosensitivity limiter), honoring the system reduce-motion and increased-contrast flags, and a rule that playback-critical controls (play, seek, volume) always sit on a frosted-toward-opaque surface, never raw glass. Avoid: full-screen glass language, glass on dense lists (Library, tag editor). Applies to: Now Playing, Visuals hub, Settings.

Source: https://infinum.com/blog/apples-ios-26-liquid-glass-sleek-shiny-and-questionably-accessible/ ; https://gulfnews.com/technology/companies/apple-yields-tinted-control-in-ios-261-beta-4-tones-down-liquid-glass-after-backlash-1.500315176

### [MUST] Ambient artwork treatment on Now Playing: blur+stretch album art as a background glow, plus 3-color extraction driving panel tints

The dominant premium now-playing pattern (Apple Music, Spotify backdrops, ColorFlow): take the album art, blur and stretch it into an ambient glow layer, then extract a small palette via K-Means/Median-Cut quantization — one dominant, one contrasting, one accent — and use it to tint glass panels, seekbar fill, and button glow, with text colors auto-adjusted for contrast. For MusicViz specifically, do this twice: when the visualizer is paused/minimized, use artwork-derived ambient color; when visuals run full-bleed, sample the scene's current palette instead so the glass chrome refracts the visuals (crystal identity = the UI is a prism over the scene). This makes every track/scene feel bespoke without any new asset work. Applies to: Now Playing, mini-player, Library headers.

Source: https://bvdart.nl/en/articles/dominant-color-extraction-in-practice ; https://medium.com/@mike-at-redspace/dynamic-theming-a-developers-guide-to-adaptive-color-in-ui-7c2e0aef2878 ; https://www.idownloadblog.com/2017/03/01/colorflow-3/

### [MUST] Preset-first architecture for the Visuals hub: live-thumbnail scene cards with search, ratings/favorites, and parameters demoted to a second layer

Every successful deep-customization tool leads with presets, not parameters: projectM ships a Visual Effect Browser with search and per-preset rating across hundreds of presets; Koala is praised precisely for 'not getting bogged down by pages of parameters'; preset browsers in synth plugins pair browsing with A/B slots. With 57 scenes x substyles x palettes, MusicViz's hub should be a searchable, filterable card grid where every card is a live (or cached-video) thumbnail rendered with the current audio, with favorite/rating chips, and a single 'Customize' affordance per card that opens the parameter layer. Recently-used and 'For this track's energy' (beat/key detection already exists) rows on top. The 200 parameters stay one tap away, never the landing surface. Applies to: Visuals hub, Customize tabs entry point.

Source: https://play.google.com/store/apps/details?id=com.psperl.prjM&hl=en_US ; https://www.soundonsound.com/reviews/elf-audio-koala-sampler

### [MUST] Macro knobs: distill each scene's parameter set into 4-8 curated macros on the first Customize tab

The synth-world consensus for taming parameter explosion: Elastic OSC reduces a macro-oscillator to 4 core parameters mapped to an XY pad; ButterSynth and Serum expose 8 macro dials that each fan out to many underlying parameters with per-mapping ranges. For MusicViz: give every scene 4-8 designer-authored macros (e.g. Energy, Density, Refraction, Glow, Chaos) as large crystal-knob controls on the first Customize tab, each internally mapped to multiple raw parameters with ranges. Full parameter lists move to an 'Expert' tab. Macros also become the natural targets for the existing LFO modulation system and performance takes — one macro lane instead of ten parameter lanes. This is the highest-leverage change for making 200 parameters feel like a feature instead of homework. Applies to: Customize tabs.

Source: https://mominstruments.com/elasticosc/ ; https://www.kirnuarp.com/ ; https://mpmidi.com/serum-2-controller

### [MUST] Dark-first OLED surface system: true-black base + four defined surface elevations, designed dark-first

2026 guidance: dark mode is the primary design surface, not an inversion — and it requires a minimum of four surface levels (base background, elevated surface, secondary elevated, overlay). For a visualizer app the base should be true black (#000) so the canvas fuses with OLED bezels and saves measurable power (YouTube dark mode: 43% less power at full brightness on OLED), while list/reading surfaces (Library, Settings, lyrics panel) sit on dark-grey elevated surfaces to avoid the harsh pure-black/white-text halation that hurts astigmatic readers. Map MusicViz's 8 crystal-mineral themes onto this 4-level token system rather than per-screen colors, so glass opacity and border tokens stay consistent across themes. Applies to: App-wide, especially Library and Settings.

Source: https://muz.li/blog/whats-changing-in-mobile-app-design-ui-patterns-that-matter-in-2026/ ; https://appinventiv.com/blog/guide-on-designing-dark-mode-for-mobile-app/

### [MUST] Search-in-settings plus grouped subscreens once any screen passes ~15 options

Android's own settings guidance: with 15+ settings, group related items into subscreens; for deep hierarchies add search so users bypass navigation entirely — 'search combined with grouping makes findability 5x better' per settings-UX write-ups. MusicViz's Settings (playback, audio FX, visualizer safety, wallpaper, display, capture, export) and especially the Customize tabs (200 params) both qualify. Implement one search index spanning Settings AND scene parameters ('bloom', 'sensitivity', 'fps') with results deep-linking to the exact control, plus a 'Recent/Pinned' group at top. This is cheap, expected by power users, and directly addresses the app's density problem. Applies to: Settings, Customize tabs.

Source: https://developer.android.com/design/ui/mobile/guides/patterns/settings ; https://www.setproduct.com/blog/settings-ui-design

### [MUST] Thumb-zone layout law: primary actions in the bottom third, bottom sheets over FABs

75% of phone interactions use a single thumb; 2026 guidance treats bottom-third placement of primary actions and bottom sheets replacing floating buttons as non-negotiable. Audit MusicViz against this: transport controls, scene-switch, and randomize belong in the bottom third of Now Playing and the Visuals hub overlay; queue, preset pickers, palette pickers and share flows should all be draggable glass bottom sheets (the pattern Mobbin documents across the music/audio category — the player itself is a bottom sheet that expands full-screen). Top of screen stays reserved for artwork/visuals and status, matching the recurring Dribbble/Pinterest composition of oversized art above a bottom-clustered control stack. Applies to: Now Playing, Visuals hub, Library.

Source: https://muz.li/blog/whats-changing-in-mobile-app-design-ui-patterns-that-matter-in-2026/ ; https://mobbin.com/glossary/bottom-sheet

### [MUST] Halide-style progressive disclosure over the visualizer canvas: auto-hiding chrome, swipe-to-reveal quick controls, contextual UI transforms

Halide is the canonical customization-without-clutter case study: controls 'largely stay out of the way' of the viewfinder (their team explicitly rejected the 'flight simulator' look), a vertical swipe above the shutter reveals pro features one-handed, and the UI transforms contextually (tapping AF morphs the interface into manual-focus mode with focus aids). Translate directly: the Visuals hub canvas is the viewfinder; chrome auto-hides after ~3s; swipe-up reveals a one-row quick-adjust strip (macros + randomize + scene switch); long-press a macro to expand its underlying parameters in place; entering 'performance take' mode transforms the overlay into record-armed controls. Touch-smear/pinch interactions already exist — this makes deliberate control coexist with them without permanent clutter. Applies to: Visuals hub.

Source: https://9to5mac.com/2017/05/30/halide-iphone-camera-app/ ; https://developer.apple.com/news/?id=x6bv1a36 ; https://www.lux.camera/pro-camera-action-introducing-halide-mark-ii/

### [SHOULD] Randomizer upgrade: per-parameter/per-group lock icons that survive randomize and preset load

Established pattern across randomization UX: lock any parameter so shuffle/preset-load changes everything EXCEPT locked values (Emergence generative-art app shipped exactly this pairing of parameter locking + randomization; Vital users request it; shadcn Studio's theme shuffler locks fonts while re-rolling the rest; audio randomizer plugins use right-click-to-lock). MusicViz already has a randomizer and palettes — add a small padlock affordance on each macro/parameter row and palette slot, implemented as a simple locked-set check during randomize. Pair with A/B compare slots and undo/redo (standard in plugin preset browsers) so exploration is never destructive. This turns the randomizer from a slot machine into a design tool. Applies to: Customize tabs (randomizer, palette picker).

Source: https://daniel-gergely.itch.io/emergence1/devlog/519384/emergence-v102-parameter-locking-and-randomization ; https://forum.vital.audio/t/parameter-locks-for-preset-browser/11532 ; https://shadcnstudio.com/blog/how-shadcn-ui-presets-work/

### [SHOULD] XY performance pad with motion recording, wired into macros and performance takes

XY pads are the standard high-bandwidth control for live parameter play: Kaoss Pad lineage, ButterSynth's XY pad with motion recording plus 8 macro dials, Zebra's pads where each axis drives up to 8 parameters with per-mapping range, AudioSwift's trackpad XY for cutoff/resonance-style pairs. MusicViz should offer a full-screen crystal XY pad (assignable to any two macros, defaults per scene like Energy x Refraction), with recorded gestures saved as performance takes — unifying three existing systems (LFO modulation, performance takes, touch interaction) under one performable surface. VJ practice (Resolume custom control surfaces: faders, XY pads, encoders) confirms this is how live visual tools expose depth. Applies to: Visuals hub (performance mode), Customize tabs.

Source: https://audioswiftapp.com/xy-pads-for-sound-design-with-a-trackpad/ ; https://www.kirnuarp.com/ ; https://en.wikipedia.org/wiki/Kaoss_Pad

### [SHOULD] Adopt Material 3 Expressive's shape-morph + spring-physics system — the 35-shape library is a native fit for gem silhouettes

M3 Expressive (Android 16, backed by 46 studies/18k participants) adds 35 shapes with built-in shape-morphing animation, spatial/effects spring physics, button groups, FAB menus, floating toolbars, and updated sliders/progress indicators. For MusicViz: define custom faceted-gem Compose shapes for buttons and chips that morph on press (play button morphing between gem cuts is exactly what the shape-morph API was built for); use spatial springs for sheet/panel motion so glass feels physical; use a button group for transport and a floating toolbar for visualizer quick actions. This gets 2026-native motion character while reinforcing the crystal brand rather than fighting Material. Adopt the physics and shapes; skip dynamic-color wholesale theming where it would override the 8 mineral themes. Applies to: Now Playing, Visuals hub chrome, app-wide components.

Source: https://supercharge.design/blog/material-3-expressive ; https://www.androidauthority.com/google-material-3-expressive-features-changes-availability-supported-devices-3556392/

### [SHOULD] Wavy/waveform seekbar as the Now Playing progress treatment — rendered as a refracting crystal line

Two converging references: the Android 13+ media-player squiggly slider (now a documented multiplatform 'wavy slider' pattern, and M3 Expressive updated progress indicators similarly) and the waveform seekbar tradition (foobar2000 component, multiple Android libraries, recurring Dribbble seekbar shots). For MusicViz, render the seekbar as a light-refraction line: elapsed portion animates as a wave amplitude-modulated by the live audio level (the app already has the analysis), remaining portion is a thin faceted track; scrubbing raises a glass tooltip with time + synced-lyric snippet. This is a small-surface, high-signature move — the progress bar becomes a brand element. Applies to: Now Playing, mini-player.

Source: https://github.com/mahozad/wavy-slider ; https://github.com/massoudss/waveformSeekBar ; https://dribbble.com/tags/seekbar

### [SHOULD] Queue as a peeking, glass 'Up Next' sheet: visible edge affordance, add-next vs add-last, clear-all

Documented queue UX lessons: Apple Music's queue hidden under the player sheet had a discoverability failure (no caret/affordance signaling content below — critiqued in 'One Little Detail'), fixed in iOS 18 by matching Spotify's model: explicit play-next insertion, queue persistence when starting other music, and one-tap clear. Spotify's peeking side-artwork carousel signals prev/next tracks. For MusicViz: mini glass caret + top-edge of the next track's artwork peeking at the bottom of Now Playing; drag up for a frosted queue sheet with swipe-to-reorder, 'Play next'/'Add to queue' split actions, and clear-all. Also the natural home for the (currently UI-less) playlists backend: 'save queue as playlist'. Applies to: Now Playing (queue sheet), Library.

Source: https://medium.com/one-little-detail/9-apple-music-add-a-caret-to-signify-theres-content-at-the-bottom-f086e3b1ea4d ; https://www.idownloadblog.com/2024/06/14/ios-18-apple-music-up-next-queue-changes/

### [SHOULD] Performance budget for glass: static pre-blurred fallbacks and a hard cap on real-time blur while the GPU runs the visualizer

Glassmorphism guidance is blunt about cost: real-time backdrop blur compounds per surface, stutters on 3+ year-old hardware, and 2026 'Glassmorphism 2.0' guidance says to use static blurred backgrounds on budget devices. MusicViz's GPU is already saturated by fluid sims and shaders — so: cap live-sampled blur to ONE surface (the active sheet/overlay), have all other 'glass' use cheap approximations (pre-blurred scene snapshot, gradient + noise texture + 1px border reads as frosted glass at a fraction of the cost), auto-degrade to the static approximation when frame time exceeds budget, and test on the low end of the installed base. A paid visualizer that drops frames because of its chrome is self-defeating. Applies to: Visuals hub, Now Playing overlays.

Source: https://clay.global/blog/glassmorphism-ui ; https://muz.li/blog/whats-changing-in-mobile-app-design-ui-patterns-that-matter-in-2026/

### [SHOULD] Serif display type doubling down — expressive high-contrast serifs are the 2026 confidence marker, but keep them display-only

Multiple 2026 typography-trend roundups agree serifs are resurging specifically in expressive, high-contrast, sharp-detailed forms for hero/editorial moments — 'the mark of confidence in brand and product contexts.' MusicViz's serif display type is ahead of this curve; the refinements are: (1) reserve the serif strictly for display sizes (track titles, scene names, screen headers) and pair with a neutral grotesk/sans for controls, labels and settings rows; (2) consider a variable-weight axis so titles subtly respond to audio energy or theme (2026 'adaptive type systems' trend) — a cheap, on-brand delight; (3) never set serif over unscrimmed glass at small sizes — thin high-contrast strokes are the first casualty of blur. Applies to: Now Playing, Visuals hub headers, Library.

Source: https://www.andacademy.com/resources/blog/graphic-design/typography-trends/ ; https://madegooddesigns.com/font-trends-2026/ ; https://medium.com/design-bootcamp/typography-trends-2026-2027-when-letters-begin-to-breathe-8499fb6c5ef1

### [SHOULD] Recurring Pinterest/Dribbble now-playing composition: oversized rounded artwork card, 24px+ radii, jewel-tone gradients on near-black, glow microinteractions

Across Pinterest ideas hubs (Music Player UI Design, Dark Mode Music App, Glass Morphism UI) and Dribbble's glassmorphism.audio-player search / music-player-ui tag (1,200+ shots), the convergent formula — inferred from search snippets and secondary write-ups since Pinterest/Dribbble block direct fetches — is: dominant rounded-corner artwork card (24px+ radius), controls clustered below in a frosted card, deep near-black backgrounds with jewel-tone accent gradients (deep purples, midnight blues, neon pinks, ocean teals), scale+glow press feedback on controls, and blurred art-derived backdrops. A documented case study (Soundico Beats concept) adds a floating soundwave bar and glassy overlays. MusicViz already matches the palette language via mineral themes; the actionable gaps are the oversized-artwork card treatment in Now Playing and consistent glow-on-press microinteractions. Applies to: Now Playing, mini-player.

Source: https://www.pinterest.com/ideas/music-player-ui-design/916051500373/ ; https://dribbble.com/search/glassmorphism.audio-player ; https://medium.com/@20bmiit108/designing-a-glassmorphism-music-player-a-modern-ui-ux-exploration-8c29b8dd796d

### [SHOULD] Value-first onboarding: reach the 'wow' visualizer moment in under 60 seconds, then a short post-purchase feature-map tour

Onboarding research for premium apps: cleaner, shorter flows outperform visual-heavy ones; users who never hit a meaningful premium 'aha' in session one are churn/refund risks; after purchase, run a brief tour showing WHERE premium features live and guide the first premium action. For a paid one-time-purchase visualizer the aha is obvious: first-run should request audio permission, auto-pick a track (or demo audio / mic capture), and drop straight into a curated crown-jewel scene — before any account/theory screens. Then a 3-4 stop glass coach-mark tour: swipe scenes, tap to smear, here's Customize, here's video export. Settings walkthroughs, theme pickers and library indexing all defer to later moments. Applies to: Onboarding flow, first-run of Visuals hub.

Source: https://adapty.io/blog/mobile-app-onboarding/ ; https://www.airbridge.io/en/blog/5-steps-app-onboarding-before-the-paywall ; https://www.appcues.com/blog/best-user-onboarding-examples

### [COULD] Vythm-style 'performance bar': one-tap screen effects (bloom, chromatic aberration, color correction) as a quick strip over the canvas

Direct competitor Vythm VJ organizes live play as mode selection plus a 'performance bar' of instantly-toggleable screen effects (bloom, color correction, chromatic aberration) over 100+ backgrounds — its Play Store listing markets exactly this immediacy. MusicViz's equivalents are buried in per-scene parameters. Surface a horizontal strip of 5-7 global post-effects as tap-to-toggle / hold-to-adjust crystal chips in the Visuals hub quick-controls layer, orthogonal to scene choice. This gives casual users VJ-feeling power with zero parameter literacy and showcases the engine's range in demos/screen recordings. Applies to: Visuals hub.

Source: https://play.google.com/store/apps/details?id=com.MKGames.Vythm&hl=en-US

### [COULD] Crystal motif accents: prismatic refraction and faceted highlights as scarce hero moments, not wallpaper

Crystal/prism UI reference material (Pinterest prism-crystal/3d-crystal hubs, stock refraction illustrations, GraphicRiver crystal UI packs) consistently trades on light-refraction effects: iridescent facet flashes, rainbow caustic edges, glow-from-within gems. The design risk is kitsch — game-UI crystal buttons read as free-to-play. Guidance synthesized from the glassmorphism scarcity principle: reserve prismatic effects for a few high-value moments — active-state edge refraction on the play button, a caustic sweep when a preset saves or an export completes, faceted silhouettes for theme icons — and keep working surfaces as calm frosted glass. One signature refraction shader reused sparingly beats mineral texture everywhere. Applies to: Now Playing (play button), Customize (save/preset moments), theme picker.

Source: https://www.pinterest.com/ideas/prism-crystal/953881519429/ ; https://graphicriver.net/crystal-and-ui-graphics ; https://clay.global/blog/glassmorphism-ui

### [COULD] Library density done right: learn from Poweramp's customization sprawl — offer few, curated 'look' controls instead of dozens of toggles

Poweramp, the reference paid Android player, exposes dozens of look-and-feel options (album-art corners, fonts, transparency, seekbar styles) buried in Settings > Look and Feel — powerful but widely noted as overwhelming, which is why third-party 'Improved' skins succeed by offering a curated dozen choices (12 accents, 11 fonts, 5 backgrounds). Lesson for MusicViz's Library and theme system: keep the 8 mineral themes as complete curated looks (each fixing accent, surfaces, artwork corner treatment) rather than exposing granular UI-appearance toggles; put any per-user tweaks (grid vs list, artwork size) as 2-3 options max in an overflow, not a settings tree. Curation IS the premium signal. Applies to: Library, Settings (appearance).

Source: https://www.androidpolice.com/tried-poweramp-and-musicolet-for-month-on-android/ ; https://play.google.com/store/apps/details?id=com.poweramp.v3.improved

### [COULD] Ship the playlists UI as standard Mobbin-documented flows: playlist detail sheet, save-queue-as-playlist, drag reorder

MusicViz has a playlists backend with no UI — a straightforward gap. Mobbin's music/audio category and Spotify playlist-detail flow screens document the settled conventions users expect: playlist detail with hero artwork mosaic + play/shuffle split button (an M3E split-button use case), long-press/overflow 'Add to playlist' from any track row, save-current-queue-as-playlist from the queue sheet, and drag-handle reorder. Following the documented default flow keeps effort low and pairs naturally with the queue-sheet redesign. Applies to: Library, Now Playing queue sheet.

Source: https://mobbin.com/explore/flows/dafca833-7bf4-46e3-97d0-4769403f8f7a ; https://mobbin.com/explore/mobile/app-categories/music-audio

### [COULD] Contextual adaptive surfaces (2026 'AI-native' pattern) applied cheaply: time-of-day and audio-source aware home states

Muzli's 2026 pattern #1 is adaptive interfaces that restructure by context (Spotify's morning podcast shelves; Google Maps' commute vs exploration modes) with the caveat that adaptation must be invisible. MusicViz has the signals to do a lightweight version without ML: when launched via live-wallpaper or external-display context, land on the Visuals hub; when headphones connect, surface the library/last queue; evening launches bias toward darker, low-brightness scene rows (synergy with the photosensitivity limiter); mic/system-capture mode gets its own compact chrome. Keep it to reordering rows and default tab selection — never moving controls. Applies to: app home / Visuals hub / Library entry states.

Source: https://muz.li/blog/whats-changing-in-mobile-app-design-ui-patterns-that-matter-in-2026/
