# Feature Triage — 91-Feature Blueprint vs the Code

Produced by a 6-category parallel triage (one architect pass per category, file:line
evidence required for every 'implemented' claim) plus a synthesis pass, run against
main at the slow-visuals fix. Counts: 7 implemented outright, 24 partial, 60 absent —
but see section 1: several 'absent' blueprint entries were in fact complete.

# WORK ORDER — music-visualizer-2 feature queue

## 1. ALREADY BUILT — do not fund these

| id | name | evidence |
|---|---|---|
| #91 | faster-than-realtime deterministic 4K re-render | `export/VideoExporter.kt:88-91` (offline frame loop, precomputed timeline), `:535`, `:547` constant dt, `:719` computed PTS, `:593` autoquality off; pinned by `ExportDeterministicQualityTest.kt` |
| #86 | gl-transitions library (122 entries) | `assets/gl_transitions.json`; `render/TransitionCatalog.kt:101-126`, `:140-147`; `render/VisualizerRenderer.kt:539-556`, `:1223-1226`; `ui/CustomizeTabs.kt:339-377` |
| #64 | BPM-synced LFOs | `render/Lfo.kt:148-153`, `:80-83`; `ui/CustomizeTabs.kt:703-709`; `data/LfoStore.kt:92-93/:102-103`; `render/VisualizerRenderer.kt:1033`; export parity `export/VideoExporter.kt:565` |
| #57 | image/photo reactive background | `data/TextureStore.kt:237-283` (generated .milk, bass zoom + treble pulse), `ui/TextureController.kt:86-94`, `ui/VisualsHub.kt:1345-1372` |
| #52 | beat-triggered preset hardcuts (on by default) | `ui/AutoVisualsController.kt:266-285`; default `ui/PlayerViewModel.kt:98`; `ui/AutoVisualsPrefsStore.kt:46/:66`; second path `:127-145` |
| #1 | true gapless | `playback/PlaybackEngine.kt:71` (one process-scoped player), `ui/PlayerViewModel.kt:1545` (setMediaItems+prepare at queue-open only). Encoder-delay trimming is DefaultAudioSink's own field, not the replaced chain — verified in media3-exoplayer 1.10.0 bytecode |
| #18 | headset double-click → next | `AndroidManifest.xml:141` MediaButtonReceiver; media3-session 1.10.0 `MediaSessionImpl.applyMediaButtonKeyEvent` rewrites 85/79→87 on double tap. Only triple-click is absent |
| #16 | backup/restore (platform) | `AndroidManifest.xml:53-56` + `res/xml/backup_rules.xml` + `res/xml/data_extraction_rules.xml` — deliberate excludes with quota arithmetic in comments |
| #61 | kaleidoscope first-class | `SceneIds.kt:15` → `kaleido_frag.glsl`; global fold `render/scene/SceneParams.kt:42-43`, applied in 22 shaders and `res/raw/composite_frag.glsl:185-190`; export parity `export/FxCompositor.kt:394-395` |
| #27 | float DSP chain, phase 1 (chain ownership) | commit `d019eb3`; `audio/dsp/MvzAudioProcessorChain.kt:57` ordering, `:61-70` delegations, installed `audio/TapRenderersFactory.kt:41`, 6 tests |
| #54 | layer compositing, N=2 | `render/VisualizerRenderer.kt:332-339`, `:1139-1154`, `:1291-1301`; `composite_frag.glsl:428`; `render/BlendMode.kt:15-38`; `ui/CustomizeTabs.kt:623` |
| #84 | keyframe timeline | `data/PerformanceTake.kt:16-31/:103-126/:177-189`; export reader `export/VideoExporter.kt:213`, `ui/ExportController.kt:226-228` |
| #87 | constant-speed retiming (audio+video) | `export/ClipEdit.kt:111/:129/:228-247`; `ui/StudioScreen.kt:287` |
| #88 | beat-synced cutting (of visuals, incl. export) | `ui/AutoVisualsController.kt:266-285` + take replay per frame |
| #51 | burnt-in text overlay | `export/ClipEdit.kt:120/:206-225`, `ui/StudioScreen.kt:327-329`, gated by `ClipEditTest.kt:64` |
| #66 | preset organisation — folders | `data/PresetStore.kt:76-134`; full tree UI `ui/VisualsHub.kt:236-390` |
| #45 | reactivity attack/decay | `analysis/BandSmoother.kt:7-13`; sliders `ui/CustomizeTabs.kt:432-433`; `ui/PlayerViewModel.kt:2008-2013`; `data/PresetStore.kt:219-220/:459-460` |
| #44 | spectral centroid (computed, cached, consumed) | `analysis/FeatureExtractor.kt:133`, `:174`; `analysis/AnalysisCache.kt:234/:157`; readers `FountainScene.kt:41`, `StormScene.kt:118`, `SceneSuggester.kt:110` |
| #40 | onset/novelty (flux + adaptive threshold + tempo) | `analysis/FeatureExtractor.kt:121-132`, `:275-290`; `analysis/PulseTracker.kt:285+`; multi-band `analysis/DrumChannels.kt:127-146` |
| #43 | energy/character model | `analysis/SceneSuggester.kt:34-46/:53-85/:99-112`; `analysis/FeatureTimeline.kt:29-33` |
| #53 | live palette mutation (5 sources) | `render/scene/ShaderScene.kt:255`; `render/Lfo.kt:41-42`; key→hue `ui/PlayerViewModel.kt:1766`; artwork `:1805-1841`; chroma `render/scene/CymaticsScene.kt:289-296` |
| #60 | feedback buffer | `render/VisualizerRenderer.kt:487-491`, `:1546-1594` + `trail_warp_frag.glsl`; generic ping-pong `render/fluid/FluidBuffers.kt:267` |
| #90 | long-export hardening (5 real failure modes fixed) | `export/AudioTranscoder.kt:20-23`; `export/VideoExporter.kt:738-748`, `:781-785`, `:796-805`, `:806-825` |
| #5 | drag-reorder — *the hard parts* | gesture + drop math + tests exist: `ui/LibraryScreen.kt:533-617`, `:480`, `:499`, `PlaylistReorderTest`; sink `ui/PlayerViewModel.kt:1328` whose KDoc already claims the queue tab. It is a port, not a build (S) |
| #46 | BPM/key data — *the analysis half* | `ui/TrackLibrary.kt:27-29/:305-306`, `ui/MusicLibraryController.kt:350`, on screen at `ui/LibraryScreen.kt:181/:155-156`; `analysis/KeyPalette.kt:26-48/:98-107` is Camelot minus mode |
| #19 | playback statistics — *the whole data layer* | `data/HistoryStore.kt:137/:168/:205/:213` complete, durable, and has **zero callers** in main/ |

## 2. PARALLEL WAVE — new files only, no contention

All items below touch **no existing file**; every one can run simultaneously with every other and with the whole serial queue. Ranked value/effort.

| # | deliverable | new files |
|---|---|---|
| 1 | **#16 backup-rules source-text gate** — highest value-per-line in the repo; nothing pins `backup_rules.xml`/`data_extraction_rules.xml` today, so the next `filesDir` store silently breaks the 25 MB quota for presets, playlists and history | `app/src/test/java/dev/musicviz/BackupRulesCoverageTest.kt` |
| 2 | **#46 engine** — Camelot wheel + queue planner; imports `KeyPalette` rather than duplicating it, degrades key→BPM→no-op for unanalysed tracks | `analysis/CamelotWheel.kt`, `analysis/QueuePlanner.kt`, `+2 tests` |
| 3 | **#75 engine** — export queue state machine | `export/ExportQueue.kt`, `+test` |
| 4 | **#8 parsers** — M3U/PLS/XSPF, with basename+byte-size resolution mirroring `TrackLibrary.identityKey:205` | `data/PlaylistFormats.kt`, `+test` |
| 5 | **#79 table** — named platform export bundles (ratio/quality/fps/loopSafe all already persist) | `export/ExportPresets.kt`, `+test` |
| 6 | **#13 planner** — album / balanced / weighted orderings computed *before* `setMediaItems`, not ShuffleOrder subclasses | `playback/ShuffleModes.kt`, `+test` |
| 7 | **#30 matrix builder** — mono/balance/width/pseudo-stereo + #32's polarity flip as coefficients; media3's `ChannelMixingAudioProcessor` does the rest | `audio/dsp/StereoMatrix.kt`, `+test` |
| 8 | **#83 parser** — `.cube` LUT → `SingleColorLut` bitmap | `export/CubeLut.kt`, `+test` |
| 9 | **#37 doc** — collect the chain contract already written in `MvzAudioProcessorChain.kt:11-47` + `AudioChainContractTest.kt:9-31` + `EqualizerSettings.kt:79-95` | `docs/AUDIO_CHAIN.md` |
| 10 | **#9 tree builder** — path list → nested nodes with single-child collapsing (fixes `/Music/Live` + `/Podcasts/Live` merging at `LibraryScreen.kt:680`) | `ui/FolderTree.kt`, `+test` |
| 11 | **#88 planner** — bpm + beat times + target length → cut list, copying `BarTrim`'s 50-220 BPM trust guard | `export/BeatCutPlanner.kt`, `+test` |
| 12 | **#84 edit module** — insert/move/delete/retime over take JSON | `data/TakeEdit.kt`, `+test` |
| 13 | **#25 parser** — AutoEQ ParametricEQ.txt as a sealed parse result. Inert until #24 lands | `audio/dsp/AutoEqPreset.kt`, `+test` |
| 14 | **#81 parser** — SRT/VTT. Extend the cue model with end times **now** if #76 is coming | `ui/SubtitleParse.kt`, `+test` |
| 15 | **#1 gapless regression gate** — the only work left on #1 (touches `PlaybackEngineTest.kt` only) | test edit |

Skip in this wave: #7 CueSheet parser (parser is 20% of the cost; uri-as-identity is the other 80%), #73 GifWriter (frame source doesn't exist, output looks bad).

## 3. SERIAL QUEUE

### 3a. The four-file gate — strictly one at a time
Each of these needs some of `SceneParams.kt` / `PresetStore.kt` / `ParamRandomizer.kt` / `CustomizeTabs.kt` + regenerated `PARAM_MATRIX.md`.

1. **#48 Shadertoy import** (L, medium). `VisualizerRenderer.kt`, `CompositeGrade.kt`, `VisualsHub.kt`, `FftProcessor.kt`, `AudioFeatures.kt`. Go first: it settles the *separate Scene class* decision (a `ShaderScene` subclass hits `gateFor(SHADER)` and silently kills Zoom/Rotation/Shape/Colour/FX/Pulse — `VisualizerRenderer.kt:1526-1533`) and adds the linear-magnitude accessor that #39 and MFCC also need. Reuse `tools/vendor_gl_transitions.py`'s ES 3.00 fixes; do not write a third porter.
2. **#68 mod-matrix, with #67 smoothing folded in** (XL, high). `CustomizeTabs.kt`, `Lfo.kt`, `Adsr.kt`, `LfoStore.kt`, `PlayerViewModel.kt`, `VisualizerRenderer.kt`, `VideoExporter.kt`. This is a persisted-format migration of the un-versioned `lfos`/`adsrs` keys plus dual-pipeline parity, not a UI. **Write the destination set down before starting** — 37 of SceneParams' 170 fields are routable, and widening means ~130 new clamp rules in `applyTarget`. Per-route smoothing must land in the same migration.
3. **#53 writable palette LUT** (M, medium). `CyclicPalettes.kt`, `PaletteStore.kt`, `lib_palette.glsl`, `VisualizerRenderer.kt`. Scope: user-authored multi-stop ramps in a live LUT row; also fixes colour-map-works-on-22-of-39-styles.
4. **#50 sprite/image overlay** (L, medium). `composite_frag.glsl`, `VisualizerRenderer.kt`, `FxCompositor.kt`, `CustomizeTabs.kt`. Use the LayersBus precedent (renderer state, not SceneParams). Both composite call sites must upload — `CompositeUniformParityTest`. Atlas on texture unit 5+.
5. **#61 mandala refinement** (S, medium). Rotating fold offset + radial repeat + petal count. Full five-leg gate change.
6. **#47 ISF** (XL, medium). Only after #48 proves the separate-Scene-class route. Ship a documented subset with a per-entry `unsupported` marker; model dynamic uniforms on `TransitionCatalog`, never SceneParams fields.
7. **#54 N-layer** — defer. Fix the shipping bug first (postFx double-applies all nine whole-frame effects per layer today).

### 3b. Four independent spines — run concurrently with 3a and each other, serial *within* each spine

**Player / ViewModel spine** (`ui/PlayerViewModel.kt` — gated by `ViewModelSurfaceTest`, `DeadVmApiTest`)
1. **#5 drag-reorder queue** (S, high) — `PlayerPanels.kt`, `VisualizerScreen.kt:259`. Recompute from item index; suppress follows-playback autoscroll during drag.
2. **#13 shuffle-order defect** (S, high) — `refreshQueue:1296` reports timeline order, so the queue panel and "Up next" name the wrong tracks under shuffle. Fix with `Timeline.getNextWindowIndex`, then wire the #6 planner.
3. **#19 stats screen** (M, medium) — new `ui/StatsScreen.kt` + a 6th library tab (not pinned). **Do not add `fun mostPlayed(`** — `DeadVmApiTest.kt:41-47` fails the build. Expose the VM's `HistoryStore` instance or flush before opening, or it ships permanently stale.
4. **#11(1) per-track resume position** (S, high) — hooks the existing 500 ms `accrueListenTime:1421` tick; `PlaybackService.lastPlayedResumption:144-158` carries a real `startPositionMs`.
5. **#4 saved/swappable queues** (L) — persist uris+index+position **and the shuffle order**, which `DefaultShuffleOrder` does not save today. Add the new store to both backup rule files.

**Library spine** (`ui/LibraryScreen.kt`, `ui/MusicLibraryController.kt`)
1. **#12a year + composer** (S) — MediaStore columns on all levels; `GroupList:314` is already generic.
2. **#12b album-artist + genre** (M) — API 30+ vs minSdk 26, needs a guard + fallback + per-row `trackOverrides` fallback.
3. **#6 smart playlists** (L) — only after #12b, else "Genre = Jazz" is silently empty. Cheapest first slice is built-in Favourites / Most played / Recently added, no rule DSL.
4. **#9b SAF-tree browsing** (M) — lazy `DocumentFile.listFiles` per expanded node, never the depth-8 eager walk.
5. **#10a multi-select + bulk enqueue/add-to-playlist** (S) — selection state + loops over existing CRUD. Stop there.

**Audio spine** (`audio/TapRenderersFactory.kt`, `playback/PlaybackEngine.kt`, `ui/AudioSettings.kt` — gated by `AppSettingsTabSplitTest`, `PrefsRoundtripReflectionTest`)
1. **#24 parametric EQ — the keystone, alone** (L, high). `Biquad.kt`/`BiquadCascade.kt`/`ParametricEqConfig.kt`/`EqProcessor.kt`. #25, #29, #35 all need `Biquad.kt`. Decide up front whether the platform `Equalizer` is retired (two EQs stack silently) and note `EqualizerCardStateTest` drives the existing card. Config lives on `PlaybackSession`, never the ViewModel.
2. **#20 + #31 as one gain slice** (L, high). ReplayGain via media3's `GainProcessor` + limiter. Measure by riding `OfflineAnalyzer`'s existing decode; bumps `AnalysisCache.VERSION` 2→3. Never ship the preamp without the limiter.
3. **#30 wiring** (S) — register 1ch and 2ch matrices minimum; identity → `NOT_SET` is a free bypass.
4. **#29 crossfeed** (M) — after #24 only.
5. **#27 float stage + #33 dither as its last stage** (M).
6. **#36 per-output profiles** (L) — build the route observer once, shared with #38's honest subset.
   Every one of these needs one line of UI copy: **the tap is first, so none of it moves the visuals.**

**Export / Studio spine** (`export/VideoExporter.kt`, `ui/SettingsDialog.kt`, `export/ClipEdit.kt` — gated by `ExportHostSaveableTest`, `PrefsRoundtripReflectionTest`, `ParamSurface.FAMILIES`)
1. **#90(1) foreground service** (S, high) — the render dies with the ViewModel today; this loses more long exports than every fixed bug combined. Alone, first.
2. **#75 wiring** (M, high) — decouple `exportSceneFactory` from the live `VisualizerView` (`ExportHost.kt:78-84`); the returned scene owns no GL until `init()`.
3. **#79 chip row** (S) — `SettingsDialog.kt:139`, `ExportSettings.kt:73`.
4. **#70 HEVC only** (M, high) — three hardcoded `MIMETYPE_VIDEO_AVC` sites; lifts `MAX_AVC_DIM = 4096` so 21:9 "4K" stops being a lie. VP9 drags a second audio encoder; AV1 needs capability probing. Do not bundle them.
5. **#74 PNG sequence + the glReadPixels/PBO readback** (M, medium) — the shared prerequisite for #72/#73 and the thing that turns #90(2) resume from L into M. Needs `OpenDocumentTree`, not `CreateDocument`.
6. **#76 + #82 + #77 + #80 as one Studio overlay slice** (M–L, high) — subclass media3 `TextOverlay`/`CanvasOverlay`/`BitmapOverlay`; `Lyrics.indexAt` already exists. **Touches no gate file.** Every one of these must be added to `ClipEdit.isIdentity` or the export takes Transformer's lossless fast path and emits an unedited copy.
7. **#83 + #87 wiring**, then **#78 templates** (a template with nothing to bundle is a preset with a longer name).

## 4. NOT WORTH DOING / BLOCKED

| # | reason |
|---|---|
| #2 crossfade | Undecided architecture. Contradicts a *tested* invariant (`PlaybackEngine.kt:181-204`, `PlaybackEngineTest.kt:46-57`); real problem is facade-Player position reporting + duplicate `AUDIOFOCUS_GAIN`, not mixing. Interlocks #1 (must be off for gapless albums). Needs a written decision first. |
| #14 auto DJ | Strictly downstream of #2. Its sequencing half is not a feature — fold it into #13's planner. |
| #21 bit-perfect / #23 hi-res | Mutually exclusive with the visualizer: float output is the only escape from int16 and it deletes the `TeeAudioProcessor` silently; `AudioChainContractTest.kt:37-46` fails the build on it. Product decision, not a task. **Instead: restore `analysis/AudioQualityInfo.kt` from `6c3011e^` (S, high value).** |
| #22 DoP output | Same blocker. Split it: DSD→PCM decode is pure JVM (L) via the AIFF three-site pattern; DoP output is not. |
| #28 DVC | Already declined at `BLUEPRINT_REVIEW.md:194-196`. Fights A2DP absolute volume; `player.volume` already has two contending writers. |
| #38 BT codec | `BluetoothA2dp.getCodecStatus` is `BLUETOOTH_PRIVILEGED`. Cannot be delivered honestly. Ship route/format awareness with the #23 restore instead. |
| #65 OSC | No INTERNET permission, and its absence is load-bearing user-facing copy (preset links, mic/capture privacy). **Close it** — #63 local MIDI delivers the same capability at zero product cost. |
| #56 camera | Dangerous permission against a documented privacy posture; the live wallpaper can never open the camera (Android 9+). Undecided. |
| #43 learned embeddings / #39 ML stems | No TFLite/ONNX in `app/build.gradle.kts:240-279`, no network to fetch a model, no licence/APK-size decision. HPSS half costs >30k median selections/frame for information nothing consumes. |
| #49 dual projectM | Native rebuild + `nativeGetLastError()` is a process-global with no handle (already unsound during MilkDrop export) + destructive-read PCM. |
| #59 bundled .milk packs | Reverses a shipped removal (`CHANGELOG.md:851-854`; `PlayerViewModel.kt:669-673` still sweeps them) and needs a per-item licence audit MilkDrop presets never carry. |
| #72 alpha export | Platform-unavailable: no alpha-capable `MediaCodec` encoder, MP4 can't carry it, `MUXER_OUTPUT_WEBM` can't encode VP9 alpha. Revisit after #74. |
| #89 Lottie/SVG | New graphics-runtime dependency against a vendor-and-audit repo practice; Android has no SVG parser. Static stickers = #80 + a position control. |
| #71 ProRes | No encoder exists. The all-intra mezzanine reading is `KEY_I_FRAME_INTERVAL = 0` + a bitrate multiplier — do that, ignore the label. |
| #26 convolution | Not blocked (JTransforms is on the classpath) but XL/low: hard audio-thread deadline, no-allocation rule, IR resampling, pre-delay vs reported position. Defer. |
| #58 3D mesh import | Nothing in the tree has a depth buffer; `GlUtil.resetFrameState()` disables depth and cull every frame. The parser is the cheap 10%. Low value against the SDF aesthetic. |
| #32, #33, #34, #35 | None is a standalone feature. #32 → a `-1` coefficient inside #30, drop the rest. #33 → last stage of #27. #34 → one line (`setOutputSampleRateHz`) if "fixed output rate"; XL and pointless if "selectable SRC tiers" — make the parent pick. #35 → filters are a preset over #24's cascade; peak/RMS normalise is a "simple" mode of #20. |
| #45 FFT size/window | Largest blast radius, least payoff: no `fftSize` in the cache key, `CymaticsMath.REFERENCE_FFT_BINS = 1024` is pinned bin-for-bin, `AUDIO_TEX_WIDTH = 64` is hardcoded for every shader. The repo's own counter-argument is at `DrumChannels.kt:152-158`. Close it. |
| #10b bulk delete/move | No "forget this uri everywhere" seam exists across TrackLibrary/playlists/Favourites/History/analysis cache, and `removeFromLibrary` is pinned *out* by `DeadVmApiTest.kt:50-67`. Ship #10a only. |
| #3 tag writing | XL for medium value, three hand-rolled container writers + IntentSender consent — and writing tags changes file size, which changes `TrackLibrary.identityKey`, orphaning cached BPM/key on the next rescan. Defer until that identity model is fixed. |
| #85 multi-track timeline | XL; it replaces the flat immutable `ClipEdit` with a tree. Land #87 ramp and #88 cuts first and re-ask whether a timeline is still wanted. |
| #7 CUE sheets | Low value; the parser is easy, but a range-inside-one-file breaks uri-as-identity in `PlaybackQueue.window`, HistoryStore, FavouritesStore and TrackLibrary simultaneously. |

## 5. THE THREE CORRECTIONS THAT MATTER

1. **Nine listed features already ship end-to-end, several of them the ones the blueprint treats as headline work.** #91 (deterministic off-clock 4K re-render) *is* the export architecture. #86 ships the full 122-entry gl-transitions corpus. #64 is complete including offline export parity. #57, #52, #61, #1, #18, #16 likewise. #27's "first foundation slice" landed in `d019eb3`. Funding any of these is pure waste; the residue in each case is a test, a phase lock, or a sentence of UI copy.

2. **The tap-first invariant deletes the visual payoff of the entire audio-DSP category, and makes two of its items unschedulable.** Because the analysis tap sits at chain index 0, #20/#24/#26/#29–#35 are *inaudible to the visuals* — fold to mono, cut 12 dB of bass, and the picture does not move. Say it once in the UI or it gets filed as a bug 35 times. And #21/#23 are not Phase-3 engineering: float output is the only route past media3's int16 conversion and it silently deletes the `TeeAudioProcessor`, so bit-perfect hi-res and the visualizer are mutually exclusive. That is a product question ("does a visualizer ship a mode with no visuals?"), and `AudioChainContractTest.kt:37-46` already fails the build on the answer.

3. **The four-file Customize gate is the wrong contention model for two thirds of this backlog, and it hides three gates that will actually fail builds.** Nothing in #1–#19 or #20–#38 needs SceneParams/PresetStore/ParamRandomizer/CustomizeTabs. The real spines are `PlayerViewModel.kt`, `LibraryScreen.kt`, `MusicLibraryController.kt`, `TapRenderersFactory.kt` and `VideoExporter.kt` — plus `ViewModelSurfaceTest` (every new `fun set…` needs a non-`*Controller.kt` ui/ caller in the same commit), `DeadVmApiTest` (which pins `fun mostPlayed(` and `removeFromLibrary` *out*, blocking the obvious wirings for #19 and #10), `AppSettingsTabSplitTest` (six Settings tabs verbatim; every labelled control in exactly one file), `PrefsRoundtripReflectionTest` (reflective, so a field added to `save()` but not `load()` fails the day it lands) and `ExportHostSaveableTest` (fails on any new `by remember {` in `ExportHost.kt`). Separately: media3 1.10.0, already resolved, supplies `GainProcessor`, `ChannelMixingAudioProcessor`/`Matrix`, `TextOverlay`/`CanvasOverlay`/`BitmapOverlay`, `SingleColorLut` and `Composition` — five features on this list are configuration, not engineering.