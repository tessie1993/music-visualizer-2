# Reference coverage ledger

**Open — populated by V2-A-02.** This file currently defines the schema and nothing else.
No row exists yet, and no source may be adapted until its row does
([`MASTER_PLAN.md`](MASTER_PLAN.md) §2.1 rule 9).

## What this is for

The research corpus is large enough that "we looked at that already" stops being true
after one session. Without a ledger the same source gets re-researched, a shader gets
borrowed with no traceable origin, and the catalogue fills with near-duplicates of one
idea found in four repositories. So every effect or concept found in the research gets a
row here — not only the ones selected for the first release (§3.2).

A row is **complete** only when its V2 implementation, rejection or merge is evidenced. A
feature is never "incorporated" because this plan names it.

## Row schema

| Column | Meaning |
|---|---|
| `source` | the upstream project, as named in [`SOURCE_ARCHIVE.md`](SOURCE_ARCHIVE.md) |
| `upstream name` | what the effect is called upstream |
| `upstream commit` | the reviewed pin, full hash |
| `license tier` | ADAPT / REIMPLEMENT / ORACLE / STUDY / EXCLUDE (§3) |
| `mathematical family` | the maths, independent of any implementation |
| `V2 family` | which of the twelve families in §7 owns it |
| `recipe or engine ID` | the stable V2 identifier, once one exists |
| `disposition` | PORT / MERGE / DEFER / EXCLUDE |
| `rationale` | why that disposition, in one sentence |
| `provenance entry` | the key in [`provenance.json`](provenance.json) |
| `tests` | the test that proves it |
| `screenshots` | capture under `captures/` |
| `shipped version` | app version it first shipped in, or blank |

## Dispositions

| Disposition | Meaning |
|---|---|
| **PORT** | becomes its own V2 recipe or engine |
| **MERGE** | folded into an existing family as a mode, field, boundary or post node (§8.2) |
| **DEFER** | in the catalogue, not in this wave; the row says what would unblock it |
| **EXCLUDE** | licence, provenance, duplication or product fit blocks it |

`MERGE` is the expected outcome for most rows. Four upstream projects each having a
"tunnel" is one family with four recipes, not four engines — consolidation is the point.

## Coverage

| source | upstream name | upstream commit | license tier | mathematical family | V2 family | recipe or engine ID | disposition | rationale | provenance entry | tests | screenshots | shipped version |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| _none yet_ | | | | | | | | | | | | |
