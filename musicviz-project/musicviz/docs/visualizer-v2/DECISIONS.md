# Architecture decision index

Every decision the V2 overhaul makes that a later session could plausibly reverse from
memory lives in [`adr/`](adr/README.md) as one numbered file. This page is the index.

[`MASTER_PLAN.md`](MASTER_PLAN.md) is not an ADR log — it is the standing instruction. An
ADR is what gets written when a slice needs to *depart* from it, or when the plan
deliberately left a choice open and evidence has now closed it. §2.1 rule 6 makes an ADR
mandatory before any new dependency, permission, ABI or licence obligation; §4.4 before a
DI framework; §5.3 and §14 before a benchmarked number moves.

| ADR | Title | Status | Slice |
|---|---|---|---|
| 0001 | The temporary flash budget follows the safety choice instead of running unconditionally | superseded — the clamp is now unconditional and the safety choice no longer exists; see [`SAFETY_MODEL.md`](SAFETY_MODEL.md) | V2-0-02b |
| [0002](adr/0002-a-blocked-slice-does-not-hold-the-queue.md) | A slice blocked on hardware is recorded as LOCKED and does not hold the queue | accepted | V2-0-04 |
| [0003](adr/0003-the-mono-downmix-is-the-front-pair.md) | The V2 mono downmix is the mean of the front pair, not of every decoded channel | accepted | V2-2-05b |

## Decisions the plan already made

These need no ADR. They are recorded here only so a session does not re-litigate them
after reading a contradicting older document:

- Six engine Gradle modules with hand-written composition; no Hilt or Koin until the §4.4
  threshold is measured.
- GLES 3.0 is the product baseline; 3.1 compute and SSBOs are an enhancement path.
- No Vulkan, Rust, wgpu, WebView, web runtime, network permission or second MilkDrop
  engine.
- projectM stays, dynamically linked, as the classic preset layer.
- Sample indices and presentation-clock segments are the source of time, not wall clock.
- One `FrameRunner` serves live, wallpaper, external display, preview, takes and export.
