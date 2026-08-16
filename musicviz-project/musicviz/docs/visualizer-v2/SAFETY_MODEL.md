# Safety model

What the app does about flashing and motion, why each limit exists, and the vectors that
prove it. Required by [`MASTER_PLAN.md`](MASTER_PLAN.md) §2.2; the policy is §11.

**Status:** the parameter-level model is production and tested. The measured global limiter of
§11.2 is not built — see "What this does not cover" — and until it is, three render paths are
outside every limit here.

---

## 1. What the app is protecting against

Two different harms, with different controls, deliberately not merged:

| Harm | Control | Governed by |
|---|---|---|
| Photosensitive seizure | flash rate and full-frame luminance depth | WCAG 2.3.1, three flashes per second |
| Vestibular discomfort | speed, drift, shake, rotation, endless zoom | reduced motion |

Safe mode is not "turn the music response off" (§11.3). Colour, texture, density and slow
structural movement keep reacting; what is suppressed is the impulsive full-frame event.

## 2. The user's choice

`VisualSafetyChoice` is four-valued and versioned. A boolean could not distinguish *wants the
strobe* from *was never asked*, and `ThemeStore.saveGui` writes every key on every save — so an
untouched switch and a deliberately disabled one are byte-identical in the prefs file.

| Choice | Resolves to |
|---|---|
| `UNKNOWN` | `SAFE_DEFAULTS` — never asked, or asked under an older schema |
| `SAFE` | `SAFE_DEFAULTS` |
| `REDUCED_MOTION` | `SAFE_DEFAULTS` plus motion scaling |
| `CUSTOM` | the stored sliders, verbatim — the only path to `enabled = false` |

`SAFE_DEFAULTS` is 3 Hz, 0.25 depth, no inversion. Fixed rather than read from the stored
sliders: those are `CUSTOM`'s parameters, and someone who has not chosen has not chosen them
either.

Migration runs one way. A stored `gui_safe_visuals=true` was deliberate, because false was the
default, so it becomes `SAFE`. A stored `false` proves nothing and becomes `UNKNOWN`. A choice
recorded under an older `SAFETY_CHOICE_VERSION` is asked again rather than carried forward to
behaviours the user never saw.

## 3. Where the limits are applied

One resolution point, `GuiPrefs.safety`, read by all four outputs — live renderer, transition
picker, exporter, wallpaper — so none of them can disagree about what was chosen.

`VisualSafety.apply` runs **last**, after `LfoEngine.apply` and `AdsrEngine.apply`, on the
params a scene is about to receive. Order is the whole design: a safe stored value is worth
nothing if a modulator can push it back into the hazardous range.

| Lever | What it bounds |
|---|---|
| `apply` | strobe, flash, glitch, bloom, brightness, intensity, contrast; invert and solarize off |
| `strobeHz` | the strobe's oscillator rate — dimming a 9 Hz flicker leaves a 9 Hz flicker |
| `limitLfoRate` | an LFO aimed at a luminance target; 30 Hz is the hazard, not the amplitude |
| `beatMinIntervalMs` | the analyzer's floor between beats, the only lever that reaches the beat flash's rate |
| `layerMix` | ADD and DIFFERENCE blends, which reach the screen after `apply` |
| `transitionStyle` / `transitionId` | a hard cut becomes a crossfade |
| `FlashBudget` | how *often* the beat flash may fire — three rising edges per rolling second |

With `CUSTOM` and limiting off, `SafetyConfig.OFF` is an exact no-op returning the input
instance, which is what the export byte-parity tests rest on.

## 4. Test vectors

| Vector | Where |
|---|---|
| worst-case params through a fresh install stay bounded on every path | `SafeByDefaultTest` |
| the clamp is load-bearing — the same input through an opt-out is untouched | `SafeByDefaultTest` |
| strobe rate, luminance LFO rate and beat gap capped on a fresh install | `SafeByDefaultTest` |
| `UNKNOWN` runs safe whatever the stored sliders say | `VisualSafetyChoiceTest` |
| only `CUSTOM` reaches `enabled = false` | `VisualSafetyChoiceTest` |
| legacy `true` migrates to `SAFE`; legacy `false` does not | `VisualSafetyChoiceTest` |
| a choice under an older schema version is asked again | `VisualSafetyChoiceTest` |
| 2 Hz passes, 8 Hz is held to budget, 12 Hz rolls off rather than cutting to black | `FlashBudgetTest` |
| a held bright level is one event, not one per frame | `FlashBudgetTest` |
| both `uPostFlash` upload sites traverse the budget | `FlashBudgetTest` |

## 5. What this does not cover

**No frame is measured.** Every limit above is computed from parameters — what the app is
about to ask the shader to do. §11.2's limiter is defined over *measured* temporal luminance
and saturated-red change, which needs a downsampled target and an asynchronous readback.

Three paths therefore reach the screen outside all of it, because none passes through
`uPostFlash` or `SceneParams`:

- **projectM** renders MilkDrop presets itself and can strobe internally;
- **Shader Studio** runs user GLSL;
- a scene's **own internal brightness** swings.

§11.2 requires all three to traverse the limiter. They do not yet. That is **V2-0-02c**, gated
on device access, and [`adr/0001`](adr/0001-flash-budget-follows-the-safety-choice.md) records
why the temporary budget follows the user's choice rather than running unconditionally in the
meantime.

Also absent: the saturated-red analysis, the alternating-stripe and red-transition vectors
(both need frame content), safety telemetry in debug builds, recipe risk metadata, and the
thermal half of §11.4. The independent sensory controls of §11.3 — brightness ceiling,
chromatic suppression, transitions off, stable horizon — exist only as the coarse
`REDUCED_MOTION` choice.
