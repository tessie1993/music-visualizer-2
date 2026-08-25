# Legacy Codebase Verdicts (M1 output)
Source: full-tree comparison vs SPEC_BLUEPRINT v1.2 + TECH_STRATEGY · Date 2026-08-25
Legacy root: musicviz-project/musicviz · Verdict legend: REUSE / PORT+adapt / MINE-algorithm / REFERENCE-only / REBUILD-fresh / DROP

## Master verdicts

| Legacy asset | Verdict | Target module | Effort | Rationale |
|---|---|---|---|---|
| engine/audio-core/** (~2.2k LOC pure JVM: ReactiveAnalyzer, SampleRing, LogBands, SuperFlux, OnsetPeakPicker, TempoTracker, BeatGrid, BarTracker, DrumChannels, StructureTracker, FeatureRing, Envelope, WindowTable) | PORT+adapt | :core:audio | S | Zero Android imports; matches SPEC §2.2 nearly line-for-line; only legacy assumption is realtime hop-clock coupling |
| Chromagram, KeyDetector, Mfcc, MelBank, SpectralContrast, StereoField, MidSideWindow | PORT+adapt | :core:audio | S | Feeds KeyPalette theming + stereo-width modulation |
| engine/audio-android (PcmTap TeeAudioProcessor sink, SinkClockDriver) | PORT+adapt | :core:audio | S | Already Media3-tap based; becomes player source of single-writer ring |
| engine/scenes/analysis (OfflineAnalyzer 295, FeatureTimeline 181, AnalysisCache 185, FrameAccumulator) | MINE-algorithm | :core:audio | M | Decode+cancel pattern = offline analyzer/cache of §2.2 |
| render/VisualSafety.kt + FlashBudget | REUSE-as-is(near) | :core:visualizer | S | D-SAFE-1 pre-implemented as non-defeatable WCAG clamp applied LAST — port verbatim + tests |
| render/Lfo.kt, Adsr.kt, ParamScope/SceneParams/ParamKeys | MINE-algorithm | :core:visualizer | M | Precursor of §2.3 modulation routes; rebuild as JSON, keep semantics |
| fluid/water/curl/cymatics Kotlin (~3.8k: FluidSim 515, FluidBuffers/Look/Emitters, RippleSim, Cymatics*) | PORT+adapt | :core:visualizer styles | M | Sim close to PavelDoGreat target; re-grade look, wire params/tier hints |
| res/raw/*.glsl (82 files, 12.4k lines incl lib_* preludes, composite_frag, projectm_grade_frag) + ShaderScene/CompositeGrade/GlUtil | REFERENCE-only | style families | M | Golden renders + grade targets; prettify pass replaces foundation |
| offscreen/* (OffscreenCompositor/Renderer, FramePacer, ThermalGovernor, ProgramBinaryCache) | MINE-algorithm | render-engine | M | Direct precedents for offline mode, §2.4 governor, binary caching |
| engine/gl probe (GlProber 1074, GlTier, ComputeSupport, FormatPolicy, WorkGroupSize, CapabilityCache) | PORT+adapt | render-engine | S | Capability probing feeds auto-tier + Q-5 GLES gates wholesale |
| engine/gl/util/BestEffort.kt (18 LOC) | REUSE-as-is | :core:common | S | Perfect |
| tools/projectm_jni.c + ProjectMEngine.kt + ProjectMScene.kt | REUSE-as-is | :feature:visuals bridge | S | FBO-target design is exactly SPEC's no-fb0-copy requirement |
| jniLibs .so blobs + SHA256SUMS (commit 2f244141, NDK28) | REFERENCE-only | build provenance | M | Rebuild under pinned NDK r29; keep SHA256SUMS CI-gate format |
| data/TemplateFormat.kt (603) + VideoTemplate.kt | MINE-algorithm | schema registry | M | ForeignFields/Tolerant<T>/version-monotonic machinery IS SPEC §2.5, battle-tested |
| data/AtomicWrite.kt (+RingLog) | REUSE-as-is | :core:common | S | fsync→tmp→rename→quarantine drop-in |
| PresetStore/TemplateRepository/HistoryStore/LfoStore/MilkPackImporter (26 files) | MINE-algorithm | stores | M | Keep JSON-shape lessons + import-laundering/safeFileName; storage rewritten on DataStore/Room |
| audio capture family (PlaybackCapture(Service), MicCapture, AudioBus, AudioFxController, AiffExtractor) | MINE-algorithm | :feature:player capture | M | Projection-revoke callback flow, disclosure ordering, AIFF fallback |
| export/LoudnessMeter(594)+LoudnessTarget(291) | MINE-algorithm | :core:export | S | ITU-R BS.1770-4 integrated + true-peak → differentiator #4 directly |
| export/LoopRender/LoopExtend (1.4k) | MINE-algorithm | :core:export | M | LoopSpec/seam-crossfade math ports; MediaCodec plumbing replaced by Transformer |
| export/VideoExporter/EncoderSurface/StudioClips/RenderEta/AudioTranscoder | REFERENCE-only | :core:export | L | Raw-MediaCodec fallback knowledge only |
| editor/AutoCut.kt (287, pure Kotlin on FeatureTimeline) | PORT+adapt | :feature:studio | S | Differentiator #3 nearly verbatim once timeline ports |
| editor/Timeline/Keyframes/Markers | MINE-algorithm | :feature:studio | M | Undoable-document patterns; UI rebuilds |
| ui/theme/* + ThemePackCatalog (1997) + drawable-nodpi 960 webp + raw 30 wavs | REFERENCE-only | :core:designsystem | S | Canonical inventory for regenerated packs; pack manifest shape |
| Compose UI (~24k LOC AppShell/VisualsHub/etc.) | REBUILD-fresh | features | – | New crystal-glass language |
| playback/* + billing/{AdPolicy,Entitlement} | REBUILD-fresh / ABSORB seams | :feature:player, :core:billing | L | Owner-declared broken; entitlement seams absorbed per SPEC §7 |
| build-logic convention plugins | DROP (obsolete under AGP9) | new DSL | S | BUT mine geode.provenance GPL-origin-marker gate concept |

## Deleted-test characterization sources (git show 1c76a0fe^)
Top 15 by port value: LiveOfflineParityTest · CorpusOracleTest(+tools/oracle/generate_corpus.py) · PcmRingBuffer{Test,CursorTest,StereoTest} · FlashBudgetTest+VisualSafetyTest · FluidMathTest/FluidVorticityMathTest/CurlFieldMathTest/WaterMathTest/RippleMathTest · CymaticsMathTest · CompositeUniformParityTest/SceneUniformParityTest · AdsrTest/LfoWrapTest · Descriptor/Harmony/Timbre OracleTests · FeatureTimelineTest/FrameAccumulatorTest · PlaybackMathTest/RenderClockWrapTest · MidSideParityTest/PcmTapParityTest/PcmFanoutTest · AtomicWriteTest/StoreDurabilityTest · ExportDeterministicQualityTest/ExportCompositeGradeTest · SafeFileNameTest/PresetRoundtripTest

## Hidden gems (must not lose)
VisualSafety.kt · TemplateFormat tolerance machinery · tools/shaderpreview (browser WebGL twin for iteration) · provenance CI gate · oracle corpus generator · ProgramBinaryCache

## Port priority (feeds M4+) 
1 audio-core chain → 2 VisualSafety/FlashBudget → 3 AtomicWrite/BestEffort/RingLog → 4 TemplateFormat tolerance→schema registry → 5 PcmTap/capture → 6 OfflineAnalyzer/FeatureTimeline/cache → 7 fluid/water/cymatics sims+shaders → 8 projectM trio → 9 BS.1770 loudness suite → 10 AutoCut + modulation model

## Portable volume
~19–23k Kotlin + 82 GLSL (12.4k lines) as re-grade assets. UI/player (~30k) correctly dropped.
