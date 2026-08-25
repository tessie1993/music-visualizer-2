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
| M5 | Render engine dual-mode + adaptive tiers + style framework (params/modulation/clamps/presets) | Tier-1 offline↔offline parity green; Tier-2 pHash structural check; tier switch no-crash | ☐ |
| M6 | Style families: shader/fluid/cymatics prettified + MilkDrop compat + ≥2 new 3D styles | .milk loads; 3D styles hit frame budget mid-tier device profile | ☐ |
| M7 | Player rebuild (Media3, library/EQ/comfort/advanced) | queue/EQ/gapless/lyrics instrumented-green on emulator | ☐ |
| M8 | Export suite (offline pipeline, essentials, 6 differentiators, formats) | Canvas loop-perfect export verified; LUFS within ±0.5 | ☐ |
| M9 | UI shell five-pillar navigation + themes system + generated packs v1 | all surfaces navigable; theme swap live | ☐ |
| M10 | Paywall port (PBL9 impl + free/premium enforcement + popup cadence) | entitlement flows pass debug-stub matrix | ☐ |
| M11 | Workflows rebuild + Play-readiness pack | zero version literals in YAML; checklist §8 complete | ☐ |
CUT-LINE (droppable if schedule breaks): extra 3D styles beyond 2, alpha lane GIF (keep WebP), smart playlists, scrobble hook, extended ratios beyond 21:9/4:5.

## §Verification commands (local gate, run in C:\Users\tessi\dev\music-visualizer-2\synesthesia once M3 lands)
`.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug ktlintCheck --no-daemon`

## §Reviewer log
| Milestone | Verdict | Notes |
|---|---|---|
| Plan v1→v4 (pre-execution) | REVISE→absorbed | sequencing, DoD, toolchain repins |
| SPEC_BLUEPRINT v1 | REVISE→absorbed | parity contract two-tier; §2.5 schema versioning; §4.4 matrix+watermark D-1; §4.5 budgets/encoders; §12 cross-cutting; popup cadence caps; GLSL count 82 |
| SPEC_BLUEPRINT v1.1 | PENDING re-review | |
| DATA-SAFETY audit #1 (spec+strategy) | REVISE→absorbed | D-SAFE-1 hard flash ceiling always-on; D-SAFE-2 timer gates CTA only, never dismissal; D-SAFE-3 RC cut from v1 (no-network law preserved); D-SAFE-4 crash-buffer backup exclusion + write-time trace sanitization; D-SAFE-5 normative disclosure copy |
| DATA-SAFETY gate | STANDING per L11 | audits every milestone; veto power |

## §Progress journal (append-only, newest top)
- 2026-08-25 DATA-SAFETY audit #1 verdict absorbed into spec v1.2 + tech strategy (RC removed). Branch synesthesia/rebuild committed @74fff9e0.
- 2026-08-25 L11 added per owner: standing data-safety expert gate. First audit dispatched on SPEC+TECH_STRATEGY.
- 2026-08-25 M1b R&D fleet returned: stack survey / render-engine strategy / billing+player mining. Consolidated into specs/TECH_STRATEGY.md.
