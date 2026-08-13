# ADR-0003: Restore the reference policy — ADR-0002 was wrong

- Status: Accepted. **Supersedes ADR-0002.**
- Date: 2026-08-13
- Deciders: Repository operator, implementing agent
- Related phase/slice: Phase 0.1 (unblocks Phase 3 and Phase 11.3)

## Context

ADR-0002 struck **Fosfora** and **Colourful Attraction** from `MASTER_PLAN.md`
§11.3's reference table on the grounds that neither project could be located,
and concluded that the 2.0 audio-frame contract therefore had no external model.

That conclusion was wrong.

PR #96 (merged to `main` as `54630a8`) added
`docs/visualizer-v2/provenance.json`, a manifest carrying exact owner/repo
pairs, pinned commits and licence files. Fetching those URLs directly resolves
every project ADR-0002 struck:

| Project | Repository | Pinned | Licence | Exists |
|---|---|---|---|---|
| Fosfora | `kevinraymond/fosfora` | `09132c01` | MIT **or** Apache-2.0 | **Yes** |
| Colourful Attraction | `QC20/Colourful-Attraction` | `6e502d36` | MIT | **Yes** |
| RDPE | `sqrew/rdpe` | `28db17f8` | MIT (declared in `Cargo.toml`) | **Yes** |
| ORPHIC | `adityarajashekaran/orphic` | — | **AGPL-3.0** (dual; commercial on request) | **Yes** |

Fosfora is a Rust/wgpu real-time visualiser and VJ engine: 55 audio-reactive
effects, WGSL shaders editable at runtime, 8-layer compositing, MIDI/OSC
control. That is materially what the earlier research rounds described.

### Why the error happened, so it is not repeated

The prior audit searched by **name and description keyword**. Those searches
returned nothing, and the conclusion "not found" hardened into "fabricated"
across a research document and then an ADR. `RESEARCH_AUDIT.md` itself carried
the correct caveat — *"absence of a search result is weaker evidence than a
fetched licence file"* — and ADR-0002 leaned on the weak evidence anyway.

The operative lesson: **a failed keyword search is not evidence of
non-existence.** Only a fetch against a concrete URL settles the question, and
a negative result without a URL is "unverified", never "fabricated".

## Decision

1. **ADR-0002 is superseded.** Fosfora and Colourful Attraction are restored to
   `MASTER_PLAN.md` §11.3's reference table with their roles as written. No
   further amendment to §11.3 is needed for these rows — the plan was right.
2. **The audio-frame contract regains its external model.** Phase 3 may study
   Fosfora's lock-free audio delivery, multi-resolution analysis, audio-texture
   layout and modulation design. Fosfora is MIT/Apache-2.0, so adapting source
   is permitted **with** the notice obligations recorded in `LICENSE_LEDGER.md`.
3. **RDPE (MIT) is admitted** as a study reference for the rule system and GPU
   particle design — the role the earlier rounds claimed for it.
4. **ORPHIC is prohibited from shipping** (AGPL-3.0). It joins Baryon
   (PolyForm Strict), ENTHEA (AGPL-3.0) and BoomingMusic (GPL-3.0). ADR-0002's
   addition of ENTHEA and BoomingMusic to that list **stands** — that half was
   correct and is carried forward. PR #96 independently quarantined ORPHIC.
5. **`provenance.json` from PR #96 is adopted as the authoritative provenance
   record**, being pinned-commit evidence rather than recollection.
   `LICENSE_LEDGER.md` defers to it and must not contradict it.
6. **Verification rule, binding from here:** no reference may be recorded as
   non-existent without a fetch against a concrete URL. Absent a URL, the status
   is `UNVERIFIED`.

## Consequences

- **Positive:** Phase 3 recovers a real architecture reference; three MIT
  projects return to the usable set; provenance now rests on pinned commits.
- **Negative:** two documents asserted a fabrication that did not occur. Both
  are corrected in the same change as this ADR
  (`docs/RESEARCH_AUDIT.md`, `docs/v2/LICENSE_LEDGER.md`).
- **Still unverified, and now labelled as such rather than struck:** `Velo
  Visualiser`, `Musicya` and `Kiln`. Keyword searches did not locate them and
  no manifest supplies a URL. They may not justify a decision while
  `UNVERIFIED`, but they are **not** asserted to be fabrications.
- **Rollback:** none needed; no code depended on ADR-0002.
- **Validated by:** Phase 11.3's requirement that every ledger row carry an
  exact URL, revision and licence — which `provenance.json` already satisfies
  for the rows it covers.
