# 0001 — The temporary flash budget follows the safety choice instead of running unconditionally

Status: accepted
Slice: V2-0-02b
Date: 2026-08-16

## Context

`MASTER_PLAN.md` §11.2 asks for a global limiter that **every** output passes, enforcing no
more than three high-risk flashes per second "unless measured area/luminance are demonstrably
below the documented threshold".

Two facts make the unconditional version unbuildable in this slice.

The measurement does not exist. §11.2's limiter is defined over *measured* frame luminance
and saturated-red change, which on GLES 3.0 means a downsampled render target plus an
asynchronous PBO readback, and a device to prove the readback does not stall the pipeline.
Neither the readback nor a device is available here. What this slice can measure is the
parameter product the shader is about to apply — `flash × beat × 0.6` — which is a genuine
estimate of one full-frame event, not of the frame.

And an unconditional limiter would override an explicit opt-out. V2-0-02a made
`VisualSafetyChoice.CUSTOM` the only way to switch limiting off, and made that a decision
someone takes deliberately. Applying a rate limit on top of it would mean the app quietly
overruling the one answer the user did give. It would also end `SafetyConfig.OFF` as an exact
no-op, which the export byte-parity tests rest on.

## Decision

The rolling flash budget applies whenever `SafetyConfig.enabled` — which after V2-0-02a is
every install that has not explicitly opted out, including every new one. Under a Custom
opt-out it is an exact no-op.

The budget is still *evaluated* on every frame, so its one-second history stays coherent when
the choice changes mid-session; only its result is gated.

## Evidence

- `FlashBudgetTest` — ten deterministic vectors: 2 Hz passes untouched, 8 Hz is held to the
  budget, 12 Hz rolls off rather than cutting to black, a sub-threshold impulse never spends
  budget, a held plateau is one event rather than one per frame, the window recovers, a
  backwards clock restarts it, and two instances fed one input sequence agree exactly.
- Both upload sites — `VisualizerRenderer.onDrawFrame` and `FxCompositor.composite` — call it
  once per frame, verified by reading the call graph rather than assumed.

## Consequences

What this makes easy: the rate rule is enforced at the last point before the frame, on both
the live and the export path, with no GPU work and no device needed to prove it.

What it does not do, and what a later slice must: it does not measure the frame. A projectM
preset, a user shader in Shader Studio, or a scene's own internal brightness can still flash
without the budget seeing it, because none of those swings passes through `uPostFlash`. §11.2
is satisfied for parameter-driven events only.

Revisit when the measured limiter lands (**V2-0-02c**, gated on device access). At that point
the unconditional question reopens on better terms: with a real measurement, "demonstrably
below the documented threshold" becomes a thing the app can actually determine, and the
Custom opt-out can be honoured for everything except content that measures over the line.
