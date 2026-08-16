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
| [0001](adr/0001-flash-budget-follows-the-safety-choice.md) | The temporary flash budget follows the safety choice instead of running unconditionally | accepted | V2-0-02b |

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
