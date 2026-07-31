# Musical Pulse: Tempo-Locked, Graded Beat Interpretation

## The problem this solves

The complaint, verbatim: *"way too many triggers go off"* and *"too many
down points or movements are made"* — the visuals twitch at every transient
instead of moving with the song.

The root cause was architectural, not a tuning issue. The old pipeline was:

```
spectral flux ──> sigma threshold + refractory ──> beat: Boolean ──> every scene
```

Three structural flaws, each visible on screen:

1. **Purely reactive.** Any flux spike past the threshold fired. The gate had
   no concept of *tempo*, so syncopated hits, fills, vocal consonants and
   whatever survived the band weighting all counted as "beats". Raising sigma
   or widening the refractory (the two user sliders) trades misses for false
   fires but can never distinguish *on-the-pulse* from *off-the-pulse* —
   that information only exists in the timing structure, which a
   per-frame threshold cannot see.
2. **All-or-nothing.** `beat` was a Boolean, so a brushed snare in a quiet
   bridge kicked the visuals exactly as hard as the first hit of the drop.
   Every trigger was a full-strength "down point".
3. **No macro dynamics.** Scenes saw instantaneous rms/bass/mid/treble but
   nothing about where the moment sits in the song's arc, so a verse and a
   chorus produced the same overall motion intensity.

## Research grounding

The fix follows the classic beat-tracking literature rather than inventing a
new heuristic:

- **Scheirer (1998), "Tempo and beat analysis of acoustic musical signals"**:
  a bank of resonators tuned to candidate tempi; beats are read off the
  resonator *phase*, not off raw onsets. Key idea borrowed: the pulse is a
  property of the onset envelope's periodicity, and individual onsets are
  only *evidence* for it.
- **Ellis (2007), "Beat tracking by dynamic programming"**: estimate a tempo
  period first (autocorrelation of the onset envelope — which this app
  already computed for its BPM readout), then choose beat times that stay
  near that period while landing on strong onsets. Key idea borrowed:
  predict where beats *should* fall and score onsets against the prediction.
- **Phase-locked loops** (the standard DJ-software approach to beat grids):
  small timing errors of accepted beats feed back into the period estimate,
  so the grid breathes with a drummer instead of drifting.
- **MilkDrop's aesthetic rule** (impulse + slow decay; already used by the
  scene envelopes here): visuals should get a *shaped* impulse per beat, and
  the impulse's size should carry meaning.

## The new pipeline

```
                       ┌────────────────────────── PulseTracker ─────────────────────────┐
spectral flux ──> sigma gate (user sliders) ──> candidates ──> tempo-phase grid ──> beat  │
                       │   autocorrelation ──> period + clarity ──┘        │              │
rms ─────────────> EnergyFollower ──> macroEnergy ─────────────────────────┴──> strength  │
                       └──────────────────────────────────────────────────────────────────┘
```

`PulseTracker` (analysis/PulseTracker.kt) sits between the existing
`BeatGate` and the scenes. Per frame it emits, through `AudioFeatures`:

| Field             | Meaning                                                             |
| ----------------- | ------------------------------------------------------------------- |
| `beat`            | Accepted beat (tempo-gated once locked)                             |
| `beatStrength`    | 0.35..1: how hard the hit was, scaled by the macro-energy envelope  |
| `beatImpulse`     | What scenes consume: 0 off-beat, `beatStrength` on it (legacy-safe) |
| `beatPhase`       | 0..1 saw over the beat interval — motion *between* beats            |
| `pulseConfidence` | 0..1 grid confidence; scenes can gate choreography on it            |
| `macroEnergy`     | 0..1 track-relative loudness arc (verse low, chorus high)           |

Behavioral rules, in order of what they fix:

- **Locked suppression** — once `pulseConfidence` passes the lock threshold,
  candidates landing outside ±20% of the period around a predicted beat are
  swallowed. This is the single rule that removes most excess triggers: hats
  and syncopation stop firing, the pulse stays.
- **Accent bypass** — an off-grid candidate ≥1.5σ *above* the beat threshold
  (a fill, a drop hit) still fires at graded strength. Big musical moments
  are never lost to the grid.
- **Coasting** — a predicted beat with no candidate advances the grid
  silently and decays confidence. Breakdowns stay calm; the old gate
  re-normalised against the quiet section and started strobing on pads.
- **Honest fallback** — confidence can only grow through *consecutively
  confirmed predictions* (3+ in a row) while the autocorrelation is actually
  periodic (clarity gate). Ambient/rubato material therefore never locks and
  gets the old reactive behavior at 0.8× strength — softer, never dead.
- **Phase anchoring on the strongest onset** — while acquiring lock, the grid
  re-seats itself on candidates clearly stronger than the current anchor, so
  the phase settles on the kick rather than on whatever transient came first.
- **Graded strength** — `(z − sigma) / 3σ` mapped to 0.35..1, then scaled by
  `0.6 + 0.4 × macroEnergy`: the same hit pulses gently in a quiet passage
  and lands full-weight in the chorus.

## Determinism and the cache contract (unchanged)

Everything above is a pure function of the flux/rms sequence and the two
user settings. The invariants the codebase is built around still hold:

- Live analysis (`FeatureExtractor.extract`) and offline replay
  (`PulseTracker.decidePulse` / `FeatureExtractor.decideBeats`) run the same
  code over the same numbers → exports match playback frame for frame
  (`PulseTrackerTest."live extractor and offline replay agree on every field"`).
- The v2 analysis cache needs **no format change**: it already stores the raw
  flux and rms curves, and `FeatureTimeline.withBeatSensitivity` re-derives
  every pulse field on load at the user's current sliders.
- The beat *decision* deliberately ignores rms (energy shapes only strength),
  so the flux-only `decideBeats` replay stays exact
  (`PulseTrackerTest."beat decision ignores the rms curve"`).
- The Settings sliders keep their exact meaning: they tune the *candidate*
  gate that feeds the tracker.

## What scenes do with it

Every consumer that used to branch `if (features.beat)` for a fixed-size kick
now scales by `features.beatImpulse` (0 off-beat, graded on it, and 1 for
legacy features that carry a flag but no strength — synthesised idle features
and pre-tracker cache entries keep their historical behavior, which is also
what keeps the existing scene tests meaningful):

- envelope snaps: `ParticleSceneBase` / `ShaderScene` / `ProjectMScene`
  `beatPulse`, `CompositeGrade.integrateBeatPulse` (renderer + export),
  fluid `beatEnv`s (`FluidEmitters`, `FluidChoreography`, `CurlFlowScene`),
  `FluidSim.audioBeat`, and the `uBeat` uniform in both composite passes;
- event magnitude: `BurstScene` firework size, `FountainScene` emission
  boost, `RippleOverlayDrops` ring amplitude.

`beatPhase`, `pulseConfidence` and `macroEnergy` are available on
`AudioFeatures` for scenes/LFOs that want anticipation motion, confidence-
gated choreography, or verse/chorus scaling; `ShaderScene`'s existing BPM
phase clock keeps working as before and simply benefits from the cleaner
beat stream it resynchronizes on.

## Tuning map

All constants live in `PulseTracker`'s companion (grid tolerance, PLL gain,
accent margin, confidence dynamics, strength floor/span, energy attack/
release/peak-decay) with doc comments explaining each choice. The user-facing
sliders remain the two that existed: beat sensitivity (sigma) and minimum gap
between beats.
