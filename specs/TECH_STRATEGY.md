# Synesthesia — Tech Strategy (M1b output)
Status: DRAFT · Feeds ARCHITECTURE_BLUEPRINT (M2) · Verified against live sources Aug 2026

## 1. Stack pins (app side; native side pinned separately)
| Concern | Pin | Why |
|---|---|---|
| Playback/export | **Media3 1.11.0** | current stable; GlEffect/GLShaderProgram extension point, OverlayEffect/TextOverlay, edit-list lossless trim, InAppMp4Muxer default (+Fragmented variant for resumable segments), media3-ui-compose state helpers |
| UI | **Compose BOM 2026.08.00**, material3 1.4.0, adaptive 1.3.0-rc01 | strong-skipping default ON; stability config file for time/collections |
| Navigation | **Navigation 3 `1.1.6`** (typed NavKey + kotlinx.serialization) + lifecycle-viewmodel-navigation3 2.11.0 | Nav2 is legacy; Nav3 stable |
| DI | **Hilt 2.60.1** (KSP) | Android-bound app; KMP-DI buys nothing |
| Library DB | **Room 3.0.0** (`androidx.room3`, KSP, coroutine-first) | indexed/FTS queries impossible in DataStore; serious players need it |
| Settings | **DataStore Preferences 1.2.1** | prefs, EQ blobs, entitlement cache |
| Billing | **billing-ktx 9.1.0** | ≥8 mandatory Aug 31 2026; 9.x covers showInAppMessages transactional flows |
| Toolchain | JDK 26 · AGP 9.3.x (new DSL only) · Gradle 9.7.x · Kotlin 2.4.10 + KSP `2.4.10-x` | per REBUILD_PLAN L7 |
| Modules | ~12–15: `:core:{audio,visualizer,database,designsystem,billing,common}` + `:feature:{player,library,visuals,studio,settings}` + central `:navigation` | NiA 2026 shape; anti-over-granularity guidance |

## 2. Render engine decisions
- **Spine = hand-rolled GLES 3.0 layer** (projectM needs a plain current-context EGL anyway; The-Forge dropped GLES, Filament fights procedural styles).
- **bgfx via JoshuaBrookover/bgfx.cmake** ONLY for instanced/compute-assisted 3D styles (Bass Vortex, Cymatic Cathedral): renders into an FBO texture the compositor blends beside raw-GL/projectM outputs. Offline shaderc compile (`--platform android -p 300_es`) wired as Gradle task, bin2c embed. Static ~2–3 MB stripped per ABI.
- **Deterministic offline pattern** (validated precedents: libprojectM external-clock API #740/#817, butterchurn-export, kymograf, Shadertoy headless renderers): EGL pbuffer/surfaceless → offscreen FBO → clock advances exactly 1/fps per iteration → audio features indexed by frame number → PBO-ring async readback → encoder. **Tiled rendering valve** for frames above GPU max (4K on constrained GPUs). Determinism scope: same-device exact; cross-device bit-exactness impossible at half-float sims (documented, Tier-1 parity runs same-device).
- **Upscale**: live-only at 0.5–0.75× render scale → FSR1 EASU+RCAS mobile fp16 variant (atyuwen optimization ≈3× cheaper than stock); offline renders native-res, no upscale.
- **Fluid**: port PavelDoGreat/WebGL-Fluid-Simulation structure (RG16F/R16F direct ES3 formats) with piellardj/navier-stokes-webgl Stam cleanliness; legacy repo's sim is already close — re-grade, don't reinvent.
- **Particles**: primary = fragment-sim RGBA16F ping-pong + instanced quads with `texelFetch(gl_VertexID)` vertex-pull (tiler-friendly, identical live/offline path); transform feedback only as legacy fallback path.
- **projectM pin conflict — RESOLVED**: upstream v4.1.7 (Jul 2026) lacks `render_frame_fbo`; our bridge requires it (master-only, @since 4.2.0-unreleased). Decision: **keep pinned commit `2f244141`**; evaluate cherry-picking 4.1.6/4.1.7 preset fixes onto that base; revisit the moment 4.2.0 tags. Upstream will NOT raise the GLES floor (WebGL2 support pins ES 3.0).

## 3. Player build references (mine, don't copy license-tainted code)
- Primary session/service reference: **androidx/media `demos/session`** (official MediaLibraryService) + **FoedusProgramme/Gramophone** (GPL-3.0, most active clean Media3 player, Jun 2026).
- Metadata/tags: **Auxio**'s Musikr (taglib) pattern for fast folder scans.
- Library DB shape: Retro/Metro Room schemas (playlist joins, history, queue restore); InnerTune-lineage Room relations OK to read — **their YouTube-scraping paths are FORBIDDEN** (Synesthesia stays local-only).
- EQ: pure-Kotlin float32 biquads as `BaseAudioProcessor`s in DefaultAudioSink chain (composes with speed/pitch/skip-silence; StreamMetadata gives per-device preset context). Offload OFF by default (offload bypasses AudioProcessors entirely). No Oboe/AAudio C++ — zero benefit for latency-insensitive playback.
- Widgets/Android Auto deferred post-v1 (cost: RemoteViews quirks ≈2× est.; Auto = separate Google review 4–8 wks). Session service built Auto-ready structurally from day 1 (free).

## 4. Monetization operations
- Raw PBL 9.1.0 implementation behind PurchasePort + **RevenueCat SDK free tier day-one** (free ≤$2.5k MTR; kills server requirement for validation/webhooks) + RC webhook mirrored into own DB weekly-dump to prevent lock-in; reassess >$10k MTR.
- Patterns codified: queryPurchasesAsync every launch/resume · acknowledge only post-check (3-day auto-refund law) · linkedPurchaseToken chain on upgrades · proration replacement params · PendingPurchasesParams incl. prepaid · honor isSuspended() · showInAppMessages(TRANSACTIONAL) each launch (platform-capped 1×/7 days).
- Paywall cadence (blends owner law + SOSA 2026 benchmarks): owner's boot/reopen unlock sheet (3s timer) **capped 1/session + 1/4h**; otherwise contextual feature-gate nudges only (touchpoint of gated feature, once per session); evergreen settings entry; NO 3-day trials ever (55% cancel Day 0); if trials come later → annual-only, 7–14 days.
- Gotcha list carried into impl tickets: BILLING_UNAVAILABLE reclassification, nullable getLinkUri(), reinstall→restore test path, SOSA refund-inflation flag.

## 5. Export pipeline notes feeding M8
- Transformer resume doesn't exist natively → segment renders at keyframe boundaries → InAppFragmentedMp4Muxer / sample-copy stitching; matches SPEC §4.5 chunk-cache resume design.
- Watermark via OverlayEffect/TextureOverlay (free tier D-1).
- Loudness normalization via custom AudioProcessor limiter pre-mux.
- VP9-alpha/GIF/WebP lane: software encoders from NDK-pinned sources; budget accepted in SPEC §4.5.
- Raw-MediaCodec fallback knowledge mined from LiTr/deepmedia Transcoder if Transformer blocks a format.

## 6. Risks register (top 5)
1. projectM 4.2.0 untagged → pinned-master dependency until tag lands (mitigation: provenance gates + cherry-pick review).
2. bgfx shaderc pipeline friction (one-time; contained to 2 styles).
3. Transformer @UnstableApi churn → wrap ALL usage behind `:core:export` facade so upgrades touch one module.
4. 4K thermal ceilings on mid SoCs → SPEC §4.5 pre-flight budgets + confirm gates.
5. GPL mining contamination → reference-read only, zero copy-paste from GPL players; clean-room notes required in PR bodies touching player code.
