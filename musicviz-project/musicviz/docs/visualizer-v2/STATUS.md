# V2 slice log

The one place that answers "where is the overhaul". [`MASTER_PLAN.md`](MASTER_PLAN.md) §2
is the protocol this file obeys: one slice at a time, through

`LOCKED → DISCOVERY → SPECIFIED → RED → IMPLEMENTING → VERIFYING → REVIEWING → READY_TO_COMMIT → COMPLETE`

and nothing below `COMPLETE` may be running twice. `EngineV2PlanAuthorityTest` enforces
the shape of this file — the state names, one unfinished slice, and the full §2.3 field
set on every entry — so a session that skips a field fails the build rather than leaving
the next session to guess.

Newest slice first.

---

## V2-3-01: fixture corpus and oracle generator

State: COMPLETE

Goal: Phase 3's foundation — generated PCM fixtures with expected values from an external
oracle, so later analysis work is measured against something other than itself.

User-visible effect: none. Test infrastructure. What it changes is that `StereoField`'s
correctness is now checked against a computation the app does not own.

In scope: `tools/oracle/generate_corpus.py`; 11 fixtures and a manifest under
`app/src/test/resources/corpus/`; `Corpus` and `CorpusOracleTest`.

Out of scope: libebur128 loudness reference values — a second oracle and its own slice. Real
music excerpts: none are available here, and a corpus that claimed them without shipping them
would be worse than the gap. Both recorded in the manifest's `notCovered`.

Files expected to change: `tools/oracle/generate_corpus.py`,
`app/src/test/resources/corpus/*`, `app/src/test/java/dev/musicviz/analysis/{Corpus,CorpusOracleTest}.kt`.

Compatibility contract: no production code changes.

External source/provenance entries: **librosa** (ISC) and **libebur128** (MIT) were already in
`provenance.json` at ORACLE tier with verified licence evidence and pinned commits, so §2.1
rule 9 was satisfied before this slice began — checked rather than assumed. librosa runs at
fixture-generation time only and never enters the APK; the manifest says so and the generator's
module docstring says so.

Tests written first: not applicable — the deliverable is the oracle. Six fault injections are
the evidence.

Benchmark or visual evidence: not applicable.

Rollback: revert the one commit. Nothing in production depends on the corpus.

Risks: 904 KiB of binary fixtures in the repository, and a regenerated corpus is a full rewrite
of those bytes. Mitigated by keeping fixtures short and int16 rather than float32, and by
`generatorVersion`, which makes a stale corpus a visible mismatch. Regenerating without
regenerating the expectations fails the checksum test rather than silently comparing against the
wrong numbers.

Commands and results: below.

Review findings: two, both mine.

**My oracle computed a different quantity than the app.** For `stereoWidth` I wrote
`sqrt(ss)/sqrt(mm)`, while `StereoField` defines it as `s / (m + s)` over RMS magnitudes. On the
anti-phase fixture — no mid at all — my version divided by zero and reported the **widest
possible signal as width 0**, and the guard I had written made that look deliberate. Caught by
reading the generated manifest instead of trusting that a green generator meant correct values.
Correlation is now computed the textbook way from L and R, which makes it a genuine independent
check of the mid/side identities `StereoField` uses; width mirrors the app's own definition,
because it is not a standard quantity, and the manifest says which is which.

**A fault injection silently did not inject.** My `sed` pattern did not match the real source
(`((mm - ss) / denom).coerceIn(...)`, not `(mm - ss) / denom`), and `sed` exits 0 when it matches
nothing, so the `||` fallback never ran and the run reported nothing at all. Noticed because the
output was empty rather than a failure. Re-run with an assertion that the pattern exists and a
checksum that the file changed — the correlation algebra then failed three separate ways.

Commit: `test(analysis): a fixture corpus with expected values from an external oracle`

Next slice: V2-3-02 — the FFT/window graph with center-aligned hops.

### What the oracle catches

Six faults injected, all caught:

| Fault | Failing test |
|---|---|
| one corrupted byte in a fixture | corpus matches its manifest |
| correlation: mid/side sign flipped | correlation agrees with the oracle (+ anti-phase collapse) |
| correlation: cross term dropped | correlation agrees with the oracle |
| correlation: side energy omitted | correlation agrees with the oracle (+ anti-phase collapse) |
| width normalised by the wrong total | width agrees, including where mid is zero |
| band table starting an octave and a half too high | a pure tone lands in the band that contains it |

### The corpus

11 fixtures, 904 KiB, 22,050 Hz, raw interleaved int16 — the format the tap itself delivers, so
both sides dequantise identically by `x / 32768`.

silence · impulse · tone_440 · sweep · am_4hz · clicks_120bpm · tempo_ramp · stereo_antiphase ·
stereo_wide · stereo_identical · discontinuity

### Verification

| Command | Result |
|---|---|
| `librosa` install into a venv | 0.11.0, with numpy 2.4.6 / scipy 1.17.1 / soundfile 0.14.0 |
| librosa smoke test (RMS, centroid, beat track) | real values, not stubs |
| `CorpusOracleTest` | 9 tests, 0 failures, 0 skipped |
| corpus checksums against the manifest | 11/11 match |
| `checkAll` | BUILD SUCCESSFUL across all seven projects |

---

## V2-2-05c: serve the analyzer from the V2 ring — the Phase 2 gate

State: COMPLETE

Goal: close the Phase 2 gate — "the legacy analyzer can consume the new ring through a bridge
with no feature or playback regression; callback allocation benchmark is clean."

User-visible effect: none intended, and one deliberate change (below). Every band, beat, chroma
and stereo reading now comes from `SampleRing` through `MidSideWindow` instead of from
`PcmRingBuffer`'s capture-time downmix. V2-2-05b measured the two at a delta of exactly zero for
mono and stereo, which is why this is a wiring change rather than a rewrite.

In scope: `AnalysisEngine` taking a `SampleRing` and reading through `MidSideWindow`; extracting
its per-hop work into `AnalysisEngine.Pass`; `PlaybackSession` handing it `sampleRing`.

Out of scope: `PlayerViewModel.latestPcm`, which feeds projectM through a *cursor* read rather
than a latest-window one and therefore needs a different bridge — its own slice. Deleting
`PcmRingBuffer`, which still has that reader, per §12.

Files expected to change: `app/src/main/java/dev/musicviz/analysis/AnalysisEngine.kt`,
`app/src/main/java/dev/musicviz/playback/PlaybackEngine.kt`, plus two test call sites.

Compatibility contract: feature values unchanged for mono and stereo. Surround differs, per
`adr/0003`.

External source/provenance entries: none. Checked rather than assumed: §3.1's ledger has nothing
that applies to this step. `librosa` and `libebur128` enter at V2-3-01 as **ORACLE** tier —
fixture generation outside the runtime, never linked.

Tests written first: no, and the fault injections are why that mattered. See below.

Benchmark or visual evidence: not applicable; nothing new runs on the callback.

Rollback: revert the one commit.

Risks: below.

Commands and results: below.

Review findings: **the analyzer's core loop had no test at all, and I only found that by
injecting faults into it.** Hard-wiring the stereo reading to `MONO`, and building the waveform
out of the side channel instead of the mid, both left the entire suite green — 1,290 tests, no
failures. The FFT could have been fed the wrong signal and nothing would have said so.

The cause was structural, not an oversight in coverage: the work lived inside a `while (true)`
loop on `Dispatchers.Default` behind a wall-clock deadline, so no test could step it. Extracted
into `AnalysisEngine.Pass`, which owns the window and the per-hop buffers and does one tick per
call. The loop is now six lines and the work is reachable.

`AnalysisPassTest` covers it: a tone raises the bands it occupies and not all of them, the
waveform matches the mid-only signal rather than a side-contaminated one, wide content reads
wider than narrow, and a mono source reads correlation 1 rather than decorrelated.

Commit: `refactor(analysis): serve the analyzer from the V2 ring, and make its hop testable`

Next slice: Phase 3 — V2-3-01, the fixture corpus and oracle generator.

### The one behaviour that changes

`PcmRingBuffer`'s write index was monotonic for the life of the process, so after a seek it kept
serving a window that still held pre-seek audio. `SampleRing` restarts its numbering at each
epoch, so for one window after a seek or format change — about **43 ms** at 48 kHz — there is
nothing to read and the analyzer publishes nothing. The visuals hold their last frame instead of
briefly showing the previous track's audio.

Asserted rather than left to be discovered: `a new epoch withholds the window until it has
refilled`.

### Verification

| Fault injected | Before `Pass` | After |
|---|---|---|
| stereo field hard-wired to `MONO` | **passed** | FAILED |
| waveform built from the side channel | **passed** | FAILED |
| FFT fed the side channel | not tried | FAILED |
| analyzer pointed at a ring nothing writes | **passed** | FAILED |

| Command | Result |
|---|---|
| `AnalysisPassTest` | 5 tests, 0 failures |
| `checkAll` | BUILD SUCCESSFUL across all seven projects |
| `// FAULT` scan of engine and app sources | clean |

### Phase 2 gate

| Requirement | State |
|---|---|
| legacy analyzer consumes the new ring through a bridge | **done** — `MidSideWindow` |
| no feature regression | mono and stereo bit-identical; surround per `adr/0003` |
| no playback regression | capture path unchanged; every producer feeds both rings |
| callback allocation benchmark clean | 0 bytes/callback for tap and ring write |

---

## V2-2-05b: make the V2 ring servable, and prove it against the legacy one

State: COMPLETE

Goal: everything the reader migration needs, minus the migration. The V2 ring must receive audio
from **every** producer, and must be able to hand back the exact pair today's analyzer reads.

User-visible effect: none. No reader has moved. What changed is that live input — microphone and
playback capture — now reaches the V2 ring as well, and there is a proof that the two rings
produce identical numbers.

In scope: `SampleRing.sourceChannelCount` and `snapshotLatest`; `MidSideWindow` in `audio-core`;
`PcmRingBuffer` implementing `PcmSink`; `AudioCapturePump`/`MicCapture`/`PlaybackCapture`/
`CaptureController` taking a sink rather than a buffer; `PlaybackSession.captureSink` as the one
definition of where captured audio goes; `adr/0003`.

Out of scope: repointing `AnalysisEngine` and `PlayerViewModel` — V2-2-05c, which closes the
Phase 2 gate. Deleting `PcmRingBuffer` is later still, per §12.

Files expected to change: `engine/audio-core/…/{SampleRing,MidSideWindow}.kt`,
`app/src/main/java/dev/musicviz/audio/{PcmRingBuffer,AudioCapturePump,MicCapture,PlaybackCapture}.kt`,
`app/src/main/java/dev/musicviz/ui/{CaptureController,PlayerViewModel}.kt`,
`app/src/main/java/dev/musicviz/playback/PlaybackEngine.kt`, `docs/visualizer-v2/adr/0003-*.md`.

Compatibility contract: every reader still reads `PcmRingBuffer`, unchanged. `PcmRingBuffer`
gains a `write` alias so producers can be handed a destination; nothing else about it moves.

External source/provenance entries: none. No external repository is involved at this step —
`librosa` and `libebur128` enter at V2-3-01 as **ORACLE** tier, generating fixtures outside the
runtime, never linked.

Tests written first: no. The parity proof is the deliverable, and it had to be written against
the implementation to mean anything. Four fault injections stand in, and the fourth found a real
hole.

Benchmark or visual evidence: not applicable — no new work on the callback beyond V2-2-05a's,
already measured at 0 bytes.

Rollback: revert the one commit.

Risks: the mono downmix for **surround** sources changes when the readers move. Legacy folds all
decoded channels; the V2 ring keeps the front pair by the design §5.1 asks for, so its mid is the
mean of two. Measured, not assumed — bit-identical for mono and stereo, differing on more than
half the samples of a five-channel fixture. `adr/0003` records the decision and why the front
pair is the more faithful answer on a two-speaker device.

Commands and results: below.

Review findings: two, both mine, both found by injecting faults rather than by re-reading.

**A mono source would have been halved, silently.** `SampleRing` keeps two channels and a mono
source leaves the second at zero, so a bridge averaging the ring's channels returns exactly half
amplitude — right shape, right length, no error. The ring now records the *source's* channel
count, and `mono is not halved by the silent second channel` fails without it.

**I wrote the dual-write lambda twice** — once for the tap, once for the capture controller —
which is precisely how two producers end up disagreeing about where audio goes. Collapsed into
`PlaybackSession.captureSink`, one definition for all three producers. The fault "capture feeds
only the legacy ring" passed every test until that test existed.

Commit: `feat(audio): make the V2 ring servable, and prove it against the legacy one`

Next slice: V2-2-05c — repoint `AnalysisEngine` and `PlayerViewModel`, closing the Phase 2 gate.

### Parity, measured

| Source | Result |
|---|---|
| stereo 16-bit | mid and side **bit-identical**, delta 0 |
| stereo float | mid and side **bit-identical**, delta 0 |
| mono | **bit-identical**; side identically zero |
| five-channel | differs on >½ of samples — the `adr/0003` divergence, pinned so it cannot widen unnoticed |

### Verification

| Command | Result |
|---|---|
| fault: mid averages the ring's channels, not the source's | FAILED (2 tests) |
| fault: side is the difference rather than half of it | FAILED (2 tests) |
| fault: the ring never records the source channel count | FAILED (3 tests) |
| fault: capture feeds only the legacy ring | **passed at first** — nothing covered the capture wiring; FAILED after `live input reaches both rings` |
| `PlaybackCaptureContractTest` source gate | caught the `ring.writeInterleaved` → `sink.write` rename and was updated in the same change, per `CLAUDE.md` |
| `checkAll` | BUILD SUCCESSFUL across all seven projects |

---

## V2-2-05a: write the V2 ring in production, on the tap's own numbering

State: COMPLETE

Goal: the first half of the Phase 2 gate. `SampleRing`, `RingReader` and `beginEpoch` have
existed since V2-2-02 with no production caller — a ring nothing writes proves nothing about a
ring. This gives it a writer, and joins its epoch to the tap's generation so a frame index and a
clock segment mean the same thing.

User-visible effect: none, deliberately. Every consumer still reads `PcmRingBuffer`; both rings
are fed. Switching the readers is V2-2-05b, and it has to be provable against captured features
rather than bundled into the write path.

In scope: `PlaybackSession.sampleRing`, the tap's `PcmSink` writing to both, the boundary
listener fanning out to the ring and the clock, and the callback-allocation benchmark V2-2-02
called for and never wrote.

Out of scope: repointing `AnalysisEngine` and `PlayerViewModel` at the new ring; the
latest-window read `RingReader` still lacks; the mid/side derivation moving to the reader; the
`AudioCapturePump` second-writer question. All V2-2-05b. Deleting `PcmRingBuffer` is later
still, per §12.

Files expected to change: `app/src/main/java/dev/musicviz/playback/PlaybackEngine.kt`,
`app/src/test/java/dev/musicviz/playback/PlaybackEngineTest.kt`,
`engine/audio-android/src/test/kotlin/dev/musicviz/engine/audioandroid/PcmTapTest.kt`.

Compatibility contract: `PcmRingBuffer` and every reader are untouched. One extra write per
callback, measured.

External source/provenance entries: none.

Tests written first: no — the shape was known, the hazard was not. Four fault injections are the
evidence, and one of them is the hazard below.

Benchmark or visual evidence: `SampleRing.write` through the real tap — **0 bytes per callback**.

Rollback: revert the one commit.

Risks: the capacity choice is load-bearing and its failure mode is severe. `SampleRing.write`
**requires** each write to fit inside the reader runway and throws if it does not — on the
playback thread, inside `AudioProcessor.flush`, which stops playback. The tap delivers at most
one staging chunk (4,096 frames) per write, so the runway must exceed it; at 65,536 frames of
capacity the default quarter-runway is 16,384. Pinned behaviourally rather than by reading the
constants: `a decoder buffer far larger than the tap's staging window still fits the ring`
pushes 40,000 frames through the real tap, and fails if either constant moves.

Commands and results: below.

Review findings: none new. This slice came out of an adversarial review of V2-2-04b, which
identified it as the true next step by checking the tree rather than the slice log — the Phase 2
gate names "the legacy analyzer can consume the new ring through a bridge", and the ring had no
producer at all.

Commit: `feat(audio): write the V2 ring in production, on the tap's own numbering`

Next slice: V2-2-05b — serve the legacy analyzer from the ring, which closes the Phase 2 gate.

### Verification

| Command | Result |
|---|---|
| fault: the V2 ring is never written | FAILED (2 tests) |
| fault: the ring never starts a new epoch | FAILED (2 tests) |
| fault: capacity too small for the tap's staging chunk | FAILED |
| `SampleRing.write` allocation through the tap | 0 bytes/callback |
| `checkAll` | BUILD SUCCESSFUL across all seven projects |

---

## V2-2-04b: drive the presentation clock from the audio sink

State: COMPLETE

Goal: make V2-2-04a's clock live. Something has to observe speed changes, seeks, source
changes and skipped silence and append a segment at the right input frame.

User-visible effect: none. Nothing consumes `presentationClock` yet — the bridge to `SampleRing`
is the Phase 2 gate slice — so this adds a producer with no consumer, deliberately, in that
order. What changes is that the mapping is now fed by the pipeline that actually applies speed
and removes silence, rather than by nothing.

In scope: `SinkClockContracts.kt` and `SinkClockDriver.kt` in `:engine:audio-android`;
`PcmTap.boundaryListener`; `MvzAudioProcessorChain` raising the hooks and exposing its skip
counter; a defaulted `hooks` parameter on `TapRenderersFactory`; `PlaybackSession` owning the
clock and driver.

Out of scope: V2-2-04's third bullet, "compare predicted versus presented position" — that is
device work and is named **V2-2-04c** below. Locating skipped-silence spans (**V2-2-04d**).
Wiring the tap to `SampleRing`. Deleting `PcmTapSink`.

Files expected to change: `engine/audio-android/src/main/kotlin/dev/musicviz/engine/audioandroid/{SinkClockContracts,SinkClockDriver,PcmTap}.kt`,
`engine/audio-core/src/main/kotlin/dev/musicviz/engine/audio/AudioPresentationClock.kt`,
`app/src/main/java/dev/musicviz/audio/{TapRenderersFactory,dsp/MvzAudioProcessorChain}.kt`,
`app/src/main/java/dev/musicviz/playback/PlaybackEngine.kt`, and the docs below.

Compatibility contract: every new constructor parameter is defaulted, so no existing call site
changes. `TapRenderersFactory` is edited in place and `buildAudioSink`'s body is untouched, so
both source-text gates that read it still read what they read before. `handleBuffer` is not
touched, so V2-2-03's zero-allocation gate stands unmodified.

External source/provenance entries: none. No new dependency; media3 was already on this module.

Tests written first: no — this is a driver for behaviour that only exists inside media3, so the
red step was establishing what media3 actually does, from bytecode, before any code was written.
Recorded as a §2.1 rule 1 testability exception. Nine fault injections are the substitute
evidence, and two of them found real defects (below).

Benchmark or visual evidence: allocation per boundary, measured — 368 bytes.

Rollback: revert the one commit.

Risks: below the verification table.

Commands and results: below.

Review findings: three, two of them mine and one a design defect caught before it was written.

Commit: `feat(audio): drive the presentation clock from the sink's own flush points`

Next slice: V2-2-04c — the device comparison, or the Phase 2 bridge.

### Why the sink and not `Player.Listener`

`Player.Listener` was the obvious answer and is the wrong one. Its callbacks arrive on the
application looper, unordered with respect to the tap's frame counter, so they can say *that*
speed changed but not *at which frame* — and they die with the screen while `PlaybackService`
keeps playing.

The chain's own `applyPlaybackParameters` runs on the playback thread at the stream position
where the parameter takes effect, and `PcmTap.flush` is the last instant at which the ended
generation's frame count and the silence-skipping counter both still exist. One call stack, one
thread, every number simultaneously live.

Verified from the media3 1.10.0 bytecode rather than assumed, because three of the load-bearing
facts are undocumented:

| Question | Answer, from `javap` |
|---|---|
| Does enabling AudioTrack playback parameters skip the chain's speed hook? | **Yes** — `applyAudioProcessorPlaybackParametersAndSkipSilence` returns before the chain call when `useAudioOutputPlaybackParams()` is true. Not enabled in this app; now guarded by a test and used as a detector. |
| Is `getSkippedFrames` output frames below Sonic? | **No** — input frames of its own stage, which the pass-through tap makes identical to tap frames. The conversion is the identity, not an approximation. |
| Does `skippedFrames` survive a flush? | **No** — `onFlush` zeroes it, *after* the tap's flush in the same pipeline pass. That ordering is why the counter can be read at all. |

I had recorded the third as the opposite, from misreading `onReset` as `onFlush`. It was caught
by re-deriving it instead of trusting the note.

### Review findings

**1. The design reproduced V2-2-02a's bug, and its own test would have pinned it.** The first
draft called the boundary listener *before* resetting the tap's counters, so the clock's newest
epoch became N+1 while `framesWritten` still held N's total. A consumer reading the clock then
the tap maps that count into the new segment and gets a confident presentation time an entire
track away — the same shape as `SampleRing` publishing its frame count after its slot stores:
a frontier out of step with the data it describes, failing as plausible output. The listener now
runs after both stores, with the ended values passed as arguments.

**2. The test for that was vacuous, because of the guard added for a different reason.** The
listener call is wrapped so a clock fault cannot stop playback. The first version used
`runCatching`, which catches `Throwable` — including the `AssertionError` from the assertions
made inside the listener. The fault injection passed. Narrowed to `RuntimeException`, which
still catches every realistic driver bug and lets a test failure through; the assertions moved
outside the callback as well, and a `boundaryFailures` counter makes a swallowed fault countable.

**3. `append` copied the segment list twice.** Found by the allocation budget failing at 944
bytes: `(segments + segment).takeLast(cap)` builds the whole list, then builds it again. One
pass instead — 368 bytes, on the playback thread, where the second copy was least welcome.

### What this cannot know

*Where* silence was removed. Media3 announces the toggle, never a skip event. So the total is
folded into the next anchor and every segment carries `skippedInputSamples = 0`:

| Property | Value |
|---|---|
| Segment anchors, skip-silence off | exact |
| Segment anchors, skip-silence on | ahead by under 100 ms of input per drain boundary — see below |
| Segment interiors, skip-silence on | late by (silence removed so far within the segment) / slope; corrected at the next boundary |
| Segment interiors, skip-silence off | identically zero — and it is off by default |

The anchor caveat was **missed on the first pass and is a correction to this entry**: the
original said "anchors exact, factor 1". `skippedFrames` is written only inside
`outputShortenedSilenceBuffer`, which `onQueueEndOfStream` also calls; media3 cascades
`queueEndOfStream` in ascending pipeline order, so the tap at index 0 reads the counter before
the stage at index 1 adds the tail it still holds, and the next flush zeroes it. Verified by
listing every write to that field (two in `outputShortenedSilenceBuffer`, one zeroing in
`onFlush`). The driver under-counts removed silence, so the anchor runs ahead, bounded by one
`minimumSilenceDurationUs` of input and zero with skip-silence off.

The mistake is the same one this session has now made three times: a mechanism that is right,
described more confidently than the evidence supports.

### Second correction: three defects found by an adversarial review of this slice

**A refused boundary froze the anchor but not the timeline.** `openSlope = 0.0` runs before both
refusal returns, so the next boundary cannot advance `anchorUs` — but real presentation time did
advance. The driver then *resumed* when the hooks came back, appending at an anchor short by the
whole refused span, and every later mapping returned a confident `At` that was early by it.
Reachable: `useAudioOutputPlaybackParams()` is read per output configuration, so a route change
can flip it and flip it back.

The first fix I wrote made it worse — a test named "the speed verdict recovers when the chain
regains authority", asserting exactly the behaviour that produces the wrong answer. Recovery is
not achievable: the missed span is frames times a slope that was by definition unknown. So the
driver now latches `anchorTrusted = false` and stops appending for good, which leaves consumers
with `StaleEpoch` — "I do not have that" — instead of a plausible number. The old test is
deleted rather than adjusted; it encoded the wrong answer.

**`ClockSegment.fromFormat` sat outside the `try` whose comment claimed it kept exceptions out
of `AudioProcessor.flush`.** All three of its `require`s throw, so the real net was `PcmTap`'s
catch one frame up the stack — which would have swallowed the fault without
`refusedByClockInvariant` ever seeing it, defeating the test that asserts that counter stays 0.
Moved inside. And `speed <= 0f` is **false for NaN**, so a NaN speed passed the guard and threw
from `fromFormat`; it is now `!(speed > 0f)`.

**The test rig modelled a shape the sink never emits.** `parameterChange` raised both hooks *and*
carried the frame count in one boundary. Media3 always produces two: an unhooked drain carrying
the whole ended generation, then a hooked flush carrying zero. Added `speedChange`, which drives
the real pair, and the speed test now asserts three segments where it asserted two.

Also taken from the same review: `clampedSkipExceedingFrames` renamed to
`discardedSkipExceedingFrames` (the code discards, the name claimed the behaviour the slice
deliberately rejected), `unmeasuredBoundaries` gated on `endedFrames > 0` (it fired on every
playback start, reporting a hole where nothing was lost), and two comments that contradicted
each other about which flush carries the frames.

| Fault re-injected | Result |
|---|---|
| anchor stays trusted after an unmodelled span | FAILED |
| untrusted anchor still appends | FAILED |
| `speed <= 0f` instead of `!(speed > 0f)` | FAILED |

`PresentationTime.Skipped` is therefore **unreachable in production**: a §5.2 field and a whole
sealed-interface case ship dead. Saying so plainly rather than reporting five of five triggers
implemented — silence-skip discontinuity is not implementable, because no such media3 event
exists. Recorded in `AUDIO_FEATURE_ABI.md` §2.2, not just here.

Also absent: any absolute offset for output latency, and the sink's buffered lead discarded at a
seek, which the anchor still counts. Both are V2-2-04c.

### Verification

| Command | Result |
|---|---|
| `:engine:audio-android:testDebugUnitTest` | 29 tests, 0 failures, 0 skipped |
| fault: listener notified before the counters are reset | **passed at first** — the vacuous test above; FAILED after narrowing the catch |
| fault: listener call not guarded | FAILED |
| fault: skip counter read on every boundary | FAILED |
| fault: speed always treated as authoritative | FAILED (2 tests) |
| fault: speed verdict recomputed on unhooked boundaries | FAILED (5 tests) |
| fault: `append` builds the kept list twice | FAILED (the allocation budget) |
| fault: factory builds a chain without the hooks | FAILED |
| fault: tap never told which listener to report to | FAILED |
| fault: session builds its factory without the driver | **passed at first** — the session test drove the driver directly; FAILED after asserting the chain attached its counter |
| boundary allocation | 368 bytes, budget 600 |
| `checkAll` | BUILD SUCCESSFUL across all seven projects |

### Risks

1. **The anchor counts frames the sink discards at a seek.** Bounded by the sink's buffered
   lead, unmeasured here. First experiment in V2-2-04c.
2. **It runs inside `AudioProcessor.flush` on the playback thread**, so a bad number stops the
   music. Mitigated at both ends: the tap guards the listener call, the driver catches what the
   clock rejects, and both keep a counter. `the driver never builds a segment the clock rejects`
   drives 200 boundaries and asserts the net stays dry.
3. **It rests on media3 internals no API documents.** A version bump can change them silently.
   The detector for the worst case (speed applied at the AudioTrack) is a test; the others would
   surface as a clock that stops appending, which the diagnostics counters name.

---

## V2-2-04a: the segmented presentation clock

State: COMPLETE

Goal: build §5.2's piecewise `AudioPresentationClock` — the map between captured input frames
and the time they are heard — with the properties the plan names: monotonic intervals, round
trips, and gaps surfaced rather than interpolated across.

User-visible effect: **none today, and it is worth being exact about that.** Nothing maps audio
time to visual time in this app yet; the live path takes the newest window on a wall-clock timer
and the offline path is sample-locked, which is the divergence §2 of `ENGINE_V2_PLAN.md`
catalogues. There is no wrong mapping here to fix, because there is no mapping. What this slice
does is make the wrong one hard to write later: the correct mapping now exists, typed, so a
consumer reaching for `sampleTime + offset` has something better to reach for instead.

The subject is real even if the consumer is not. Speed and skip-silence are both live user
settings (`PlaybackSettings`, `PlayerPrefs.skipSilence`), and both sit **below** the tap — so
either one in use already breaks the naive mapping today.

In scope: `ClockSegment`, `PresentationTime`/`InputPosition`, `AudioPresentationClock` and its
immutable `PresentationSnapshot`, in `:engine:audio-core`.

Out of scope: V2-2-04's third bullet, "instrument real Media3 events and compare predicted
versus presented position". Half of it is wiring — a listener translating
`onPlaybackParametersChanged`, `onPositionDiscontinuity` and the chain's
`getSkippedOutputFrameCount` into segments — and half is a device comparison against real
playback, which is the half that says whether the model is right. Split off as **V2-2-04b**
rather than landed with an untested other half.

Files expected to change: `engine/audio-core/src/main/kotlin/dev/musicviz/engine/audio/{ClockSegment,PresentationMapping,AudioPresentationClock}.kt`.

Compatibility contract: nothing existing changes. New types only.

External source/provenance entries: none.

Tests written first: as properties rather than worked examples — a piecewise linear map is
exactly the thing that is right at the sample you picked and wrong either side of a seam. Ten
tests sweep speeds 0.5x–4x and frames 0–1.2M for the round trip, step through 20,000 frames for
monotonicity, and walk the whole skipped span rather than sampling it.

Benchmark or visual evidence: not applicable.

Rollback: revert the one commit.

Risks: the definition of "presentation time" is the whole design, and §5.2 does not spell it
out. Resolved by reading the field list: if presentation meant `currentPosition`, `speed` and a
variable slope would both be dead fields, so it is the output timeline — the clock the listener
hears on. Stated in `ClockSegment`'s KDoc so the next session does not have to re-derive it, and
it is the thing V2-2-04b's device comparison will confirm or refute.

Commands and results: below.

Review findings: two.

**A fault the tests did not catch.** Of five injected faults, four failed a test and one — the
reverse lookup taking the *first* matching segment rather than the last — passed everything.
Every segment before the newest also starts at or before a given presentation time, so that
version answers from a span that finished playing minutes ago, with an epoch and a frame index
that both look reasonable. The ten property tests never used more than one segment on the
reverse path, so the choice was never exercised. `a presentation time inside the newest segment
is answered from it` closes it, and fails under that fault.

That is the argument for injecting faults rather than counting tests: the suite was green, the
properties were real, and one branch of the map was unproven.

**A second finding, which cost the files.** Proving the property tests non-vacuous meant
planting faults in files that were new and untracked, and `git add -N` + `git checkout --`
restores an intent-to-add path to the **empty** index blob rather than to its content — both
implementation files were silently truncated to zero bytes. Caught by `wc -l` on the restore,
not by trusting the trap. V2-1-04d's finding was the same class of mistake by a different
mechanism; the rule that survives both is that a plant is only safe when the restore is a copy
of bytes taken beforehand, and it is verified afterwards.

Commit: `feat(audio-core): the piecewise presentation clock, with the gaps visible`

Next slice: V2-2-04b — drive the clock from Media3 events.

### What the naive mapping gets wrong

| Below the tap | Effect on `sampleTime + offset` | Modelled by |
|---|---|---|
| Sonic speed | 2x compresses a span into half the presentation time | `inputSamplesPerPresentationUs`, `speed` |
| silence skipping | removes spans from the timeline entirely | `skippedInputSamples` → `PresentationTime.Skipped` |
| seek / source change | restarts input numbering under an unchanged output clock | `epoch`, `discontinuityGeneration` → `StaleEpoch` |

### Verification

| Command | Result |
|---|---|
| `:engine:audio-core:test` | 11 clock tests, 0 failures, 0 skipped |
| fault: forward map ignores the skipped span | FAILED |
| fault: epoch check dropped | FAILED |
| fault: slope ignores speed | FAILED (2 tests) |
| fault: backwards timeline accepted | FAILED |
| fault: reverse lookup takes the oldest matching segment | **passed** — the gap above; FAILED after the added test |
| `checkAll` | BUILD SUCCESSFUL across all seven projects |

---

## V2-2-02a: reserve the writer's runway in the sample ring

State: COMPLETE

Goal: fix a torn-read hole in V2-2-02's ring, found when its own stress test failed for the
first time during an unrelated run.

User-visible effect: none yet — nothing reads `SampleRing` in production. What it prevents is
the failure once something does: a window of audio from one lap later, returned as `Ok`, which
every consumer would treat as ordinary audio.

In scope: `SampleRing.oldestAvailable`, a `maxWriteFrames` bound enforced in `write`, the
`RingReader` comment that argued the wrong thing, and the tests that encoded the unsafe
guarantee.

Out of scope: a claim/commit counter pair, which would keep a full capacity readable at the cost
of resting on a memory-ordering argument (a release store does not stop later plain stores
moving before it, so the claim would need a full fence, and `VarHandle` is API 33). Reserving
the runway needs no such argument.

Files expected to change: `engine/audio-core/src/main/kotlin/dev/musicviz/engine/audio/{SampleRing,RingReader}.kt`,
`engine/audio-core/src/test/kotlin/dev/musicviz/engine/audio/SampleRingTest.kt`.

Compatibility contract: the safe read depth shrinks from `capacity` to `capacity -
maxWriteFrames`. No production caller exists to notice.

External source/provenance entries: none.

Tests written first: the failing run was the red. `the oldest trusted frame excludes the
writer's runway` states the invariant deterministically, because the stress test that found the
bug needs the reader preempted at exactly the wrong moment and has not reproduced on demand
since — 0 failures in 9 further runs, with the old formula in place.

Benchmark or visual evidence: not applicable.

Rollback: revert the one commit.

Risks: the runway costs a quarter of the ring by default. At the capacities this is used at
that is thousands of frames of headroom for buffers of ~1,000, which is the same trade
`PcmRingBuffer` already made.

Commands and results: below.

Review findings: the bug is one my own comment argued could not happen. `RingReader` said the
post-copy re-check "beats reserving a fraction of the ring against a case that mostly does not
occur". Both halves were wrong: the re-check reads the same lagging counter it is meant to
outsmart, and the case does occur. The comment is replaced rather than deleted, so the reasoning
that failed is on the record next to what replaced it.

Commit: `fix(audio-core): reserve the writer's runway so a lapped read is a Gap`

Next slice: V2-2-04a — the presentation clock.

### Why the re-check alone could not work

`write` stores its slots and *then* publishes `written`. So between those two, the writer has
already reached frames the counter does not admit to:

| | writer's true frontier | published `written` | old `oldestAvailable` | safe? |
|---|---|---|---|---|
| mid-write | 46400 | 46400 | 45376 | reader at 45376 passes — and reads frame 46400 |
| with the runway | 46400 | 46400 | 45632 | reader at 45376 gets `Gap` |

### Verification

| Command | Result |
|---|---|
| old formula, 3 deterministic tests | **all 3 FAILED** — runway invariant, gap past safe depth, cursor-after-gap |
| new formula, whole `audio-core` suite | 26 tests, 0 failures, 5 consecutive clean runs |
| old formula, stress test alone, 9 runs | 0 failures — which is why it is not the proof |
| `checkAll` | BUILD SUCCESSFUL |

---

## V2-2-03: bridge the PCM tap through audio-android

State: COMPLETE

Goal: move the tap implementation out of `:app` and into `:engine:audio-android`, the module
§4.1 names for "Media3/PCM tap, microphone, device format adaptation", and make it allocation-free
on the audio callback while it goes.

User-visible effect: fewer audio-thread allocations. The tap allocates three objects per buffer
today — `duplicate()`, `asShortBuffer()` and a `FloatArray` whenever the buffer grows — at roughly
40 callbacks a second, on the thread whose deadline is the audio device's. That is the "callback
allocation benchmark is clean" half of the Phase 2 gate.

In scope: `PcmSink` in `audio-core` (the destination the tap writes to, satisfied by `SampleRing`
already); `PcmTap` and `PcmTapFormat` in `audio-android`; `PlaybackSession` wiring; media3 on
`:engine:audio-android`.

Out of scope: moving `TapRenderersFactory` — §12 keeps the player and its Media3 workflow in
`:app`, and only the *tap* is listed as MOVE. Deleting `PcmTapSink` — §2.1 rule 7 forbids
removing a legacy seam in the slice that introduces its replacement, and here it earns the stay:
it is the oracle the parity test compares against. Wiring the tap to `SampleRing` in production:
that is the Phase 2 gate's bridge, after the presentation clock.

Files expected to change: `engine/audio-core/src/main/kotlin/dev/musicviz/engine/audio/{PcmSink,SampleRing}.kt`,
`engine/audio-android/{build.gradle.kts,src/main/kotlin/dev/musicviz/engine/audioandroid/{PcmTap,PcmTapFormat}.kt}`,
`app/src/main/java/dev/musicviz/playback/PlaybackEngine.kt`.

Compatibility contract: sample values, sample order and the format callback are unchanged.
`PcmRingBuffer` keeps its contents byte for byte — proved by comparison, not by inspection.

External source/provenance entries: none. `media3-exoplayer` is already an `:app` dependency at
the same version; this adds an edge §4.1 names explicitly, not a new library.

Tests written first: yes, and the red was a measurement rather than an assertion. A throwaway
probe put 50,000 buffers through the existing `PcmTapSink` with HotSpot's
`getThreadAllocatedBytes` either side: **120.0 bytes per callback**, against **0.003** for an
empty loop on the same meter. That number is the slice.

`PcmTapTest` (13 tests, `:engine:audio-android`) then covers 16-bit and float conversion, order
across the staging seam, byte order, a non-zero buffer position, a trailing partial frame, an
unsupported encoding, generation and frame-count reset, and the end-to-end path into
`SampleRing` read back through `RingReader`. `PcmTapParityTest` (5 tests, `:app`) is §12's
waveform-fixture proof.

Benchmark or visual evidence: allocation per callback — see the table below. Not a §2.1 rule 8
device benchmark: it measures allocation, which is deterministic and device-independent, not
frame time.

Rollback: revert the one commit.

Risks: byte order. The tap is first in the chain, so it receives the decoder's own buffer, whose
`ByteOrder` is whatever the decoder left — not necessarily native. The old code forced
`LITTLE_ENDIAN`; so does this one, and a test feeds a big-endian buffer to prove it.

One behaviour genuinely differs: the old tap defaulted to 16-bit stereo and would convert a
buffer that arrived before any `flush`; this one drops it. Checked rather than assumed — media3's
`BaseAudioProcessor.flush` runs `onFlush` and `DefaultAudioSink` flushes the chain when it
configures, so `queueInput` cannot precede it. Unreachable in production, and the safer answer
where it is not.

Commands and results: below.

Review findings: two, both caught re-reading the diff.

`handleBuffer` was still calling `bytesPerSample(active.encoding)` on every buffer — parsing a
media3 constant on the audio thread, which is exactly what "adapt formats outside the callback"
forbids, three lines under a comment claiming it did not. Encoding is now resolved once into
`PcmTapFormat.sampleWidth`, and the callback's `when` over that enum is exhaustive with no
`else`, so a third sample width fails compilation instead of falling through to silence.

`PcmTapSink` is unreferenced by production code and §2.1 rule 7 keeps it for one more slice.
Left dead and unmarked it is a trap for the next session — two tap implementations, one wired.
Its KDoc now says which one is live, why this one is still here, and which slice removes it.

A third, found on the review pass after the commit: **the one line the slice changed in `:app`
had no test on it.** `PcmTapParityTest` builds its own tap and its own ring, and
`PlaybackEngineTest`'s existing wiring test writes to `session.ring` directly with a comment
saying the tap "only runs inside a real audio pipeline". So both halves were proved and the
join between them — the `PcmSink` lambda in `PlaybackSession` — was not. `tap` is now `internal`
and a test pushes PCM through the session's real tap and reads it back out through
`PlayerViewModel`; pointing the lambda at a different `PcmRingBuffer` fails it.

That is the failure the class exists to prevent, and it would not have crashed, logged, or
failed to compile.

Commit: `refactor(audio): move the PCM tap into audio-android and off the allocator`

Next slice: V2-2-04 — the segmented presentation clock.

### Allocation on the audio callback

Measured with `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`, 20,000 runs after an
equal warm-up.

| Path | Bytes per callback |
|---|---|
| `PcmTapSink` (before) | **120.003** |
| `PcmTap` (after) | **0.007** |
| empty loop — the meter's own floor | 0.002 |
| a loop allocating one `FloatArray(1)` — the meter's control | > 8 |

The 120 bytes were a `duplicate()`, an `asShortBuffer()` view and, whenever a larger buffer
arrived, a fresh `FloatArray`. At roughly forty callbacks a second that is ~5 KB/s of garbage
generated on the thread whose deadline is the audio device's. Afterwards the tap sits within
0.005 bytes of a loop that does nothing at all — the code has no allocation site left on that
path: absolute `getShort`/`getFloat` reads need no view object, and a buffer wider than the
staging array is written as several chunks instead of growing it.

`PcmTapTest` asserts a budget of 8 bytes rather than zero, and proves in the same test that the
meter can see allocation at all, using a control loop that allocates — otherwise "under budget"
and "measuring nothing" would be the same result.

### Correction

The first version of this entry, and the commit message that went with it, said the new tap
measures **exactly 0.0** bytes per callback, and offered as proof that the test fails when its
budget is tightened to `0.0`.

That proof is worthless and the number was wrong. `perCallback < 0.0` is false for *every*
non-negative measurement, so tightening the budget to zero fails whatever the tap does — it
distinguishes nothing. Printing the value instead gives **0.0068**, against a floor of 0.0024
for an empty loop: the residue is the reflective meter's own boxing, not the tap's.

The conclusion the slice rests on is unchanged — 120 bytes to noise — but "exactly 0" was a
claim built from an experiment that could not have produced it, which is worse than a wrong
number. Corrected here rather than quietly restated; the commit that carried it is `2ca6be0`.

### Verification

| Command | Result |
|---|---|
| red probe against `PcmTapSink` | 120.0 bytes/callback — the defect, measured before the fix |
| `:engine:audio-android:testDebugUnitTest` | 13 tests, 0 failures, **0 skipped** |
| `PcmTapParityTest` | 5 tests, ring contents bit-identical to the old tap |
| parity under fault: `32768f` → `32767f` | 4 of 5 FAILED — the float case correctly survives a 16-bit-only fault |
| parity under fault: one frame dropped per chunk seam | FAILED |
| parity under fault: big-endian read | FAILED |
| `PcmTap` allocation, printed | 0.0068 bytes/callback vs a 0.0024 floor |
| session wiring under fault: tap pointed at another ring | FAILED |
| `checkAll` | BUILD SUCCESSFUL across all seven projects |
| whole suite | 1,298 distinct tests (app 1,267, engine 31), 0 failures, 0 skipped — each also re-run in the release variant |

---

## V2-1-04d: compile every shader with a real GLSL front-end

State: COMPLETE

Goal: close the bullet V2-1-04b deferred. That slice checked the include manifest and balanced
braces because no compiler was available; `glslang-tools` is a one-line install, so the excuse
was the container's, not the problem's.

User-visible effect: none today — all 61 standalone shaders already compile. What changes is
when the next mistake surfaces: at `check` instead of as a black scene on whichever device
first selects it.

In scope: `ShaderSources` (one copy of enumeration, include expansion and stage detection);
`ShaderSyntaxTest`; the CI step installing the compiler; `ShaderIncludeManifestTest` moved onto
the shared fixture.

Out of scope: linking vertex and fragment stages as a program, and driver-specific acceptance.
glslang is a front-end — a shader it accepts can still be rejected by a particular driver, and
only V2-0-04's device matrix answers that.

Files expected to change: `app/src/test/java/dev/musicviz/{ShaderSources,ShaderSyntaxTest,ShaderIncludeManifestTest}.kt`,
`.github/workflows/android.yml`.

Compatibility contract: untouched. No production file changes.

External source/provenance entries: none. `glslang-tools` is a build-time tool, not a shipped
dependency, so §2.1 rule 6 does not bite.

Tests written first: not applicable — this is a checker. Fault fixtures replace red-first, and
they are permanent rather than a one-off: `the compiler harness rejects what it claims to
catch` compiles four deliberately broken shaders through the same path as the real ones and
requires each to be rejected, plus the fixture skeleton itself to be accepted.

Benchmark or visual evidence: not applicable.

Rollback: revert the one commit.

Risks: the pass is skipped where `glslangValidator` is absent, which could make it vacuous
everywhere at once. `the CI workflow installs the shader compiler` is the guard — it does not
skip, and it fails if the install step is dropped.

Commands and results: below.

Review findings: **the earlier approach damaged the tree, and that is why the fixtures are the
way they are.** Proving V2-1-04b's checks non-vacuous meant planting faults into real shaders
and restoring them. One of those runs was interrupted between the plant and the restore, and
`aurora_frag.glsl` sat in the working tree with `main()` truncated and its whole body displaced
into a `neverCalled()` function — a black Aurora scene, committed had nobody looked.

Found by reading `git status` rather than trusting the session's own account of what it had
done. Reverted, and re-verified compiling.

The fix is structural, not a resolution to be careful: faults now live in in-test fixture
strings that go through the same `compileSource` path as the real shaders. Nothing has to be
put back, so nothing can be left behind. That also makes the proof permanent instead of a
manual step nobody repeats.

Commit: `test(shaders): compile every shader with glslang, not just balance its braces`

Next slice: **V2-2-03 — bridge the current PCM tap through `audio-android`.**

### What the compiler sees that the manifest cannot

| Fault | Manifest check | glslang |
|---|---|---|
| unbalanced brace | caught | caught |
| unregistered include | caught | caught, as the missing function |
| undeclared identifier | invisible | `'notDeclaredAnywhere' : undeclared identifier` |
| type mismatch | invisible | `cannot convert from ' const float' to ' 3-component vector'` |
| swizzle out of range | invisible | `'z' : vector swizzle selection out of range` |
| unknown function | invisible | `no matching overloaded function found` |

The fourth row is the one worth noting: an unexpanded `//#include` shows up as the missing
function it should have provided, so the compile pass independently checks the include system
that V2-1-04b checks by manifest.

### Verification

| Command | Result |
|---|---|
| `glslangValidator` over all 61 standalone shaders | **61 clean, 0 rejected** |
| `:app:testDebugUnitTest --tests '*ShaderSyntaxTest*'` | **4 tests, 0 failures, 0 skipped** — confirmed per-case, since `assumeTrue` could have hidden a skip |
| `checkAll` | BUILD SUCCESSFUL across all seven projects |
| whole suite | 0 failures, 0 skipped |
| `git status` after the restore | clean; `aurora_frag.glsl` compiles |

---

## V2-2-02: build the sample-indexed ring in audio-core

State: COMPLETE

Goal: give the engine a PCM store where "your buffer was full" and "you fell behind and audio
is gone" are different answers, and where two readers cannot move each other's cursor.

User-visible effect: none. New code in `:engine:audio-core`; no production consumer switched,
and `PcmRingBuffer` is untouched.

In scope: `SampleRing` (storage and write), `RingReader` (an independent cursor),
`RingReadResult` (`Ok`/`Gap`/`Discontinuity`/`NotYetAvailable`), and the §5.1 cases.

Out of scope: switching the tap or the analyzer onto it — V2-2-03 bridges the tap, and §12
holds `PcmRingBuffer` at REPLACE-incrementally until then. Also out of scope: the write-path
allocation benchmark §V2-2-02 asks for. There is no device and no benchmark harness; the write
loop creates no objects, which is reviewable but not the measurement the plan wants, so it
stays open rather than being claimed.

Files expected to change: `engine/audio-core/src/main/kotlin/dev/musicviz/engine/audio/{SampleRing,RingReader,RingReadResult}.kt`,
`engine/audio-core/src/test/kotlin/dev/musicviz/engine/audio/SampleRingTest.kt`.

Compatibility contract: nothing to preserve yet — no caller. The semantics §1.3 pins
(`stereoCorrelation = 1f` for mono, empty chroma meaning no pitch information) belong to
feature extraction, not to the store, and are unaffected.

External source/provenance entries: none.

Tests written first: fourteen, covering wrap, exact capacity, one-past-capacity, gap, cursor
behaviour after a gap, interleaved stereo, mono, epoch, seek recovery, two independent readers,
construction validation, channel mismatch, and a threaded race.

Benchmark or visual evidence: not applicable.

Rollback: revert the one commit. Nothing depends on the module's contents.

Risks: a store built before its consumers can be shaped for imagined needs. Bounded by
implementing only what §5.1 lists and leaving mid/side conversion out — that is a feature's
choice of axes, not the store's job.

Review findings: two, and the second is the one worth reading.

1. **The read had a torn-window hole.** `oldestAvailable` is `written - capacity`, so a reader
   at the tail copies right up to the write head and, with a real audio thread, has its first
   frames overwritten mid-copy — returned as ordinary `Ok`. The old buffer reserves a quarter
   of the ring against this; this reserves nothing. Fixed by re-checking after the copy and
   returning `Gap`, which costs one wasted copy on the rare occasion it happens and wastes no
   capacity the rest of the time.

2. **The test I wrote for that hole was vacuous.** It passed with the guard removed. Writing
   past the reader trips the lag check at the *top* of `read` and never reaches the copy, so it
   was exercising ordinary lag under a name about races. Replaced with a genuine two-thread
   test over 200,000 frames of self-describing data — frame *i* holds value *i*, so any `Ok`
   must satisfy `out[k] == first + k` and a torn window cannot pass as audio. It fails with the
   guard removed and passes with it, which is the proof the first version never gave.

Commands and results: below.

Commit: `feat(audio-core): a sample-indexed ring whose gaps are visible`

Next slice: **V2-2-03 — bridge the current PCM tap through `audio-android`.**

### What the three types replace

| `PcmRingBuffer` today | Why it cannot answer | `SampleRing` |
|---|---|---|
| `copyNewSince` returns `Int`, clamped twice | a full buffer and a lapped reader are the same number | `Ok` or `Gap`, and `Gap` names what was missed |
| `lastCopyEndIndex`, "single-reader only" | a second reader moves the first one's position | the cursor lives in `RingReader`; one per consumer |
| no epoch anywhere | after a seek a stale index reads new audio as though it continued | `Discontinuity` carries both epochs |
| clamps a lagging reader into the ring | the buffer decides what the caller loses | the cursor stays put; the caller chooses |
| mid/side fixed at the store | one pair of axes for every feature | planar channels; axes are the feature's choice |

`Discontinuity` is a fourth case the plan's illustrative snippet does not list. A seek is not a
gap: the samples are not old, they are from a different timeline, and interpolating across the
boundary produces features for audio nobody played.

### Verification

| Command | Result |
|---|---|
| `:engine:audio-core:test` | **14 tests, 0 failures**, `--rerun-tasks` to confirm execution |
| the same, torn-window guard removed | **FAILED** — `torn windows returned as Ok` |
| `checkAll` | BUILD SUCCESSFUL across all seven projects |
| `:app:assembleDebug` | BUILD SUCCESSFUL |

---

## V2-2-01: specify the PCM and presentation-clock ABIs

State: COMPLETE

Goal: make the tap-first invariant provable against the chain the app builds, before the slice
that moves the tap out of `:app` makes the current text proof read the wrong file.

User-visible effect: none. One seam extracted, one test added, no behaviour changed.

In scope: `AUDIO_FEATURE_ABI.md` time, epoch and channel sections plus the tap stage order;
`TapRenderersFactory.audioProcessorChain()`; `AudioChainOrderRuntimeTest`.

Out of scope: §5.4's feature table, which V2-3-03 onward writes when the features exist to
describe — a table of names with no producer is a wish list, not an ABI. Also out of scope:
implementing `RingReadResult` or the presentation clock. This slice specifies them; V2-2-02 and
V2-2-04 build them.

Files expected to change: `docs/visualizer-v2/AUDIO_FEATURE_ABI.md`,
`app/src/main/java/dev/musicviz/audio/TapRenderersFactory.kt`,
`app/src/test/java/dev/musicviz/audio/AudioChainOrderRuntimeTest.kt`.

Compatibility contract: preserved exactly. The chain construction moved into a method the
sink builder calls; the array it produces is identical, and §1.3's audio-tap semantics are
untouched.

External source/provenance entries: none.

Tests written first: not applicable in the usual sense — the invariant already held, and what
was missing was a proof of it. Fault injection replaces red-first, twice.

Benchmark or visual evidence: not applicable.

Rollback: revert the one commit. The chain returns to being constructed inline.

Risks: the seam adds a method whose only caller is the sink builder, which a reader could take
for indirection. The fifth assertion answers that directly by failing if the builder stops
using it.

Commands and results: below.

Review findings: **the test §12 warned would become vacuous already was.**
`AudioChainContractTest` guards each stage comparison with `if (at >= 0)` over seven DSP stage
names — `GainProcessor`, `EqProcessor` and five more. None of them exists in the tree. The loop
body has never executed, so the ordering half of that test has been asserting nothing since it
was written. The plan anticipated the failure as a future risk of moving the tap; it is
present tense.

`AudioChainContractTest` is nonetheless left untouched. §2.1 rule 7 forbids removing a legacy
seam in the slice that introduces its replacement, and the audit one slice ago criticised
exactly that. It retires in V2-2-03, which moves the tap and makes its text target wrong.

Commit: `test(audio): prove the tap is first against the chain, not the source text`

Next slice: **V2-2-02 — build the sample-indexed ring in `audio-core`.**

### Why a runtime assertion is different in kind

The old proof reads `TapRenderersFactory.kt` and compares string indices. It cannot see an
empty chain, cannot see a reordering inside `MvzAudioProcessorChain`, and stops describing
anything once the tap lives in another module.

The new one builds the chain and looks at it. The assertion that matters most is identity
rather than type: it pushes 64 bytes of PCM through the first processor and requires **this
factory's sink** to receive them. A second `TeeAudioProcessor` wired somewhere else passes a
type check and fails this.

Both faults were planted and both were caught — and the old test passed through the first one:

| Planted | Runtime test | Text test |
|---|---|---|
| silence skipping moved before the tap | FAILED — `the tap must be first expected:<0> but was:<1>` | **passed** |
| tap wired to a different sink | FAILED — `does not feed this factory's sink expected:<64> but was:<0>` | — |

Plant A is the exact regression the text test exists to catch: analysis would have been reading
audio after silence-skipping had already removed spans from it.

### Verification

| Command | Result |
|---|---|
| `:app:testDebugUnitTest --tests '*AudioChainOrderRuntimeTest*'` | 5 passed |
| both faults planted, then reverted | caught; `git diff` shows only the intended seam |
| `checkAll` | BUILD SUCCESSFUL, after `ktlintFormat` |
| `:app:testDebugUnitTest` | **1,257 tests, 0 failures** (1,252 before) |
| `:app:assembleDebug` | BUILD SUCCESSFUL |

---

## V2-AUDIT-01: re-audit the completed slices against the plan

State: COMPLETE

Goal: read `MASTER_PLAN.md` against what was actually built and find the places where a slice
was recorded COMPLETE while something it owed was skipped.

User-visible effect: none. One new test and one document; no production change.

In scope: a line-by-line pass over §2.1–§2.4, §3.3, §4.1, §11 and the §13 slice bullets for
every slice from V2-A-01 to V2-1-04b; `SAFETY_MODEL.md`; `SafeByDefaultTest`; this record.

Out of scope: reopening COMPLETE slices whose gaps are recorded and assigned. A finding with a
named owner is tracked, not a defect.

Files expected to change: `docs/visualizer-v2/{SAFETY_MODEL.md,STATUS.md}`,
`app/src/test/java/dev/musicviz/SafeByDefaultTest.kt`.

Compatibility contract: untouched.

External source/provenance entries: none.

Tests written first: `SafeByDefaultTest` was written against behaviour that already existed,
so it could not be red first. What replaces that discipline is its second assertion — the same
hostile input through an explicit opt-out must come back *unchanged*, which fails if the clamp
ever becomes unconditional and the first assertion starts passing for the wrong reason.

Benchmark or visual evidence: not applicable.

Rollback: revert the one commit.

Risks: an audit that finds only small things may not have looked hard enough. Two of the four
findings below are real omissions against commitments this log itself recorded, which is the
kind an audit is for; the plan's own §2.2 file list is what surfaced them.

Commands and results: below.

Review findings: the four findings are the content of this slice — see the table.

Commit: `test(safety): prove the safe default end to end, and write the model it implements`

Next slice: **V2-2-01 — specify the PCM and presentation-clock ABIs.**

### Findings

**1. `SAFETY_MODEL.md` was owed and skipped.** V2-A-01 recorded that §2.2's safety document
belonged to V2-0-02. Both halves of that slice were then completed without it. Written now,
covering the choice model, where each limit is applied, the test vectors, and — the part worth
having in one place — what is *not* covered: no frame is measured, so projectM, Shader Studio
and a scene's own internal brightness still reach the screen outside every limit.

**2. The randomizer taming was claimed but never tested.** V2-0-02's bullet says "disable or
tame Strobe and randomizer paths under safe/reduced settings". It is mechanically true —
`VisualSafety.apply` runs last, after `LfoEngine` and `AdsrEngine`, verified by reading
`VisualizerRenderer:1053-1059` — but nothing proved it, and "the clamp is in the right place"
is a claim about a call order a refactor can silently break. `SafeByDefaultTest` now drives the
worst parameters anything upstream could produce through the choice a fresh install resolves
to. Worst-case rather than a random roll, because `ParamRandomizer` is random and sampling it
proves only what it drew.

**3. Rule 7 was bent in V2-1-04a.** §2.1 rule 7: never delete a legacy seam in the slice that
first introduces its replacement. That commit added `checkEngineProvenance` *and* removed the
two assertions it supersedes from `EngineProvenanceRegistryTest`. Judged and accepted rather
than hidden: reverting that single commit restores both the assertions and removes the task,
so the coverage rule 7 protects is never lost in a rollback — which is the property it exists
for. Recorded so the precedent is visible rather than quietly set.

**4. V2-0-03 delivered its gate but not its CI half.** The bullet asks to "add CI packaging
verification and record NDK/linker provenance". The Gradle gate runs wherever
`assembleRelease` runs, including CI, so packaging *is* verified — but no workflow file was
touched and no NDK/linker provenance was recorded. Assigned to the rebuild slice that has to
run `native-libs.yml` anyway, since that is where the NDK version and linker flags are
actually determined.

### Checked and correct

| Checked | Result |
|---|---|
| `MASTER_PLAN.md` against the uploaded plan | byte-identical, sha256 `46d0f44c…` |
| §2.1 rule 3 — one semantic slice, one commit | holds for all twelve commits |
| §2.4 verification order | followed; detekt was missing until V2-1-01 caught it, already recorded |
| §4.1 module graph and forbidden edges | asserted by `EngineModuleBoundaryTest` |
| §2.2 required files | seven present; five correctly assigned to unbuilt slices; one was owed — finding 1 |
| god classes in code written here | largest is `ProvenanceRules.kt` at 133 lines; nothing above 140 |

### On the god classes that already exist

`PlayerViewModel` is 2,518 lines, `ThemePackCatalog` 2,026, `VisualizerRenderer` 1,670. They
are real, and §12 is explicit about the first: **DECOMPOSE only at proven seams — behaviour
tests; no speculative rewrite.** §16 lists "giant rewrite branch becomes unreviewable" as a
named risk.

So decomposing them is not deferred out of caution but because the plan forbids doing it
*this* way. It needs its own slice, behaviour tests written first against the seams being cut,
and ideally a device to confirm nothing moved. Doing it inside an audit, with no device, would
be the exact failure §16 names.

`VisualizerRenderer` grew by 19 lines here (V2-0-02b's flash gain). Worth noting because §12
marks it BRIDGE then DELETE: adding to it is acceptable while it remains the only renderer,
and every addition is one more thing the eventual `FrameRunner` must carry.

### Verification

| Command | Result |
|---|---|
| `:app:testDebugUnitTest --tests '*SafeByDefaultTest*'` | 5 passed |
| `checkAll` | BUILD SUCCESSFUL, after `ktlintFormat` |
| `:app:testDebugUnitTest` | **1,252 tests, 0 failures** (1,247 before) |
| `:app:assembleDebug` | BUILD SUCCESSFUL |

---

## V2-1-04b: validate the shader include manifest offline

State: COMPLETE

Goal: move the include contract's failures from the GL thread on a device to the build.

User-visible effect: none today; the tree is already clean. What changes is when a future
mistake surfaces — at `check` instead of as a blank visual on whichever device first selects
that scene.

In scope: `ShaderIncludeManifestTest` — six assertions over the 65 shaders in
`app/src/main/res/raw` and the include registry in `GlUtil.kt`.

Out of scope: compiling the shaders. There is no `glslangValidator` or `glslc` in this
container, and a real syntax check needs one — brace balance is the only structural fixture
available without it, and it is described as exactly that rather than as syntax validation.
Also out of scope: making this a cross-module Gradle task. Unlike provenance, which applies to
all source, the include registry is one loader's implementation detail and lives only in
`:app` today; generalising it now would be shape guessed against a module with no shaders in
it.

Files expected to change: `app/src/test/java/dev/musicviz/ShaderIncludeManifestTest.kt`,
`docs/visualizer-v2/LEGACY_DISPOSITION.md`.

Compatibility contract: untouched. No production file changes; every shader is byte-identical
to `HEAD` after the fault injection below.

External source/provenance entries: none.

Tests written first: this slice is only tests, so "first" is meaningless — the discipline
that replaces it is fault injection. Each assertion was proved by planting the fault it
claims to catch and watching it name the file. A green run on a clean tree is not evidence.

Benchmark or visual evidence: not applicable.

Rollback: revert the one commit.

Risks: the brace check strips comments before counting, and if GLSL ever grew string literals
that stripping would be wrong. It has none, and the assertion says so where a reader will
look.

Review findings: **I reported a bug that did not exist, and caught it before it reached this
document.** A `grep` for the substring `//#include` said `lib_particle_common.glsl` nested an
include — which, with a one-level resolver, would have been a real defect. It does not:
line 2 is prose describing how the library is used, and `GlUtil.INCLUDE_PATTERN` is anchored
to line start *and* end, so it never matched. Re-probing with the resolver's exact pattern
showed zero nested directives.

That near-miss became the design. A checker looser than the resolver invents faults; one
stricter misses real ones. The test therefore uses the identical anchored pattern, and its
first assertion pins the regex literal in `GlUtil.kt` so the two cannot drift apart silently.

One assertion was also dropped after checking it: "every registered library exists as a file"
is redundant, because the registry maps to `R.raw.lib_palette` and `R` is generated from
`res/raw` — delete the file and the map stops compiling.

Commands and results: below.

Commit: `test(shaders): check the include manifest at build time, not on the GL thread`

Next slice: **V2-2-01 — specify the PCM and presentation-clock ABIs.**

### What the six assertions cover

| Assertion | The failure it moves off the device |
|---|---|
| the pattern matches the resolver's | the whole test measuring something the loader does not do |
| every include is registered | `resolveIncludes` throwing on the GL thread the first time a scene is picked |
| no library nests an include | a leftover `//#include` reads as a GLSL comment, so the shader compiles and the function it needed is simply absent |
| every registered library is used | dead weight in the APK |
| no real `#include` | GLSL ES 3.0 has no preprocessor include; it fails at driver compile |
| braces balance | a merge resolution that truncates or doubles a block |

### Verification

Every assertion proved by injecting its fault, then restored:

| Injected | Result |
|---|---|
| `//#include lib_paletee` in `aurora_frag.glsl` | FAILED — `aurora_frag.glsl: lib_paletee` |
| `//#include lib_psrdnoise2` inside `lib_palette.glsl` | FAILED — `lib_palette.glsl: lib_psrdnoise2` |
| an unclosed `{` in `aurora_frag.glsl` | FAILED — `aurora_frag.glsl: 1` |
| all reverted | `git status` clean; BUILD SUCCESSFUL |

| Command | Result |
|---|---|
| `checkAll` | BUILD SUCCESSFUL, after `ktlintFormat` on the new file |
| `:app:testDebugUnitTest` | **1,247 tests, 0 failures** (1,241 before this slice) |
| `:app:assembleDebug` | BUILD SUCCESSFUL |

---

## V2-1-04a: make the provenance gate a build task that scans every module

State: COMPLETE

Goal: move the provenance rules out of one module's unit test and into `check`, in every
module, and add the two §3.3 rules that did not exist at all.

User-visible effect: none. Build verification only.

In scope: `ProvenanceRules`, a pure Kotlin rules engine in `build-logic`;
`readProvenanceRegistry`; the `musicviz.provenance` convention plugin registering
`checkEngineProvenance` and wiring it to `check`; thirteen fixtures; removal of the two checks
it supersedes from `EngineProvenanceRegistryTest`.

Out of scope: shader asset enumeration and include validation, and the capability report —
split off as **V2-1-04b** and **V2-1-04c**. The capability report describes what a device's GL
driver supports, and the probes that populate it are V2-4-01 — defining the type now, with no
producer and no device, would be a shape guessed against imagined needs.

Files expected to change: `build-logic/src/main/kotlin/{ProvenanceRules,ProvenanceRegistryReader}.kt`,
`build-logic/src/main/kotlin/musicviz.{provenance,kotlin-common}.gradle.kts`,
`build-logic/src/test/kotlin/ProvenanceRulesTest.kt`, `build-logic/build.gradle.kts`,
`app/src/test/java/dev/musicviz/EngineProvenanceRegistryTest.kt`.

Compatibility contract: untouched. No production source file changes; the gate only reads.

External source/provenance entries: none. This slice is the machinery, not an adoption.

Tests written first: thirteen fixtures in `build-logic`, each tripping one rule. They are the
whole evidence base — a provenance gate on a tree with no adapted code passes trivially, and
would go on passing if it checked nothing at all.

Benchmark or visual evidence: not applicable.

Rollback: revert the one commit. The two superseded assertions return with it.

Risks: the mention rule is a substring match on a repository URL, so a file legitimately
discussing a forbidden source in prose would fail. That is the intended trade — §3.3 says a
STUDY or EXCLUDE source must not appear as an origin in shipped source, and the escape hatch
is to put the discussion in `docs/`, which is not scanned.

Commands and results: below.

Review findings: two, both from re-reading rather than a failing run.

1. **A hole in the rule engine.** `checkFile` ran the mention scan only when a file had *no*
   `Origin:` marker, so one correct attribution hid every other source named in the same
   file — a properly cited SwissGL kernel would have excused a GPL repository mentioned three
   lines below it. Both now run, with marker-reached sources excluded from the mention list so
   each is reported once, as the more specific violation. Two regression fixtures added.
2. `readProvenanceRegistry` cast the parsed root unchecked, so a malformed registry threw
   `ClassCastException` out of a Gradle task rather than being reported. It now returns no
   records and leaves the diagnosis to `EngineProvenanceRegistryTest`, which exists to say
   *why* a registry is malformed. Two things failing the same way for different reasons makes
   the second report useless.

Commit: `build: check provenance markers on every module, not one module's tests`

Next slice: **V2-1-04b — shader asset enumeration and include validation.**

### What was wrong with the old gate

`EngineProvenanceRegistryTest` scanned `File(moduleRoot, "app/src/main")` — a hardcoded path,
written when `:app` was the only module. Two of its rules were therefore about to become
decorative, and the two §3.3 rules that matter most for adopted code did not exist at all:

| §3.3 requirement | Before | Now |
|---|---|---|
| scan every module | `app/src/main` only | per-module task, applied through the shared convention |
| wired to `check` | `:app:test` only | `check` in all seven projects |
| SPDX marker on an adapted file | **not checked** | `OriginWithoutSpdx` |
| cited origin exists in the registry | **not checked** | `UnknownOrigin` |
| cited commit is the pinned one | **not checked** | `OriginCommitMismatch` |
| declared licence matches the registry | **not checked** | `LicenceMismatch` |
| no STUDY/EXCLUDE source as an origin | `app/src/main` only | every module, marker or bare mention |
| adopted files carry a shipped notice | in the unit test | `MissingNotice` |

The demonstration is the point. A file citing Velo Visualiser — GPL-3.0, STUDY tier — planted
in `engine/scenes/src/main/kotlin`:

```
> provenance check failed in :engine:scenes
    …/Bad.kt: ForbiddenTier(id=velo-visualiser, tier=STUDY)
    …/Bad.kt: OriginWithoutSpdx
```

The same file, against the old test: **BUILD SUCCESSFUL**. That is the gap, measured rather
than argued.

### Verification

| Command | Result |
|---|---|
| `-p build-logic test` | **13 tests, 0 failures**, `--rerun-tasks` to confirm they executed |
| `:engine:scenes:checkEngineProvenance`, GPL citation planted | **FAILED**, two violations named |
| `:app:testDebugUnitTest --tests '*EngineProvenanceRegistryTest*'`, same file planted | BUILD SUCCESSFUL — the old blind spot |
| `checkEngineProvenance` present in | all seven projects |
| `checkAll` | BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` | **1,241 tests, 0 failures** (1,243 before; two moved to `build-logic`) |
| `:app:assembleDebug` | BUILD SUCCESSFUL |

---

## V2-1-03: establish manual composition and lifetime contracts

State: COMPLETE

Goal: write the start/reset/close rule once, with its transitions tested, instead of leaving
six lifetime owners to each invent it.

User-visible effect: none. `:engine:runtime` gains its first code; no production consumer is
switched, per §V2-1-03.

In scope: `LifetimeId` and `LifetimePhase` from §4.3; the `EngineLifetime` port;
`ManagedLifetime`, which owns the transitions so an implementor writes only what it acquires
and releases; `EngineComposition`, the §4.4 hand-written root.

Out of scope: switching any production consumer, and a DI framework. §4.4 sets the threshold
for reconsidering the latter — roughly forty independently constructed production objects, or
a third lifetime needing scoped composition — and a container today would hide the one thing
that matters here, which is who closes what and in what order.

Files expected to change: `engine/runtime/src/main/kotlin/dev/musicviz/engine/runtime/{EngineLifetime,EngineComposition}.kt`,
`engine/runtime/src/test/kotlin/dev/musicviz/engine/runtime/EngineLifetimeTest.kt`.

Compatibility contract: untouched. Nothing in `:app` references any of it yet.

External source/provenance entries: none.

Tests written first: `EngineLifetimeTest`, eleven assertions, run red as unresolved references
before the port existed. The fakes §V2-1-03 asks for are one recording implementation of
`ManagedLifetime` — the transitions are what needs proving, and a mocking framework would have
proved the mock instead.

Benchmark or visual evidence: not applicable.

Rollback: revert the one commit. Nothing depends on the module's contents.

Risks: a lifetime abstraction written before it has users can be shaped for imagined needs.
Bounded by keeping it to the three verbs §4.3 names and refusing to add a fourth until a real
owner asks for one.

Commands and results: below.

Review findings: the asymmetry between `close` and `start` is the design, so it is worth
saying why rather than leaving it to be read as inconsistency. `close` is idempotent because
teardown races are ordinary — a surface goes away while an export is finishing — and the
second close should be boring. `start` after `close` **throws**, because that is a use after
free, and returning quietly would hand the caller an object that looks alive and owns nothing.
That is the shape of the bug V2-0-01 fixed, one layer up.

Commit: `feat(runtime): give the engine lifetimes one start, reset and close`

Next slice: **V2-1-04 — add capability and provenance build gates.**

### What the contract fixes

The engine has six lifetimes and, today, no shared rule for any of them. V2-0-01 is the
concrete cost: a player released while a live consumer still pointed at it, because two owners
disagreed about who was last. That is not a playback bug, it is a missing lifetime contract,
and there are five more places to make it.

| Rule | Why it is that way |
|---|---|
| `close` twice releases once | teardown races are ordinary; the second close must be boring |
| `close` before `start` releases nothing | nothing was acquired |
| `start` after `close` throws | a use after free is a bug, not a restart |
| `reset` only while running | there is nothing to return to a known state otherwise |
| the root closes in reverse | the GL context outlives the surfaces drawn on it |
| one failing teardown does not stop the sweep | a driver throwing must not strand the encoder behind it |

### Verification

| Command | Result |
|---|---|
| `:engine:runtime:test`, before the port | unresolved references — the intended red |
| `:engine:runtime:test` | **11 tests, 0 failures**, `--rerun-tasks` to confirm they executed |
| `checkAll` | BUILD SUCCESSFUL across all seven projects |
| `:app:testDebugUnitTest` | 1,243 tests, 0 failures — unchanged |

---

## V2-1-02: create the six engine modules

State: COMPLETE

Goal: put the §4.1 dependency graph into the build, so the boundaries the V2 engine depends on
are enforced by what compiles rather than by what a session remembers.

User-visible effect: none, measured rather than assumed — the debug APK is **byte-identical**
with and without the modules.

In scope: `:engine:{audio-core,visual-core,gl,scenes,audio-android,runtime}`; the
`musicviz.jvm-library` and `musicviz.android-library` convention plugins; `:app` depending on
`:engine:runtime` and on nothing beneath it; `EngineModuleBoundaryTest`.

Out of scope: moving any production code. §V2-1-02 says "no production migration", and the
modules are empty on purpose — a boundary is worth having before there is code to put behind
it, because afterwards every move argues with the boundary instead of following it.

Files expected to change: `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`,
`gradle/libs.versions.toml`, `build-logic/src/main/kotlin/musicviz.{jvm,android}-library.gradle.kts`,
`engine/*/build.gradle.kts`, `app/src/test/java/dev/musicviz/EngineModuleBoundaryTest.kt`.

Compatibility contract: unchanged. No production source file moved, so every source-text gate
still points at the file it was written against — the failure mode `LEGACY_DISPOSITION.md`
warns about does not arise until code starts moving.

External source/provenance entries: none.

Tests written first: `EngineModuleBoundaryTest`, six assertions. The interesting half is what
it does *not* try to check: `audio-core` and `visual-core` cannot import `android.*` because a
`java-library` module has no Android on its compile classpath, so the test asserts the
*plugin* rather than the imports. Get the plugin wrong and the forbidden import quietly
becomes possible again, which an import scan would not notice.

Benchmark or visual evidence: APK size measured on a clean baseline — see below.

Rollback: revert the one commit. The modules are empty, so nothing depends on them.

Risks: six empty modules are ceremony until they carry code, which §16 lists as a named risk.
The mitigation is the plan's own ordering — the modules exist so V2-2-02 has somewhere to put
the sample-indexed ring, and nothing else is created until then.

Commands and results: below.

Review findings: two.

1. `checkAll` failed with "Task with path `:engine:check` not found". Gradle creates a
   container project for the `:engine:*` paths, and it has no build file and no tasks. The
   aggregation now filters on `buildFile.exists()`, which is also the right rule for any
   future grouping.
2. The first APK comparison showed the modules *shrinking* the APK by 1.1 MB, which is not a
   thing empty modules can do. The "before" number was a stale artifact from an earlier
   commit. Re-measured against a real baseline — `git stash`, build, restore, build — the
   delta is **0 bytes**. Worth the second measurement: reporting a 1.1 MB improvement from
   adding empty modules would have been nonsense with a number attached.

Commit: `build: create the six engine modules and their boundaries`

Next slice: **V2-1-03 — establish manual composition and lifetime contracts.**

### The graph, as built

```text
audio-core     -> (nothing)
visual-core    -> audio-core
gl             -> visual-core
scenes         -> gl, visual-core, audio-core
audio-android  -> audio-core
runtime        -> scenes, audio-android (and their transitive engine modules)
app            -> runtime
```

`audio-core` and `visual-core` are `java-library`, which is the boundary doing its own
enforcement: `ENGINE_V2_PLAN.md` §1 traces the whole module argument to two files in
`analysis/` that drifted into importing `android.*` under a package convention with no way to
stop them. Those two modules now cannot.

### Verification

| Command | Result |
|---|---|
| `projects` | seven projects: `:app` plus the six under `:engine` |
| `checkAll`, first run | **FAILED** — `:engine:check` not found; container project has no tasks |
| `checkAll`, after the fix | BUILD SUCCESSFUL across all seven |
| `:app:testDebugUnitTest` | **1,243 tests, 0 failures** (1,237 before this slice) |
| debug APK, baseline vs. with modules | 347,220,588 bytes both — **0 byte delta** |

---

## V2-1-01: add build conventions and whole-project gates

State: COMPLETE

Goal: give the module split somewhere to put shared configuration, and one command that
covers every module including the ones that do not exist yet.

User-visible effect: none. Build configuration only.

In scope: a `build-logic` included build; the `musicviz.kotlin-common` convention plugin
carrying the JDK target and ktlint; `:app` adopting it; a root `checkAll` declared over
`subprojects` so a new module is covered the day it appears.

Out of scope: extracting the rest of `app/build.gradle.kts`. Signing, packaging, Robolectric
jar resolution and the Compose setup belong to the application module and to nothing else —
there is no second consumer to share them with, and a convention plugin with one caller is
indirection rather than convention. What the engine modules need gets extracted when they
exist, in V2-1-02.

Files expected to change: `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`,
`gradle/libs.versions.toml`, `build-logic/`.

Compatibility contract: unchanged. The convention plugin sets the same JVM target and the same
ktlint configuration `:app` already resolved to, so no source file is formatted differently.

External source/provenance entries: none.

Tests written first: none, and the exception is worth stating rather than glossing. A Gradle
convention plugin has no unit-test seam here — what it does is observable only as build
behaviour, so the proof is that `checkAll` covers `:app`, that the suite and lint are
unchanged, and that removing the plugin breaks compilation. A test-fixture abstraction over
`repoFile`, which §V2-1-01 also mentions, is deliberately left to the slice that first moves
a file — writing it before then would be a helper with nothing to help.

Benchmark or visual evidence: not applicable.

Rollback: revert the one commit. `:app` returns to declaring ktlint and its JVM target inline.

Risks: an included build is a real change to how the build resolves plugins, and it runs
before everything. Mitigated by keeping the plugin to two settings and by running the full
suite, lint, ktlint and detekt afterwards.

Commands and results: below.

Review findings: **`checkAll` failed on its first run**, and not on anything this slice wrote.
`detekt` reported `FlashBudget.gainFor` with four returns against a limit of two — code from
V2-0-02b. It passed that slice's gates because §2.4's verification order lists unit tests,
ktlint, lint and assemble, and **detekt is in none of them**; `:app:check` was the only path
that ran it, and no slice had been running `check`. That is precisely the gap this slice
exists to close, found by the thing built to find it. `gainFor` is now a single `when` with
one return — the same four branches, better shape — and detekt joins the per-slice list.

Commit: `build: add convention plugins and a check that covers every module`

Next slice: **V2-1-02 — create the six engine modules.**

### Verification

| Command | Result |
|---|---|
| `checkAll`, first run | **FAILED** on `detekt`: `FlashBudget.gainFor` ReturnCount 4 > 2 |
| `checkAll`, after the fix | BUILD SUCCESSFUL, 93 tasks |
| `:app:testDebugUnitTest` | **1,237 tests, 0 failures** — unchanged by this slice |
| `:app:ktlintCheck` | BUILD SUCCESSFUL through the convention plugin |
| `:app:detekt` | BUILD SUCCESSFUL |

---

## V2-0-04: collect runtime baseline

State: LOCKED

Goal: measure what the current engine actually does on real hardware, so the V2 budgets in
§14 and the Lite/Balanced/Ultra tiers in §6.7 are set from evidence rather than from the
plan's provisional numbers.

User-visible effect: none. Measurement only.

In scope: golden frames for all 38 scene IDs and the 22 named Hyperspace/Cymatics looks;
cold and warm scene creation; steady-state allocations; CPU and GPU p50/p95; memory;
context-loss recovery; transition spikes; export and wallpaper timings; scatter/deposit,
float-target, vertex-fetch and timer-query probes on one current Mali and one current Adreno;
`PERFORMANCE_BASELINE.md` with raw captures and device metadata.

Out of scope: setting any budget. §6.7's particle counts and grid sizes stay provisional until
this slice produces the evidence — "do not lock these numbers until scatter/deposit and
overdraw tests run on a real Mali and Adreno device".

Files expected to change: `docs/visualizer-v2/PERFORMANCE_BASELINE.md`,
`docs/visualizer-v2/benchmarks/`, `docs/visualizer-v2/captures/`, and a capture harness
under `app/src/androidTest/`.

Compatibility contract: untouched; nothing here changes behaviour.

External source/provenance entries: none.

Tests written first: not started. The harness is itself the deliverable and cannot be written
blind — what it captures depends on what the timer queries turn out to report.

Benchmark or visual evidence: **this slice is the evidence.** None of it exists.

Rollback: nothing to roll back.

Risks: the risk is doing it badly rather than not doing it. A benchmark table with no device
behind it is worse than an empty one, because the next session would build budgets on numbers
nobody measured. §2.1 rule 8 lists what a benchmark must record — device, OS, GPU, thermal
state, build variant, scene, quality tier, resolution, sample count, median, p95 and raw
evidence location — and none of those can be invented.

Commands and results: none run.

Review findings: none yet.

Commit: none.

Next slice: **V2-1-01 — add build conventions and whole-project gates.**

### Why this is parked, and what lifts it

Every deliverable needs a physical device. This session runs headless: no GPU, no
`adb`-reachable hardware, no thermal envelope. The parts that look software-only are not —
golden frames need a GL context, and allocation counts need the ART heap the app actually
runs on.

It lifts when **an Android device is reachable from the session, or a CI job with one is
wired up**, specifically:

| Needed | Why that one |
|---|---|
| a current Mali device | tiler binning and fill rate are where GLES 3.0 scatter/deposit collapses first (§16) |
| a current Adreno device | the other half of the scatter/deposit matrix; different driver behaviour for float render targets |
| one lower-tier GLES 3.0 device | sets the Lite tier honestly rather than by scaling down from a flagship |

`adr/0002` records why parking it does not stop the queue: `LOCKED` means specified and not
begun, so V2-1-01 onward proceed while this stays visibly open.

---

## V2-0-03: verify and gate 16 KB native libraries

State: COMPLETE

Goal: check the binaries that actually ship, not the ones a workflow happens to build.

User-visible effect: none today. **Release builds now fail**, deliberately — see the finding
below. Debug builds, tests and lint are unaffected.

In scope: `checkNativePageAlignment`, a Gradle task that reads ELF program headers out of the
`.so` entries inside the packaged APK or AAB and is wired to `assembleRelease`/`bundleRelease`;
`NativeLibraryAlignmentTest`, which does the same over the checked-in `jniLibs` sources.

Out of scope: rebuilding the libraries. That is NDK r28 plus a full projectM CMake build —
`.github/workflows/native-libs.yml`, which budgets 90 minutes — and it would produce native
binaries no device here can load. Also out of scope: the second ABI. `abiFilters` is
`arm64-v8a` alone, so there is one to check.

Files expected to change: `app/build.gradle.kts`,
`app/src/test/java/dev/musicviz/NativeLibraryAlignmentTest.kt`.

Compatibility contract: nothing user-facing. No packaging option, ABI or dependency changes;
the gate only reads what the existing build already produces.

External source/provenance entries: none. The ELF64 layout is the published format; no code
was taken from anywhere.

Tests written first: three. The load-bearing one is the positive control — it copies a real
library, rewrites `p_align` to 16384 in every `PT_LOAD` header, and asserts the reader now
reports 16384. Without it a reader that returned 4096 for everything would pass the main
assertion for the wrong reason and keep passing after a real rebuild fixed the libraries.

Benchmark or visual evidence: not applicable.

Rollback: revert the one commit. The gate is additive.

Risks: the release gate is red until the rebuild lands, which is the intended behaviour and
still needs saying out loud — anyone cutting a release will hit it. The alternative is
shipping an app that does not start on a 16 KB-page device.

Commands and results: below.

Review findings: the ELF reader now exists twice, in the Gradle task and in the test. That is
deliberate for one slice — the task reads a zip entry and the test reads a file — and
**V2-1-01 collapses it into the build-conventions plugin**, which is the next slice and the
right home for logic two modules will want.

Commit: `feat(build): gate release artifacts on 16 KB page alignment`

Next slice: **V2-0-04 — collect runtime baseline.**

### The finding

Both shipped libraries are **4 KB aligned**, and the app targets SDK 36:

```
app-debug.apk!lib/arm64-v8a/libprojectM-4.so   aligned to 4096
app-debug.apk!lib/arm64-v8a/libprojectmjni.so  aligned to 4096
```

Android 15 ships devices with 16 KB memory pages, and a library laid out for 4 KB will not
load on them. `MASTER_PLAN.md` §1.2 listed this as unverified; it is now verified, and it
fails.

The repository was not unaware of the requirement — `native-libs.yml` is literally titled
"Rebuild native libs (16 KB aligned)" and verifies alignment on its own output. The gap was
narrower and easier to miss: **a workflow that checks what it builds says nothing about
whether that output was ever committed.** The binaries in `jniLibs` predate it.

Fixing it is one run of that workflow followed by committing the artifacts. Until then the
release path is blocked, which is the correct failure: an unloadable app is worse than an
unbuilt one.

### Verification

| Command | Result |
|---|---|
| `:app:checkNativePageAlignment` | **FAILED**, naming both libraries and their 4096 alignment — the finding above |
| `:app:testDebugUnitTest --tests '*NativeLibraryAlignmentTest*'` | 3 passed, including the 16 KB positive control |
| `:app:testDebugUnitTest` | **1,237 tests, 0 failures** (1,234 before this slice) |
| `:app:ktlintCheck` | BUILD SUCCESSFUL, after `ktlintFormat` on the buildscript |
| `:app:lintDebug` | BUILD SUCCESSFUL |
| `:app:assembleDebug` | BUILD SUCCESSFUL — the gate is on the release outputs only |

`assembleRelease` was not run: it needs signing configuration this container does not have.
The gate was exercised directly against the debug APK instead, which is the same code path
over the same kind of archive.

---

## V2-0-02b: bound how often the beat flash may fire

State: COMPLETE

Goal: close the one full-frame luminance event whose *rate* nothing downstream controls.
`VisualSafety` bounds how big a flash may be and `strobeHz` bounds the strobe's oscillator,
but the beat flash fires at the track's rate, and the only lever on that sits upstream in the
analyzer where four things can still change the answer.

User-visible effect: at high beat rates the flash is held to three per second and the excess
rolls off instead of firing. Nothing changes below that rate, or for a Custom opt-out.

In scope: `FlashBudget`; `VisualSafety.flashImpulse`; the gain applied at the two `uPostFlash`
upload sites, live and export; ADR 0001 for the deviation from §11.2.

Out of scope: measuring the frame. §11.2's limiter is defined over measured luminance and
saturated-red change, which needs a downsampled target, an async PBO readback and a device to
prove the readback does not stall — none of which exist here. That is **V2-0-02c**, and
until it lands a projectM preset, a Shader Studio shader or a scene's own internal brightness
can still flash without the budget seeing it, because none of those passes through
`uPostFlash`. The `alternating stripes` and `red transition` vectors §11.2 names are part of
that slice for the same reason: both are frame-content tests.

Files expected to change: `app/src/main/java/dev/musicviz/render/{FlashBudget,VisualSafety,VisualizerRenderer}.kt`,
`app/src/main/java/dev/musicviz/export/{FxCompositor,VideoExporter}.kt`,
`app/src/test/java/dev/musicviz/FlashBudgetTest.kt`,
`docs/visualizer-v2/{DECISIONS.md,adr/0001-flash-budget-follows-the-safety-choice.md}`.

Compatibility contract: no uniform is added or renamed, so `CompositeUniformParityTest` still
compares the same two sets. `SafetyConfig.OFF` stays an exact no-op, which is what the export
byte-parity tests rest on. No preset key, scene ID or audio semantic moves.

External source/provenance entries: none. The three-per-second figure is WCAG 2.3.1, already
cited by `VisualSafety` and already in the tree.

Tests written first: `FlashBudgetTest`, eleven assertions. Ten are behavioural vectors on the
pure limiter; the eleventh is the bypass gate, and it was proved non-vacuous by stripping the
gain from the renderer and watching it name the exact offending line.

Benchmark or visual evidence: none, and none is claimed. The limiter is arithmetic on a
16-entry ring with no allocation; what it needs is a device, and that belongs to V2-0-02c.

Rollback: revert the one commit. Both upload sites return to the raw parameter.

Risks: the estimate is `flash × beat × 0.6`, the product the shader is about to apply — a
real quantity, but a parameter estimate rather than a measurement, so `RISK_THRESHOLD` is set
below WCAG's 10% of full scale deliberately. If it turns out to suppress flashes a viewer
would not have perceived, the threshold moves by evidence and an ADR, never by editing a test
until it passes.

Commands and results: below.

Review findings: three.

1. The first draft had the budget observe `fx.flash` alone. The shader applies
   `uPostFlash × uBeat × 0.6`, so a flash of 1.0 on a frame with no beat under it changes
   nothing — and would have spent budget on a non-event. It now judges the product, and
   `flashImpulse` lives in `VisualSafety` because that is where the shader's coefficients are
   already documented.
2. A stateful per-frame call is only correct if it runs once per frame, so both call sites
   were traced rather than assumed: the renderer's upload is inline in `onDrawFrame`, and
   `FxCompositor.composite` is called once per exported frame. Had either sat inside the
   transition or layer path it would have double-counted every edge.
3. The `FxCompositor` doc first claimed live and export "arrive at the same gains". They
   arrive at the same *rule*; identical gains need the sample-locked clock §10.3 is working
   toward, because live advances on a jittering wall clock. Corrected rather than left as a
   claim the code does not support.

Commit: `feat(safety): hold the beat flash to three per second`

Next slice: **V2-0-03 — verify and gate 16 KB native libraries.**

### What the budget counts

Rising edges past a risk threshold, inside a rolling second — not frames. The distinction is
the whole design:

| Input | Treated as | Why |
|---|---|---|
| impulse rises past the threshold | one flash, budget spent | this is the event WCAG counts |
| impulse held high for 60 frames | one flash | a bright scene is not a strobe |
| impulse below the threshold | not a flash | too small to be the hazard |
| the 4th rise in one second | rolled off below the threshold | not cut to zero: a cut to black is itself a full-frame change |
| the clock stepping backwards | a new session | `uTime` wraps at `TIME_WRAP_SEC`, so this is normal, not exceptional |

### Verification

| Command | Result |
|---|---|
| `:app:testDebugUnitTest --tests '*FlashBudgetTest*'`, gain stripped from the renderer | 11 tests, 1 failed, naming the exact bypassed line |
| `:app:testDebugUnitTest --tests '*FlashBudgetTest*'` | 11 passed |
| `:app:testDebugUnitTest` | **1,234 tests, 0 failures** (1,223 before this slice) |
| `:app:ktlintCheck` | BUILD SUCCESSFUL |
| `:app:lintDebug` | BUILD SUCCESSFUL |
| `:app:assembleDebug` | BUILD SUCCESSFUL |

---

## V2-0-02a: make visual safety a versioned choice

State: COMPLETE

Goal: stop one boolean answering two different questions. "Off by default" meant both *this
person wants the strobe* and *nobody has ever been asked*, and the app could not tell them
apart. Flash safety is now a four-valued, versioned choice whose unknown state runs safe.

User-visible effect: **an install that has never chosen runs with flash limiting on.** The
9 Hz strobe, the beat flash and a randomized 30 Hz luminance LFO are all bounded until the
user says otherwise, and the settings screen says so in words rather than leaving them to
wonder why the strobe looks tame. Anyone who wants the unlimited behaviour picks Custom.

In scope: `VisualSafetyChoice` and `VisualSafety.resolve`; `GuiPrefs.safetyChoice` and the
resolution of `GuiPrefs.safety` through it; versioned persistence and the legacy migration;
the settings UI replacing the switch with the choice.

Out of scope: the temporary global flash limiter §11.2 asks for, and the audit that projectM,
user shaders and legacy bridges also traverse it — both moved to **V2-0-02b**. Also the
separate reduced-motion, brightness, transition and chromatic controls of §11.3;
`REDUCED_MOTION` covers the motion half today and the rest is a later slice.

Files expected to change: `app/src/main/java/dev/musicviz/render/VisualSafety.kt`,
`app/src/main/java/dev/musicviz/ui/{AppTheme,BehaviorSettings}.kt`,
`app/src/test/java/dev/musicviz/{VisualSafetyChoiceTest,AppSettingsTabSplitTest}.kt`.

Compatibility contract: no preset key, scene ID or audio semantic changes. `gui_safe_visuals`
is still written and still read, so a downgrade to a build without the choice finds the
prefs file it expects. Presets are untouched: safety clamps final params, it does not
rewrite stored ones.

External source/provenance entries: none.

Tests written first: `VisualSafetyChoiceTest`, twelve assertions, run red as a compile
failure (`Unresolved reference 'VisualSafetyChoice'`) before any of it existed. They pin both
directions of the migration, the version gate, the unreadable-name case, and the property the
whole slice exists for — that only CUSTOM can reach `enabled = false`.

Benchmark or visual evidence: none. The resolution is pure and the clamp it feeds already had
its own tests.

Rollback: revert the one commit. Stored `gui_safety_choice` keys become inert; the legacy
boolean the old build reads was never stopped being written.

Risks: this changes what existing users see on upgrade, which is the intended behaviour of
§11.1 and still worth stating plainly. Someone who had the strobe and never touched the
switch will find it limited until they pick Custom. The alternative — treating an untouched
default as consent to a 9 Hz full-frame strobe — is the thing the plan forbids.

Commands and results: below.

Review findings: three, all from re-reading rather than from a failing test.

1. `AppSettingsTabSplitTest` pins the Safe-visuals control to `BehaviorSettings.kt` by
   searching for the literal `"Safe visuals"`. After the rewrite that control no longer
   exists, and the gate still passed — on two passing mentions of the phrase in unrelated
   body text. Exactly the vacuous-gate failure §18.3 asks about. The gate now names the
   labels the controls actually carry.
2. The standalone "Reduced motion" switch would have become a control that does nothing
   under SAFE, since the choice resolves motion scaling itself. It moved inside CUSTOM,
   where it is a parameter rather than a contradiction.
3. `SafetyConfig`'s doc said the false default was a deliberate product position and pointed
   at an open question in `PRODUCT_REVIEW.md`. That question is now answered, so the comment
   would have argued against the code. Rewritten to say what the defaults are actually for —
   keeping `OFF` an exact no-op for export byte-parity.

Commit: `feat(safety): make flash safety a versioned choice that defaults to safe`

Next slice: **V2-0-02b — global flash limiter and the paths that bypass the clamp.**

### Why the boolean could not answer this

`saveGui` writes every key on every save. So the first time a user changes any unrelated
setting, `gui_safe_visuals=false` is written for them — an untouched switch and a deliberately
disabled one are byte-identical in the prefs file. There is no way to read consent out of it,
which is why §11.1 says not to try.

The migration therefore runs one way only:

| Stored | Resolves to | Why |
|---|---|---|
| nothing | UNKNOWN → safe | fresh install, or one that predates the choice |
| `gui_safe_visuals=false` | UNKNOWN → safe | proves nothing; written by any other settings change |
| `gui_safe_visuals=true` | SAFE | false was the default, so true was deliberate |
| a choice at the current version | that choice | the explicit answer wins over the legacy key |
| a choice at an older version | UNKNOWN → safe | consent was to behaviours that have since changed |
| an unknown name | UNKNOWN → safe | downgrade or corruption; never a guess |

Resolution happens in exactly one function, and all four outputs — live renderer, transition
picker, exporter and wallpaper — already read `GuiPrefs.safety`, so none of them can disagree
about what was chosen.

### Verification

| Command | Result |
|---|---|
| `:app:compileDebugUnitTestKotlin`, before the implementation | 12 unresolved references — the intended red |
| `:app:testDebugUnitTest --tests '*VisualSafetyChoiceTest*'` | 12 passed |
| `:app:testDebugUnitTest` | **1,223 tests, 0 failures** (1,211 before this slice) |
| `:app:ktlintCheck` | BUILD SUCCESSFUL, after `ktlintFormat` fixed import order |
| `:app:lintDebug` | BUILD SUCCESSFUL |
| `:app:assembleDebug` | BUILD SUCCESSFUL |

Not verified here, and left open: how the choice reads on a device with TalkBack.
`CrystalSegmented` carries `Role.RadioButton` on a `selectable`, which is the right
semantics for a three-way choice and better than the switch it replaces, but that is a
code-level claim rather than a tested one.

---

## V2-0-01: fix the first shared-player acquisition hold

State: COMPLETE

Goal: make the first hold of the process survive the binding that creates the player, so no
release from a second owner can free a player somebody is still using.

User-visible effect: **background playback stops dying when the screen closes.** The service
starts playback, a screen opens over it, the user leaves the screen — before this the music
stopped and every later call the service made on that ExoPlayer threw.

In scope: the two `PlaybackEngine.acquireFor*` methods and four tests.

Out of scope: everything else in `PlaybackEngine`. The reference-counting design, the
deliberate drop-without-release in `rebindTo` and the `PlaybackSession` lifetime are all
correct and untouched. No new API, no debug accessor — the tests observe the defect through
the same surface a caller uses.

Files expected to change: `app/src/main/java/dev/musicviz/playback/PlaybackEngine.kt`,
`app/src/test/java/dev/musicviz/playback/PlaybackEngineTest.kt`.

Compatibility contract: unchanged. `acquireForUi`/`acquireForService`/`releaseUi`/
`releaseService` keep their signatures and their meaning; only the order of two statements
inside the acquire methods moves.

External source/provenance entries: none.

Tests written first: three added to `PlaybackEngineTest`, run red before the fix. Two failed
(the two orderings that reach a user) and the third passed, which is what made it worth
writing — it pins the other half of the invariant, that the player is still released once
both owners let go, so the fix cannot turn a lost hold into a leaked player.

Benchmark or visual evidence: not applicable; this is a lifecycle defect, not a render path.

Rollback: revert the one commit. The two methods return to their previous bodies.

Risks: the opposite failure — a player that never goes away — is the thing a fix like this
causes, and it is covered by the third test. Not covered here: the wallpaper's relationship
to the player hold, which `MASTER_PLAN.md` §10.4 owns and V2-8-02 verifies on a device.

Commands and results: below.

Review findings: both production acquire sites were re-read — `PlayerViewModel:184` and
`PlaybackService:48` — and each takes exactly one hold that is given back in `onCleared` and
`onDestroy` respectively. No third acquirer exists. The rebind path itself is exercised only
implicitly, by Robolectric handing each test method a fresh Application; asserting it
directly would need two live Applications in one method, which Robolectric does not give,
so it stays an implicit guarantee rather than a claimed one.

Commit: `fix(playback): keep the first hold taken on the shared player`

Next slice: **V2-0-02 — make visual safety a versioned choice.**

### The defect

`acquireForUi` counted the hold and then asked for the session:

```kotlin
uiHolds++
return sessionFor(context)      // -> rebindTo(context) -> uiHolds = 0
```

`rebindTo` clears both counters whenever the Application it is bound to changes, which is
right: holds taken against a dead Application are worthless. But on the **first** acquire of
the process, `app` is null, so the change fires — and the counter the caller had just taken
went `0 → 1 → 0`. The caller walked away holding nothing.

Nothing looked wrong until a second owner appeared. The two paths that reach a user:

| Order | What used to happen |
|---|---|
| screen opens, second screen opens, second closes | first screen's hold was never counted, so one release dropped the count to zero and released the player it was still driving |
| service starts playback, screen opens, screen closes | the **service's** hold was the lost one, so closing the screen released the player the notification was playing through |

The fix is the statement order: bind first, count second.

```kotlin
fun acquireForUi(context: Context): PlaybackSession = sessionFor(context).also { uiHolds++ }
```

### Verification

| Command | Result |
|---|---|
| `:app:testDebugUnitTest --tests '*PlaybackEngineTest*'`, before the fix | 11 tests, **2 failed** — the two user-visible orderings |
| `:app:testDebugUnitTest --tests '*PlaybackEngineTest*' --tests '*PlaybackResumptionTest*' --tests '*SleepTimerDelegationTest*'` | all passed |
| `:app:testDebugUnitTest` | **1,211 tests, 0 failures** (1,208 before this slice) |
| `:app:ktlintCheck` | BUILD SUCCESSFUL |
| `:app:lintDebug` | BUILD SUCCESSFUL |
| `:app:assembleDebug` | BUILD SUCCESSFUL |

---

## V2-A-02b: enumerate every researched effect in the coverage ledger

State: COMPLETE

Goal: turn "did we already look at that?" into a question with an answer — every effect
`MASTER_PLAN.md` §8.1 names, the V2 family that owns it, and what is being done with it —
so the same source is not re-researched and the catalogue does not fill with four
near-duplicates of one idea found in four repositories.

User-visible effect: none. A data file, a generated document, and two test-source files.

In scope: `reference-coverage.json` with 162 rows across the five catalogued sources;
`REFERENCE_COVERAGE.md` regenerated from it; `ReferenceCoverage` renderer and
`ReferenceCoverageTest`; one correctness fix to `ProvenanceRegistry.NO_CODE_TIERS`.

Out of scope: characterising the opaque Fosfora codenames — that needs the upstream looks,
which this container cannot render. They are DEFER rows with the reason stated, not guesses.
Recipe IDs, tests, captures and shipped versions stay empty until the owning family's slice
fills them.

Files expected to change: `docs/visualizer-v2/{reference-coverage.json,REFERENCE_COVERAGE.md,STATUS.md}`,
`app/src/test/java/dev/musicviz/{ReferenceCoverage,ReferenceCoverageTest,ProvenanceRegistry}.kt`.

Compatibility contract: untouched. No production source file is involved.

External source/provenance entries: every row names one, and the ledger is rejected if a row
cites a source the registry does not hold or disagrees with it about the licence tier.

Tests written first: `ReferenceCoverageTest` — six assertions, the load-bearing one being
that every name §8.1 lists has a row, parsed out of the plan text itself rather than a
hand-kept copy. Run red with the ledger moved out of the tree: six tests, six failures. Two
more reds followed on the real data — a rationale too short to be reasoning on nine rows, and
the generated document out of date — and both were fixed rather than relaxed.

Benchmark or visual evidence: not applicable.

Rollback: revert the one commit.

Risks: two of the six assertions are tripwires that pass vacuously today — nothing is ported
out of a no-code source yet, and no forbidden origin is cited in the tree. They are worth
keeping because the day they stop being vacuous is exactly the day a mistake would otherwise
ship silently. The ledger's family assignments are also judgements, not facts; each row's
rationale cites the §7 recipe list or §8.2 rule it came from so a later session can disagree
with the reasoning rather than the conclusion.

Commands and results: below.

Review findings: `NO_CODE_TIERS` listed ORACLE, BENCHMARK, STUDY and EXCLUDE but not
REIMPLEMENT — so the registry would have accepted a REIMPLEMENT source declaring adopted
files, which §3 forbids ("do not copy code, shader text, constant tables, names or layout").
It is now defined as every tier except ADAPT and RETAIN, the two that may legitimately carry
upstream text. Found by asking why a coverage assertion passed vacuously.

Commit: `docs(visualizer-v2): enumerate every researched effect in the coverage ledger`

Next slice: **V2-0-01 — fix the first shared-player acquisition hold.**

### What the ledger says

| | Rows |
|---|---:|
| MERGE — folds into a family as a recipe, mode, field or post node | 123 |
| DEFER — catalogued, not this wave | 30 |
| PORT — becomes its own engine or kernel | 9 |
| **Total** | **162** |

Nine PORT rows for 162 catalogued effects is the plan's §8.2 consolidation rule turned into
numbers: four upstream projects each having a tunnel is one family with four recipes, not
four engines. Nine rows cover seven distinct kernels — Particle Life, Particle Lenia,
Physarum, Gray–Scott, Firefly Sync, the attractor field library and the strobe-safe post
node — because SwissGL and Threelab each name Physarum and reaction-diffusion, and one
kernel serves both rows.

Twenty of the thirty DEFER rows are Fosfora codenames — Protea, Cleave, Vessel and the
rest. Nothing in §8.1 or §7 says what they look like, so a family assignment would be
invention; they are recorded as open with that reason, which is the honest form of coverage.

### Verification

| Command | Result |
|---|---|
| `:app:testDebugUnitTest --tests '*ReferenceCoverageTest*'`, ledger absent | 6 tests, 6 failed — the intended red |
| `:app:testDebugUnitTest --tests '*ReferenceCoverageTest*' --tests '*EngineProvenanceRegistryTest*'` | 17 passed |
| `:app:testDebugUnitTest` | **1,208 tests, 0 failures** (1,202 before this slice) |
| `:app:ktlintCheck` | BUILD SUCCESSFUL, after one blank-line fix it caught in `ReferenceCoverage.kt` |
| `:app:lintDebug` | BUILD SUCCESSFUL |

---

## V2-A-02a: pin and hash every source in the provenance registry

State: COMPLETE

Goal: make every licence claim in the V2 corpus a piece of evidence — a hash of a file read
at a named commit — and make the registry and `MASTER_PLAN.md` §3.1 cover each other
exactly, so neither can drift alone.

User-visible effect: none. Documentation, a test-source validator and one new test class.

In scope: `provenance.json` at schemaVersion 2 — 39 repositories against §3.1's 37 ledger
rows, each with a resolved commit, the licence file's path, SHA-256, byte length and first
line; the seven-tier vocabulary from §3; the seven sources §3.1 names that the registry was
missing; `SOURCE_ARCHIVE.md` reconciled to the same vocabulary and facts; `ProvenanceRegistry`
and `EngineProvenanceRegistryTest`.

Out of scope: the reference coverage ledger (V2-A-02b, split off because it is a separate
concern and a separate commit). The `checkEngineProvenance` Gradle task itself — §3.3 puts
it at V2-1-04, when it must also scan the new modules; the rules run as a unit test until
then so they cannot rot in the gap. No production source file is touched.

Files expected to change: `docs/visualizer-v2/{provenance.json,SOURCE_ARCHIVE.md,STATUS.md}`,
`app/src/test/java/dev/musicviz/{ProvenanceRegistry,EngineProvenanceRegistryTest}.kt`.

Compatibility contract: untouched. Nothing in the registry is reachable from production code.

External source/provenance entries: this slice *is* the provenance work. Nothing was copied
from any source; only licence files were read, at the commits recorded in the registry.

Tests written first: `EngineProvenanceRegistryTest` — eleven assertions, four of which are
negative fixtures that mutate the real registry text (rename a required key, corrupt a tier,
give an EXCLUDE source an adopted file, replace a hash with prose) and assert the validator
names the problem. A fixture whose mutation failed to apply would leave the document valid
and fail its own assertion, so the fixtures cannot silently stop testing anything.

Benchmark or visual evidence: not applicable. The evidence here is the licence-hash table in
`provenance.json` itself.

Rollback: revert the one commit.

Risks: pins resolved from `HEAD` today rather than from a reviewed clone are weaker evidence
than the ones carried over from the earlier research session, and the registry says which is
which in each entry's `pin.source`. Ten sources are in that category, and none is under the
one tier where it would matter: nine sit under no-code tiers, the tenth (`acidcam-gpu`) is
REIMPLEMENT, which forbids copying anyway. All four ADAPT sources — the only tier that may
contribute upstream text — keep their reviewed-clone pins.

Commands and results: below.

Review findings: the first draft asserted that no source is `unresolved`, which would have
forced a guess about Geno-1's repository. Replaced with the weaker true claim — an
unresolved source may only sit under a no-code tier — so the gap stays visible instead of
being papered over.

Commit: `docs(visualizer-v2): pin and hash every source in the provenance registry`

Next slice: **V2-A-02b — enumerate every researched effect in the coverage ledger.**

### What changed in the registry, and why it matters

| Correction | Before | After |
|---|---|---|
| Sources vs. §3.1 | 32 entries, 7 ledger rows unrepresented | 39 entries, all 37 rows covered in both directions |
| Tier vocabulary | `ALGORITHM` / `EXCLUDED`, neither in the plan | the plan's `ADAPT`/`REIMPLEMENT`/`ORACLE`/`STUDY`/`EXCLUDE`, plus `BENCHMARK` and `RETAIN` for the two §3.1 rows that use them |
| Licence evidence | a licence *name* per source | file path, SHA-256, byte length, first line and the commit each was read at |
| Velo Visualiser | absent | present, **GPL-3.0 confirmed from the licence file** — the one source whose licence actively forbids what a careless slice would do |
| LYGIA | one word inside a Shadertoy row | its own entry: Prosperity Public License 3.0.0, noncommercial-only |
| projectM | pinned to the tag `v4.1.7` | tag resolved to `e0b0a96`; licence file is `LICENSE.txt`, not `LICENSE` |
| RDPE | licence text reported missing | re-checked at `28db17f`: still no `LICENSE`, `LICENCE`, `COPYING` or `UNLICENSE`. Stays STUDY |
| Geno-1 | absent | present and explicitly **unresolved** — §3.1 names it, §21 gives no URL, and it could not be located. No Geno-1-derived idea may cite provenance until it is |

The single most valuable line is Velo's. It is GPL-3.0, it is the richest scene checklist in
the corpus, and its 48 scene names are exactly the kind of thing that gets skimmed and then
reimplemented from memory. The registry now states the boundary where a later session will
look for it.

### Verification

| Command | Result |
|---|---|
| `:app:testDebugUnitTest --tests '*EngineProvenanceRegistryTest*'` | 11 passed |
| `:app:testDebugUnitTest` | **1,202 tests, 0 failures** (1,191 before this slice) |
| `:app:ktlintCheck` | BUILD SUCCESSFUL |
| `:app:lintDebug` | BUILD SUCCESSFUL |

Licence evidence was gathered with `git ls-remote` for the commit and
`raw.githubusercontent.com/<slug>/<sha>/<file>` for the text, hashed locally. `api.github.com`
and `github.com` HTML are blocked from this container; neither is needed, and nothing in the
registry depends on a source that could not be read.

---

## V2-A-01: install this plan as the repository's execution authority

State: COMPLETE

Goal: make `docs/visualizer-v2/` the memory the overhaul runs on — one live plan, one
slice log, and a build gate that keeps both honest — and re-audit the tree against
`MASTER_PLAN.md` §1 so later slices start from measured numbers rather than the plan's.

User-visible effect: none. Documentation and one new unit test.

In scope: `MASTER_PLAN.md` as the verbatim plan; `STATUS.md`; `DECISIONS.md` as the ADR
index; `LEGACY_DISPOSITION.md` seeded from §12; `REFERENCE_COVERAGE.md` schema; the
`adr/`, `benchmarks/` and `captures/` directories; a superseded banner on
`ENGINE_V2_PLAN.md`; the authority/link/status gate test; the §1 drift record below.

Out of scope: every production behaviour. No `:app` source file changed. Populating the
coverage ledger (V2-A-02), the legacy per-subsystem discovery columns (V2-A-02 onward)
and the ABI/baseline/safety documents §2.2 lists, each of which belongs to the slice that
first has evidence for it: `AUDIO_FEATURE_ABI.md` (V2-2-01), `GPU_RESOURCE_ABI.md`
(V2-4-01), `PERFORMANCE_BASELINE.md` (V2-0-04), `SAFETY_MODEL.md` (V2-0-02),
`PRESET_SCHEMA.md` (V2-7-03), `RELEASE_GATES.md` (Phase 11). They are deliberately absent
rather than present and empty.

Files expected to change:
`docs/visualizer-v2/{MASTER_PLAN,STATUS,DECISIONS,LEGACY_DISPOSITION,REFERENCE_COVERAGE}.md`,
`docs/visualizer-v2/ENGINE_V2_PLAN.md` (banner only),
`docs/visualizer-v2/adr/README.md`,
`app/src/test/java/dev/musicviz/EngineV2PlanAuthorityTest.kt`.

Compatibility contract: untouched. No scene ID, preset key, audio semantic or public API
is involved.

External source/provenance entries: none. No external code, shader or constant enters the
tree in this slice.

Tests written first: `EngineV2PlanAuthorityTest` — six assertions written and run red
before any document existed (missing `MASTER_PLAN.md` and the rest, no authority marker,
no `STATUS.md`). It reuses `ParamSurface.moduleRoot` rather than adding a nineteenth
private `repoFile` copy, which `BASELINE.md` §3 names as prerequisite cleanup.

Benchmark or visual evidence: not applicable — no runtime path is touched.

Rollback: revert the one commit. Nothing depends on these documents at runtime.

Risks: a docs-only gate can rot into ceremony. Mitigated by keeping the assertions about
facts a stale session would actually get wrong — which document is live, whether a link
lands, whether two slices are open — rather than about wording.

Commands and results: recorded under "Verification" below. The red proof is the one worth
naming — the six assertions were run with the five new documents moved out of the tree and
the banner stashed, and all six failed for the right reasons before being run again green.

Review findings: `REFERENCE_COVERAGE.md` and `LEGACY_DISPOSITION.md` initially read as if
they were finished. Both now carry an explicit open marker naming the slice that completes
them, per §2.1 rule 10.

Commit: `docs(visualizer-v2): install the master plan as the execution authority`

Next slice: **V2-A-02 — expand provenance and coverage registry.**

### §1 drift record, measured at this HEAD

`main` at `54630a8`, the commit `MASTER_PLAN.md` §1 audited. Worktree clean at the start
of the slice; branch `claude/visualizer-patch-plan-dg8r5u`.

| §1 claim | Measured | Verdict |
|---|---|---|
| Main Kotlin: 179 files, ~51,153 lines | 179 files, 51,153 lines | exact |
| Test Kotlin: 165 files, ~27,449 lines | 165 files, 27,449 lines (before this slice's test) | exact |
| GLSL resources: 65 | 65 | exact |
| `SceneId` values: 38 | 38 `const val` in `SceneIds.kt` | exact |
| `SceneParams` fields: 165 | 165 | exact |
| Serialized preset keys: 164 | 164 parameter keys **+ 4 envelope keys** (`name`, `sceneId`, `attack`, `decay`) = 168 distinct `put("…")` | clarified |
| Bundled presets: 19 | 19 | exact |
| Modules: 1 | `include(":app")` | exact |
| Largest coordinator: `PlayerViewModel` ~2,518 lines | 2,518 | exact |

One correction carries forward: V2-7-03 must give a disposition to **168** serialized
keys, not 164. The extra four are the preset document's own envelope and are not
`SceneParams` fields, which is why the two counts differ and why silently migrating "the
164" would drop the envelope.

### Verification

Run from `musicviz-project/musicviz/`, narrow to wide per §2.4.

| Command | Result |
|---|---|
| `:app:testDebugUnitTest --tests '*EngineV2PlanAuthorityTest*'`, documents removed | **6 tests, 6 failed** — the intended red |
| `:app:testDebugUnitTest --tests '*EngineV2PlanAuthorityTest*'`, documents in place | 6 passed, BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` | **1,191 tests, 0 failures, 0 skipped** |
| `:app:ktlintCheck` | BUILD SUCCESSFUL |
| `:app:lintDebug` | BUILD SUCCESSFUL |

The Android SDK is not present in a fresh cloud container; `tools/setup-android-sdk.sh`
installs it and writes `local.properties`, which is what these runs used. No pre-existing
failure was observed to hide behind this slice: the suite was green before it and after.
