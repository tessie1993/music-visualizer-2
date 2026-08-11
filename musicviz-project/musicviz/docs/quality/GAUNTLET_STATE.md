# Gauntlet State — paused 2026-08-10

All agent clusters were stopped on request. This manifest records exactly where
each stood so any session can resume. Workflow runs are resumable: relaunch with
the same script and `resumeFromRunId` — completed agents replay from cache.
Script files + journals live under the session directory
`~/.claude/projects/-home-user-music-visualizer-2/95540a86-9842-5b70-88ec-3d0a2511587d/`
(scripts in `workflows/scripts/`, journals in `subagents/workflows/<runId>/journal.jsonl`);
research dossiers in the session scratchpad.

## Completed and merged (main)

- Round 1 (PRs #84, #85): quality bar + backlog docs, README/CHANGELOG split +
  docs repair, build config, GL dedup, AudioCapturePump merge, dead-code removal.
  Verified green: 1,076 unit tests / 0 failures, ktlint + lint clean.
- Product review: docs/quality/PRODUCT_REVIEW.md (six-reviewer fresh-eyes critique).

## Committed in this pause commit

- Instruction-surface purge (repo-level ECC trim: agents 67→16, commands 94→9,
  skills 282→12, rules→kotlin only, plugin plumbing/manifesto files deleted) plus
  a new real root CLAUDE.md. Full ECC remains installed at user level.

## Paused clusters and how to resume

| Cluster | Run ID | State when stopped | Resume note |
|---|---|---|---|
| vm-architecture-design | wf_1c9f715b-def | Research phase 3/3 done (dossiers: state-frameworks, realtime-boundary, vm-coupling — in scratchpad); 2 of 3 competing designs were in flight, judges + synthesis not started | Resume replays research from cache; designs re-run. Output goal: docs/quality/ARCHITECTURE_VM.md |
| fix-brainstorm | wf_30c4e433-4d1 | 2 of 12 fix designers started, none finished | Effectively restarts from cache-empty; output goal: docs/quality/FIX_PLAN.md |
| bug-hunt | wf_a83189fd-35c | Find phase: 2 of 6 finders returned (results cached in journal) | Resume replays the 2 finished finders; then verify → fix → green gate |
| instruction-purge | wf_da36b295-410 | Purge agent DONE (committed here); comment auditors incomplete | Only the 3 read-only comment auditors need re-running; their output feeds a later apply round |

## Standing plan when resumed

1. Finish fix-brainstorm → FIX_PLAN.md; finish architecture → ARCHITECTURE_VM.md.
2. Round 2: safety CRITICAL (safe-visuals default + first-run moment) + quick
   user-value wins (playback errors, lock-screen artwork, session restore,
   Favourites/Listening screens).
3. ViewModel migration per ARCHITECTURE_VM.md, step-by-step green.
4. Bug-hunt completion; comment-rot fixes applied after its green gate.
5. Previews / MilkDrop content / export rework; parity + l10n/a11y; detekt gate.
6. Every round: verify green → commit → push → PR; 10-lens ECC critic panel +
   skeptic after each user-facing round, until unanimously wowed.

## Key references

- Quality bar: docs/quality/QUALITY_BAR.md (+ three bar-*.md research files)
- Backlog: docs/quality/GAUNTLET_BACKLOG.md
- Product findings: docs/quality/PRODUCT_REVIEW.md
- Deep-read dossiers (scratchpad): auxio, projectm, nia, state-frameworks,
  realtime-boundary, vm-coupling
