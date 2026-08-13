# MusicViz 2.0 License Ledger

Every external dependency or influence that reaches shipping code. A row may be
added only with a **fetched** licence — never a remembered one.

Use categories: `dependency` (linked/bundled), `copied` (source in tree),
`derived` (adapted implementation), `study` (read only, nothing copied),
`math` (reimplemented from a paper or public formula).

## Shipping today

| Project | URL / revision | Licence | Use | Files | Notice action |
|---|---|---|---|---|---|
| libprojectM 4.1.7 + FBO backport | github.com/projectM-visualizer/projectm | LGPL-2.1 | `dependency`, dynamically linked | `app/src/main/jniLibs/arm64-v8a/libprojectM-4.so`, patched by `tools/projectm-v417-render-fbo-backport.patch` | In `THIRD_PARTY_NOTICES` + mirrored to `app/src/main/assets/third_party_notices.txt`; `checkThirdPartyNotices` fails the build on drift. **Replacement mechanism and dynamic boundary are non-negotiable.** |
| gl-transitions | vendored via `tools/vendor_gl_transitions.py` → `assets/gl_transitions.json` | To re-verify in Phase 11.3 | `copied` (shader source) | `assets/gl_transitions.json` | **`UNKNOWN` until re-verified.** Individual transitions carry individual authors/licences; a permissive collection licence does not cover every entry. |

`THIRD_PARTY_NOTICES` at the repo root is the authoritative list for everything
already linked (Media3, Compose, JTransforms, etc.); this table tracks what the
2.0 work adds or must re-verify.

## Approved for reference — nothing copied yet

Verified 2026-08-13 by fetching each repository. Detail in
`docs/RESEARCH_AUDIT.md`; policy in `decisions/ADR-0002-reference-policy-correction.md`.

| Project | Repo | Licence | Intended use |
|---|---|---|---|
| SwissGL | `paradigms-of-intelligence/swissgl` | Apache-2.0 | `study` → ping-pong simulation kernels under GLES 3.0. Apache-2.0 notice obligations apply **if** source is adapted. |
| ShaderEditor | `markusfisch/ShaderEditor` | MIT | `study` → Android GLES + wallpaper lifecycle. No audio input. |
| audioFlux | `libAudioFlux/audioFlux` | MIT | `study`; a native dependency needs its own ADR per plan §1 non-goals |
| Kymatik | `xsoophx/Kymatik` | MIT | `study` → Kotlin FFT / comb-filter BPM |
| Meyda | `meyda/meyda` | MIT | `study` → feature definitions |
| Clubber | `wizgrav/clubber` | MIT | `study` → band→uniform modulator mapping |
| CAVA | `karlstav/cava` | MIT | `study` → band mapping and smoothing behaviour |
| Audio Shader Studio | `sandner-art/Audio-Shader-Studio` | MIT | `study` → audio-feature→uniform wiring |
| Wavefield | `niko-dellic/wavefield` | MIT | `study` → Chladni modal patterns |
| WebGL-Fluid-Simulation | `PavelDoGreat/WebGL-Fluid-Simulation` | MIT | `study` → already mirrored conceptually by in-tree fluid |
| Lenia | `Chakazul/Lenia` | MIT | `math` — reimplement from Chan (2018) |
| Physarum | `fogleman/physarum` | MIT | `math` — reimplement from Jones (2010) |
| Threelab | `jonradoff/threelab` | MIT | `study` → parameter-schema architecture |

Test-only oracles (never shipped): librosa, libebur128. Shipping either needs a
separate ADR.

## Prohibited from shipping

| Project | Licence | Note |
|---|---|---|
| Baryon | PolyForm Strict 1.0.0 + separate commercial licence | Non-commercial. Study only; no copied source. |
| ENTHEA | AGPL-3.0 | Copyleft; incompatible with shipping this app closed. |
| BoomingMusic | GPL-3.0 | Feature reference only; no copied code. |
| Ambiguous ShaderToy material | unclear | Default-deny without an explicit compatible licence. |

## Struck — could not be located

`Fosfora` and `Colourful Attraction` appeared in `MASTER_PLAN.md` §11.3 but
could not be found. See ADR-0002. They may not justify any decision.

## Rules

1. Mathematical ideas may be reimplemented from papers. **Copied source**
   requires compatible terms and a notice.
2. A permissive engine licence does not license bundled community assets —
   presets, shaders and textures are audited individually (plan §11.3).
3. Root and in-app notices are updated together, with a test pinning them in
   sync.
