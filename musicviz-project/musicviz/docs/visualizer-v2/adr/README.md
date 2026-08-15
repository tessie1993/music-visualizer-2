# ADRs

One file per decision, named `NNNN-kebab-title.md`, numbered in the order written and
never renumbered. [`../DECISIONS.md`](../DECISIONS.md) indexes them.

An ADR is required before: a new dependency, permission, ABI or licence obligation
(`MASTER_PLAN.md` §2.1 rule 6); a DI framework (§4.4); a native audio or FFT library
(§0 non-goals, §14); replacing `PulseTracker` (§5.3); and any change to a benchmarked
budget in §14 — those move by evidence, never by editing a test until it passes.

```markdown
# NNNN — <decision, stated as the choice made>

Status: proposed | accepted | superseded by NNNN
Slice: V2-<phase>-<number>
Date: YYYY-MM-DD

## Context
What forced a decision, and what the plan says today.

## Decision
The choice, in the imperative.

## Evidence
Measurements, fixtures, benchmark files under `../benchmarks/`, captures under
`../captures/`. A decision with no evidence is a preference; say so if it is one.

## Consequences
What this makes easy, what it makes hard, and what has to be revisited if the evidence
changes.
```
