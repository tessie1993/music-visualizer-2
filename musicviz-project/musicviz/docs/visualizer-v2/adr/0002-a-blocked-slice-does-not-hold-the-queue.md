# 0002 — A slice blocked on hardware is recorded as LOCKED and does not hold the queue

Status: accepted
Slice: V2-0-04
Date: 2026-08-16

## Context

`MASTER_PLAN.md` §2.1 rule 2: "Never begin a second slice while the current slice is before
`COMPLETE`." The rule exists to stop two slices being worked at once, which is how diffs stop
being reviewable.

V2-0-04 asks for golden frames on real hardware, GPU timer-query and scatter/deposit probes on
a current Mali and a current Adreno, thermal behaviour under sustained load, and context-loss
recovery. None of it can be produced in a headless container, and none of it can be
substituted for — a benchmark table with no device behind it is worse than an empty one,
because the next session would build budgets on it.

Read literally, the rule then stops the entire programme: V2-0-04 can never reach `COMPLETE`
here, so V2-1-01 and everything after it can never begin.

## Decision

`LOCKED` — the state machine's own first state, meaning specified but not begun — is exempt
from the one-slice rule. Any number of slices may sit at `LOCKED`. At most one may be in an
*active* state (`DISCOVERY` through `READY_TO_COMMIT`).

A slice may only be parked at `LOCKED` with its blocking condition named concretely enough to
recognise when it lifts: which device, which measurement, which artifact.

## Evidence

`EngineV2PlanAuthorityTest` enforces the narrower rule directly — it counts states that are
neither `COMPLETE` nor `LOCKED` and fails above one. The protection the original rule provides
is unchanged: two slices cannot be in progress at once.

## Consequences

Makes it possible to keep moving through work that does not depend on a device while the
device-dependent slices stay visibly open, which §2.1 rule 10 asks for anyway.

The risk is that `LOCKED` becomes a place work goes to be forgotten. Two things bound it: a
`LOCKED` entry carries the same full §2.3 specification as any other, so parking a slice costs
the same thinking as starting one; and its blocking condition has to be specific, so "blocked"
cannot mean "not looked at".

Revisit if the count of `LOCKED` slices ever exceeds the count of `COMPLETE` ones. At that
point the programme is not queued, it is stalled, and that is a different problem.
