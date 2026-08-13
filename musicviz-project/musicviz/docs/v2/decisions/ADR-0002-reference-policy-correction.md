# ADR-0002: Correct the master plan's reference policy table

> **SUPERSEDED by ADR-0003 (2026-08-13). Do not act on this document.**
>
> Its central claim — that Fosfora and Colourful Attraction do not exist — is
> **false**. Both resolve from `docs/visualizer-v2/provenance.json`:
> `kevinraymond/fosfora` (MIT or Apache-2.0) and `QC20/Colourful-Attraction`
> (MIT). The finding rested on keyword searches rather than a fetch against a
> concrete URL.
>
> What survives: adding ENTHEA (AGPL-3.0) and BoomingMusic (GPL-3.0) to the
> prohibited-from-shipping list. ADR-0003 carries that forward and adds ORPHIC.
>
> Retained unedited below as the record of a wrong call.

- Status: **Superseded**
- Date: 2026-08-13
- Deciders: Repository operator, implementing agent
- Related phase/slice: Phase 0.1 (blocks Phase 11.3)

## Context

`MASTER_PLAN.md` §11.3 carries a "Reference policy for ideas already
considered" table. It grants **Fosfora** the role "Architecture/feature/scene
study under its permissive terms; copy only with exact license ledger", and
lists **Colourful Attraction** among references that may be "study or adapt[ed]
only after exact repository/file license verification".

Both entries were inherited from earlier research rounds that named projects
without fetching them. A verification pass on 2026-08-13 (recorded in
`docs/RESEARCH_AUDIT.md`, committed as `5ceef8f`) fetched every load-bearing
reference. **Neither Fosfora nor Colourful Attraction could be found** — not by
name, and not by their claimed distinguishing features.

This matters beyond tidiness: Fosfora was also the stated model for the
normalized audio-frame contract in the earlier rounds' implementation-stack
recommendation. A licence policy that grants permissions to a project nobody can
locate is unenforceable, and an architecture that cites it has no basis.

The same pass found two entries in the table that are **correct and load-bearing**
and must not be softened: Baryon is PolyForm Strict 1.0.0 (non-commercial) plus a
separate commercial licence, and ambiguous ShaderToy / GPL / AGPL / noncommercial
material is prohibited from shipping. It additionally established that **ENTHEA
is AGPL-3.0** and **BoomingMusic is GPL-3.0** — neither was named in the table,
and both fall under its prohibition.

## Decision

1. `MASTER_PLAN.md` §11.3's table is **amended, not followed as written**, for
   the two unverifiable rows. `MASTER_PLAN.md` itself stays byte-identical to
   the delivered work order; this ADR is the amendment record, as H0.3 requires.
2. **Fosfora** and **Colourful Attraction** are struck. They may not be cited to
   justify an architecture decision, a licence grant, or a shipped algorithm. If
   either is later located under a real URL, a new ADR may reinstate it with a
   fetched licence.
3. The **audio-frame contract has no external model.** Phase 3 derives it from
   the in-tree `AudioFeatures` (which already carries chroma, pulse tracking and
   beat confidence) plus the verified references below.
4. `LICENSE_LEDGER.md` and `SOURCE_ARCHIVE.md` are seeded from the verified set
   only. Any reference reaching shipping code must carry a fetched licence, not
   a remembered one.
5. Add to the prohibited-from-shipping list, alongside Baryon: **ENTHEA**
   (AGPL-3.0) and **BoomingMusic** (GPL-3.0).

## Alternatives considered

### A. Follow the table as written

- Costs: grants study-and-copy permission for a project that cannot be located,
  and leaves an architecture contract resting on it. Phase 11.3 requires an
  exact URL, revision and licence per source — unsatisfiable for these rows.
- Rejected.

### B. Rewrite `MASTER_PLAN.md` in place

- Costs: H0.3 forbids rewriting the work order to match implementation drift;
  amendments go through a dated ADR. Editing it would also make the delivered
  document and the repository copy diverge silently.
- Rejected.

## Consequences

- **Positive:** the licensing ledger starts from fetched evidence. The
  prohibited list gains two real entries it was missing.
- **Negative:** the verified reference set for the audio frame is thinner than
  the plan assumed. Phase 3 does more original design than anticipated.
- **Migration:** none — no code depends on these rows yet, which is why this is
  cheap to fix now and expensive after Phase 3.
- **Rollback:** a later ADR reinstating a located project with a fetched licence.
- **Validated by:** Phase 11.3's requirement that every ledger row carry an
  exact URL, revision and licence.

## Verified reference set

Fetched 2026-08-13; full detail in `docs/RESEARCH_AUDIT.md`.

**Permissive — usable subject to notice obligations:** SwissGL
(`paradigms-of-intelligence/swissgl`, Apache-2.0 — *not* a Google repository);
ShaderEditor (`markusfisch/ShaderEditor`, MIT — has **no** audio input, GLES and
wallpaper-lifecycle reference only); Meyda (MIT); Clubber (`wizgrav/clubber`,
MIT); audioFlux (`libAudioFlux/audioFlux`, MIT, C, explicit Android support);
Kymatik (`xsoophx/Kymatik`, MIT, Kotlin/JVM FFT + comb-filter BPM); CAVA
(`karlstav/cava`, MIT); Audio Shader Studio (`sandner-art/Audio-Shader-Studio`,
MIT); Wavefield (`niko-dellic/wavefield`, MIT, Chladni modal patterns);
WebGL-Fluid-Simulation (MIT); Lenia (`Chakazul/Lenia`, MIT); Physarum
(`fogleman/physarum`, MIT, Go); Threelab (`jonradoff/threelab`, MIT).

**Prohibited from shipping:** Baryon (PolyForm Strict 1.0.0); ENTHEA (AGPL-3.0);
BoomingMusic (GPL-3.0); ambiguous ShaderToy material.

**Existing boundary, unchanged:** projectM, LGPL-2.1, dynamically linked.

Per the master plan, mathematical ideas may be reimplemented from papers;
**copied source** requires compatible terms and notice. Note that Physarum's
algorithm traces to Jones (2010) and Lenia to Chan (2018) — both are
reimplementable from the papers regardless of any repository's licence.
