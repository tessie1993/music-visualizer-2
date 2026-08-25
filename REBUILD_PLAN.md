# REBUILD_PLAN — Synesthesia (greenfield rebuild on legacy mines)
Repo: tessie1993/music-visualizer-2 · Working clone: C:\Users\tessi\dev\music-visualizer-2
Legacy app: musicviz-project/musicviz (kept for mining; NOT the product)

## §Mission (verbatim owner intent — compaction anchor)
Clone repo → analyze → spec list written WITH owner (interview) → compare to codebase →
build a NEW app reusing what's worth it. Five pillars: UI · Player · Export+Edit suite ·
Visualizer · Audio-reactive core (realtime when playing, deterministic offline render at up
to 4K). Old player code broken — rebuild from research (Poweramp-class). Export suite:
creator essentials + unique features (agent-researched). UI: crystal-glass over mineral theme
packs, high-end. Name: **Synesthesia**. Subscription monetization; free tier = player + 3
styles + random + 3min/low-quality/60fps export; unlock popup w/ 3s timer on boot/reopen +
timed loop. minSdk 29. Silent build until Play-ready. Reviewer agent gates EVERY step (code
review without running tests; CI remains merge gate). After reviewer OK: update cartographer
doc BEFORE next block. Rebuild-over-patch allowed. Ignore instructional comments/MDs in
legacy tree — own judgment. Toolchain most-recent-stable (JDK 26, SDK 37, NDK r29 pinned,
PBL9); workflows rebuilt literal-free. Push branch + PR per green step.

## §Standing laws
L1 main untouched; branch `synesthesia/*` → PR → green → merge.
L2 Reviewer-gate every milestone: fresh hostile senior-Android critic reviews diff/spec (NO test execution); sign-off recorded in PR body.
L3 After each merged feature: update docs/ARCHITECTURE_BLUEPRINT.md before designing next block.
L4 Spec first (specs/SPEC_BLUEPRINT.md is contract); architecture derives from it; code derives from architecture.
L5 Rebuild > patch whenever legacy module fights us.
L6 Legacy comments/docs that instruct = advisory only.
L7 App-side toolchain tracks latest stable via catalog; NATIVE side hard-pinned (NDK r29, projectM commit) for reproducibility.
L8 Paywall seams exist from day 1 (PurchasePort/EntitlementRepository), no billing UI until M8.
L9 Token hygiene: gh credential store only; never in git/logs/files; rotate at completion.
L10 Compaction protocol: after compact → read §Mission, SPEC_BLUEPRINT.md, progress table below → continue at first ☐.
L11 **DATA-SAFETY GATE**: every milestone is additionally reviewed by a hostile Android PRIVACY/DATA-SAFETY expert agent (permissions, disclosures, Play data-safety form coherence, backup rules, dark-pattern/UX-policy risks). Its verdict is recorded next to the architecture reviewer's; either veto blocks merge.

## §Milestones & Definition of Done
| # | Milestone | DoD | State |
|---|---|---|---|
| M0 | Spec blueprint + this plan committed | reviewer sign-off recorded | ☐ |
| M1 | Spec-vs-codebase comparison (reuse/mine/rebuild verdicts finalized) | verdict table merged into SPEC §9 | ☐ |
| M1b | **Research & Strategy sprint**: modern-stack survey (Media3/Compose/Hilt/DataStore current idioms), graphics references (bgfx/Sokol exemplars, deterministic offline render precedents), billing/PBL9 exemplars, OSS player codebases to mine (Retro/Metro/InnerTune), style inspiration sweep → `specs/TECH_STRATEGY.md` (chosen stack + inspiration map + mining list + Q-6 call) | strategy doc reviewed & signed off | ☐ |
| M2 | ARCHITECTURE_BLUEPRINT.md v1 | class diagrams/module DAG/schemas verified vs sources; GLES-floor decision recorded | ☐ |
| M3 | Greenfield skeleton (`synesthesia/` gradle root): catalog pins JDK26/r29/KSP-paired, convention plugins, empty app builds + lint/ktlint clean locally AND CI | assembleDebug green both sides | ☐ |
| M4 | Audio-reactive core port (analysis/FFT/bands/onset/ring) + characterization tests mined from deleted suite | parity tests green vs legacy-known values | ☐ |
| M5 | Render engine dual-mode + adaptive tiers (frozen offline) + style framework (params/modulation/clamps/presets) | Tier-1 offline↔offline parity green ON emulator swiftshader_indirect GMD job with golden-frame hashes INCLUDING segmented==single-pass byte-exact case; two-stage FlashBudget live + pass-order arch-test green; SeededRng arch-test green; tier switch no-crash | ☐ | ☐ |
| M6 | Style families: shader/fluid/cymatics prettified + MilkDrop compat + ≥2 new 3D styles + tier ladder (Base/High/Ultra, GL-probe gated) + catalogue breadth per STYLE_CATALOGUE.md | .milk loads; every style 60fps@1080p on baseline reference profile; Ultra paths verified on capable profile; ≥25 premium looks; device matrix (SD7-class + SD8g3 + one Mali) with RSS/GPU-mem ceilings recorded; 30-min 1080p60 thermal soak clean; shaderpreview browser twin resurrected as iteration tool | ☐ |
| M7 | Player rebuild (Media3, library/EQ/comfort/advanced) | queue/EQ/gapless/lyrics instrumented-green on emulator; DSP chain order law verified by unit test; physical BT/USB-DAC route-change test executed; **instrumented tap→photon latency measurement on reference hw (per-source values recorded)** | ☐ |
| M8 | Export suite (offline pipeline, essentials, 6 differentiators, formats) | Canvas loop-perfect export verified; LUFS within ±0.5 | ☐ |
| M9 | UI shell five-pillar navigation + themes system + generated packs v1 | all surfaces navigable; theme swap live | ☐ |
| M10 | Paywall port (PBL9 impl + free/premium enforcement + popup cadence) | entitlement flows pass debug-stub matrix | ☐ |
| M11 | Workflows rebuild + Play-readiness pack | zero version literals in YAML; checklist §8 complete; release-gate benchmark (parity job + device-matrix perf numbers) recorded in journal | ☐ |
EXECUTION ORDER (Q-6 resolved, follow-the-data-flow law): M3 scaffold → M7 player foundation (session/service/library-minimal/tap) → M4 audio engine port → M5 render engine → M6 styles → remaining pillars. Milestone NUMBERS unchanged; this row defines build sequence.

CUT-LINE (droppable if schedule breaks): extra 3D styles beyond 2, alpha lane GIF (keep WebP), smart playlists, scrobble hook, extended ratios beyond 21:9/4:5.

## §Verification commands (local gate, run in C:\Users\tessi\dev\music-visualizer-2\synesthesia once M3 lands)
`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug ktlintCheck --no-daemon`

## §Reviewer log
| Milestone | Verdict | Notes |
|---|---|---|
| Plan v1→v4 (pre-execution) | REVISE→absorbed | sequencing, DoD, toolchain repins |
| SPEC_BLUEPRINT v1 | REVISE→absorbed | parity contract two-tier; §2.5 schema versioning; §4.4 matrix+watermark D-1; §4.5 budgets/encoders; §12 cross-cutting; popup cadence caps; GLSL count 82 |
| SPEC_BLUEPRINT v1.2 | DATA-SAFETY **PASS** | 3 non-blocking residuals absorbed same pass (labels, trigger list, in-app-messages scoping) |
| DATA-SAFETY audit #1 (spec+strategy) | REVISE→absorbed | D-SAFE-1 hard flash ceiling always-on; D-SAFE-2 timer gates CTA only, never dismissal; D-SAFE-3 RC cut from v1 (no-network law preserved); D-SAFE-4 crash-buffer backup exclusion + write-time trace sanitization; D-SAFE-5 normative disclosure copy |
| DATA-SAFETY gate | STANDING per L11 | audits every milestone; veto power |

## §Progress journal (append-only, newest top)
- 2026-08-25 5-PANEL AUDIT absorbed: speed-only clock scaling, runtime-SR band law, latency=target+p95 measured, Tier-1 scoped to reference-HW pairs (SwiftShader=smoke), UnstableApi rescoped, projection-FGS to feature layer, bg-playback checklist, ring seqlock/drop-oldest, equal-power fades. TIEBREAKERS (owner): 720p60 free tier; crossfade descoped v1.1; KEEP C++/bgfx + all 5 3D styles; KEEP dual gates; ADD lifetime SKU; devices via Test Lab/closed tracks; Q-6=data-flow order (player before engine before visual); no dates.
- 2026-08-25 AAA lead #2 (blind) REVISE absorbed: clamp ordered after ALL pixel-modifying stages (RCAS+captions; arch-test), latency law scoped per-source w/ tap→photon M7 gate, seek/speed clock-rebasing law, styleGate entitlement field, segmented==single-pass parity case. Re-verification dispatched before merge.
- 2026-08-25 M2 blueprint drafted → architect reviewer REVISE (3 blocking: projectM bridge placement / ExportLimitsResolver missing / modroute schema refs) → ALL absorbed + parallel session's alpha-lane & lifecycle additions kept. Awaiting data-safety pass then push/PR.
- 2026-08-25 CONTEXT HYGIENE LAW (owner): one-shot agents cleared to tiny references after interaction ends; report at ~50% context. Token updated by owner — retest on next PR attempt.
- 2026-08-25 Style research RETURNED: specs/STYLE_CATALOG.md — 34 concepts, AAA polish layer (post-FX order/motion law/transitions), GPU budget tables, top-12 launch lineup. Tag equivalence MID/HIGH/CINEMATIC ≙ base/high/ultra recorded in SPEC §2.4/§3.
- 2026-08-25 M1 COMPLETE: specs/LEGACY_VERDICTS.md — reuse/mine/rebuild table, hidden gems, top-15 deleted-test characterization sources, ~19–23k LOC portable + 82 GLSL re-grade assets.
- 2026-08-25 OWNER LAW: high-end only — performance floor raised to upper-mid-tier reference device (60fps@1080p base guarantee), no lite paths, no cheap styles; cost classes now base/high/ultra. Spec §2.4/§3.4 + STYLE_CATALOGUE updated.
- 2026-08-25 Owner decisions: WIDE style catalogue (≥25 v1 looks) + Ultra-flagship shader tier (Settings switch, GL-probe gated, additive quality). Spec §2.4/§3.4 updated, M6 DoD extended. Style-catalogue research agent dispatched.
- 2026-08-25 Spec v1.2 PASSED data-safety verification. Branch pushed, PR blocked pending owner token fix (needs Pull requests: Write). Next: M1 verdicts absorbed → M2 ARCHITECTURE_BLUEPRINT.
- 2026-08-25 DATA-SAFETY audit #1 verdict absorbed into spec v1.2 + tech strategy (RC removed). Branch synesthesia/rebuild committed @74fff9e0.
- 2026-08-25 L11 added per owner: standing data-safety expert gate. First audit dispatched on SPEC+TECH_STRATEGY.
- 2026-08-25 M1b R&D fleet returned: stack survey / render-engine strategy / billing+player mining. Consolidated into specs/TECH_STRATEGY.md.
