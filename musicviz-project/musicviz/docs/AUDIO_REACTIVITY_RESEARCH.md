# Audio reactivity: clean-room research

Findings from reading the source of shipping audio-reactive software. Written
deliberately without reference to this repository's own code or comments, so
it can be used as an independent yardstick rather than a rationalisation of
what already exists.

Every claim below is either quoted from source (marked with a file path) or
explicitly flagged as inference. Where a constant is derived rather than
stated in the source, the derivation is given.

## Sources read

| Project | What it is | Read at |
|---|---|---|
| MilkDrop 2 | The reference music visualizer (Nullsoft, 2013 source release) | `vis_milk2/plugin.cpp`, `milkdropfs.cpp`, `fft.cpp`, `utility.cpp` |
| projectM v4 | Faithful MilkDrop port, still maintained | `src/libprojectM/Audio/{Loudness,PCM,MilkdropFFT}.cpp` |
| Butterchurn | MilkDrop port to JS/WebGL | `src/audio/audioLevels.js` |
| aubio | Reference C library for onset/tempo detection | `src/spectral/specdesc.c`, `src/onset/peakpicker.c`, `src/onset/onset.c` |
| cava | Console audio visualizer, well regarded for feel | `cavacore.c`, `CAVACORE.md` |
| glava | GLSL desktop audio visualizer | `shaders/glava/smooth_parameters.glsl` |
| Mandelbulber2 | Fractal renderer with audio-driven parameter animation | `mandelbulber2/src/audio_track.cpp` |
| BTrack | Real-time beat tracker (Adam Stark) | `src/BTrack.h` |

## The single most important finding

**None of the visualizers drive their visuals from a predicted tempo grid.**

For MilkDrop this is verifiable by exhaustion: grepping the entire source for
`bpm|tempo|beat_detect|onset` (case-insensitive) returns only the words
"temporary" and "temporal". The complete preset-facing audio API is six
floats — `bass`, `mid`, `treb` and their `_att` variants. Everything rhythmic
a MilkDrop preset does is emergent from those.

Where tempo tracking does exist (BTrack), it is a *separate module layered on
top of* an onset detector that remains independently available. `BTrack.h`
exposes `processOnsetDetectionFunctionSample()` and `beatDueInCurrentFrame()`
as distinct entry points, with the onset detection function living in its own
class. The tempo layer consumes the onset layer; it never replaces or
suppresses it.

Inference, clearly labelled as such: the reason appears to be that a tempo
grid is a *prediction*, and a prediction that disagrees with the audio must
either be corrected or must override what was actually played. For a
beat-sync tool (launching clips, driving lights on a grid) overriding is
correct. For a visualizer, the thing the eye is checking against is the sound
itself, so overriding is exactly the failure mode.

## Convergent rule 1 — instant rise, slow fall

Five independent codebases, four languages, two decades apart, all implement
the same asymmetry, and all of them make the *rise* unsmoothed or nearly so.

MilkDrop / projectM / Butterchurn (`plugin.cpp:9510`), retention form
(`rate` is how much history is kept, so a *smaller* rate is faster):

```c
if (mysound.imm[i] > mysound.avg[i]) rate = 0.2f;   // attack
else                                 rate = 0.5f;   // release
rate = AdjustRateToFPS(rate, 30.0f, GetFps());
mysound.avg[i] = mysound.avg[i]*rate + mysound.imm[i]*(1-rate);
```

Derived time constants, τ = −(1/30)/ln(rate): **attack 20.7 ms, release
48.1 ms**, a ratio of 2.3×. Note also that `bass` itself is *never* smoothed —
only `bass_att` is. Presets get both.

cava (`cavacore.c:451`) goes further and does not smooth the rise at all:

```c
if (cava_out[n] < prev_cava_out[n] && noise_reduction > 0.1) {
    cava_out[n] = cava_peak[n] * (1.0 - (cava_fall[n]*cava_fall[n]*gravity_mod));
    cava_fall[n] += 0.028;
} else {
    cava_peak[n] = cava_out[n];   // rising value used verbatim
    cava_fall[n] = 0.0;
}
```

The fall is *quadratic* — acceleration under gravity — rather than
exponential, which is why cava's bars read as physical objects.

glava (`smooth_parameters.glsl`) uses linear gravity, rate-independent:
`val -= gravitystep * (seconds per update)`, default `gravitystep 4.2`.

Mandelbulber2 (`audio_track.cpp`, `decayFilter`):

```cpp
if (animation[i] > value) value = animation[i];              // instant rise
else value = (animation[i] - value) / strength + value;      // slow fall
```

It also ships a symmetric `smoothFilter` (identical minus the max test) as a
*separate, optional* filter — the asymmetric one is the default for reactivity
and the symmetric one is offered when smoothness matters more.

**Implication:** symmetric smoothing of a drive signal is a design error for
reactivity. It costs on the attack, which is the only edge the eye locks onto.

## Convergent rule 2 — detect on raw spectra

aubio's spectral flux (`specdesc.c:235`), the reference implementation, is
half-wave-rectified difference of **raw FFT magnitudes**:

```c
for (j=0;j<fftgrain->length;j++) {
    if (fftgrain->norm[j] > o->oldmag->data[j])
        onset->data[0] += fftgrain->norm[j] - o->oldmag->data[j];
    o->oldmag->data[j] = fftgrain->norm[j];
}
```

Nothing is smoothed before the detector. Any lowpass applied upstream of an
onset detector is attenuating precisely the derivative the detector exists to
measure. Smoothing belongs on a *parallel* branch (to produce a display or
envelope signal), never in series ahead of detection — which is exactly the
topology MilkDrop uses, where `imm` stays raw and `avg` is derived beside it.

## Convergent rule 3 — normalize against recent history

Three different strategies, all solving "a quiet track must still drive the
visuals":

**MilkDrop — divide by a long running average.** The entire AGC is one
division (`plugin.cpp:9510`):

```c
long_avg[i] = long_avg[i]*rate + imm[i]*(1-rate);   // rate 0.992 @30fps
imm_rel[i]  = imm[i] / long_avg[i];                 // guard: <0.001 -> 1.0
```

τ ≈ **4.15 s** steady state; for the first 50 frames `rate` is 0.9 (τ ≈ 316 ms)
so it converges in under a second instead of taking ~12 s. Because numerator
and denominator carry the same arbitrary scale, absolute level cancels
entirely, and the published values orbit 1.0. Geiss's authoring guide states
the resulting convention directly: *"1 is normal; below ~0.7 is quiet; above
~1.3 is loud bass"*.

**cava — hunt a target with an asymmetric gain** (`cavacore.c:480`):

```c
if (overshoot)        sens *= (1 - 0.02  * framerate_mod);   // fast down
else if (!silence)    sens *= (1 + 0.001 * framerate_mod);   // slow up
```

20:1 asymmetry, and note the `!silence` guard — **the gain freezes during
silence** so it cannot ramp into the noise floor and then explode on the next
note. That guard is easy to omit and produces a very recognisable artefact.

**Mandelbulber2 — divide by the per-bin maximum over the whole track**
(`getBand`). Only available because it is an offline renderer that can see the
entire file; listed for completeness, not as a real-time option.

## Convergent rule 4 — compensate every constant for frame rate

All three real-time engines do this explicitly, and MilkDrop's helper is
called out in its own source as feel-critical:

- MilkDrop / projectM / Butterchurn: `rate^(30/fps)` (`utility.cpp:81`)
- cava: `framerate_mod = 66 / framerate` (`cavacore.c:445`)
- glava: `val -= gravitystep * (seconds per update)`

A time constant expressed in "per frame" without this term silently changes
meaning whenever the frame rate moves.

## Thresholding: what a good onset gate looks like

aubio's peak picker (`peakpicker.c:117`):

```c
thresholded = onset_proc[win_post] - median - mean * threshold;
```

Three properties worth copying:

1. **Median, not mean + k·σ.** The median is robust to the very peaks being
   detected. A mean+σ threshold is inflated by every onset it detects, so each
   beat raises the bar for the next one — the detector fights itself.
2. **A short window.** Defaults are `win_post = 5`, `win_pre = 1`, i.e. a
   **6-frame** adaptive window, not seconds. The threshold must track the
   passage, not the track.
3. **A small refractory.** `aubio_onset_set_default_parameters` sets
   `minioi` to **50 ms** (`onset.c:289`). Anything much larger starts deleting
   real musical events: at 120 BPM an eighth note is 250 ms and a sixteenth is
   125 ms.

Caveat, stated honestly: aubio's picker runs a zero-phase `filtfilt` over the
novelty curve and reads at `win_post`, which means it is **non-causal** and
buys its accuracy with lookahead latency. A real-time visual path may not want
that trade; the median/short-window/small-refractory lessons transfer
regardless.

## The best binding surface found: Mandelbulber2

Rather than publishing a fixed `bass`/`mid`/`treb` triple, Mandelbulber2 lets
every animated parameter select **its own centre frequency and bandwidth**
(`getBand(frame, midFreq, bandwidth, pitchMode)`) and then compose its own
filter chain from three primitives:

- `decayFilter(strength)` — instant rise, exponential fall
- `smoothFilter(strength)` — symmetric one-pole
- `binaryFilter(thresh, length)` — continuous value to discrete trigger, with
  a minimum hold of `length` frames

`binaryFilter` is the notable one: it is how you get discrete, beat-like
events out of a continuous band **without any tempo model at all** — threshold,
then hold. A "beat" becomes a per-binding decision rather than one global
boolean that every visual has to agree on.

## Distilled recommendations

1. Publish an instantaneous per-band value and a smoothed one *side by side*.
   Never let the smoothed one be the only thing available.
2. Make the smoothing asymmetric, with the rise unsmoothed or near-instant.
   Consider a physical (quadratic/gravity) fall rather than exponential.
3. Normalize against a running average of the same quantity, target ~1.0, and
   freeze the gain during silence.
4. Express every time constant in seconds and compensate for frame rate.
5. Run onset detection on raw spectra; keep smoothing on a parallel branch.
6. If a threshold is needed, use a median over a short window, and keep the
   refractory near 50 ms.
7. Let each visual parameter choose its own band and its own filter chain.
   Prefer that over one global beat flag.
8. Tempo estimation is legitimate as *metadata* — a readout, an LFO sync
   source. It should not sit in the path between the microphone and the pixel.

## What is not yet covered

Research was interrupted by usage limits before these were reached, and no
findings should be assumed for them:

- Fluid-simulation audio mapping (which audio quantity drives splat force,
  vorticity, dye injection)
- Fractal/raymarching parameter binding idioms, and the Shadertoy audio
  texture contract
- Elite VJ software audio surfaces (Notch, Synesthesia, Resolume, TouchDesigner)
- Keijiro Takahashi's LASP/Reaktion low-latency Unity work
- Shader-platform audio API comparison (Hydra, Veda, KodeLife, ISF)
- Real-time onset detection beyond aubio (SuperFlux, adaptive whitening)
- Winamp AVS audio model (source is cloned but unread)
