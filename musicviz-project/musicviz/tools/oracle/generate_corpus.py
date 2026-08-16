#!/usr/bin/env python3
"""Generates the audio fixture corpus and its expected values.

MASTER_PLAN.md V2-3-01. Runs OUTSIDE the app: librosa is ORACLE tier
(provenance.json), which means it produces expected values here and never
enters the runtime. Nothing this script imports may appear in the APK.

Usage:
    python3 -m venv .venv && .venv/bin/pip install librosa soundfile
    .venv/bin/python tools/oracle/generate_corpus.py

Output (under app/src/test/resources/corpus/):
    <name>.pcm        raw interleaved little-endian int16, no header
    manifest.json     rates, channels, checksums, expected values, tolerances

Raw int16 rather than WAV because the Kotlin side then needs no decoder and no
header parser - the manifest is the only description of the bytes, so there is
exactly one place for the two sides to disagree, and a checksum over it. int16
rather than float32 because it is what the app's tap actually delivers, and it
halves a corpus that has to live in the repository. Both sides dequantise the
same way, x / 32768, so both analyse identical numbers.

Determinism is a requirement, not a nicety: the expected values describe these
exact bytes. Every random source is seeded, and the checksums in the manifest
fail the build if a regenerated corpus differs.
"""

from __future__ import annotations

import hashlib
import json
import pathlib
import sys

import librosa
import numpy as np
import scipy
import soundfile

# Bumped by hand whenever a fixture's definition changes, so a stale corpus is
# a visible mismatch rather than a silent one.
GENERATOR_VERSION = 1

SR = 22_050
OUT = pathlib.Path(__file__).resolve().parents[2] / "app/src/test/resources/corpus"

# Per-feature agreement required between this oracle and the Kotlin engine.
# Each is a decision, not a default:
TOLERANCES = {
    # Correlation is a normalised sum over the whole window; the only
    # divergence is float summation order.
    "stereoCorrelation": 1e-4,
    # Width is a ratio of RMS magnitudes, one sqrt more than correlation.
    "stereoWidth": 1e-4,
    # Plain sqrt(mean(x^2)) over the fixture.
    "rms": 1e-6,
    # The band a tone lands in is an integer; a tone must not straddle.
    "peakBandExact": 0.0,
}


def _tone(freq: float, seconds: float, amp: float = 0.5) -> np.ndarray:
    t = np.arange(int(SR * seconds), dtype=np.float64) / SR
    return (amp * np.sin(2 * np.pi * freq * t)).astype(np.float32)


def _fixtures() -> dict[str, np.ndarray]:
    """name -> (frames, channels) float32, mono kept as (frames, 1)."""
    rng = np.random.default_rng(0xC0FFEE)
    out: dict[str, np.ndarray] = {}

    out["silence"] = np.zeros((SR // 2, 1), dtype=np.float32)

    impulse = np.zeros(SR // 2, dtype=np.float32)
    impulse[SR // 4] = 1.0
    out["impulse"] = impulse.reshape(-1, 1)

    out["tone_440"] = _tone(440.0, 0.5).reshape(-1, 1)

    sweep = librosa.chirp(fmin=110.0, fmax=4_000.0, sr=SR, duration=1.0).astype(np.float32)
    out["sweep"] = (0.5 * sweep).reshape(-1, 1)

    # Amplitude modulation: a 440 Hz carrier at 4 Hz, the shape a tremolo makes
    # and the one an envelope follower has to track without chattering.
    carrier = _tone(440.0, 1.5, amp=1.0)
    t = np.arange(carrier.size, dtype=np.float64) / SR
    out["am_4hz"] = (carrier * (0.5 + 0.5 * np.sin(2 * np.pi * 4.0 * t)).astype(np.float32) * 0.5).reshape(-1, 1)

    out["clicks_120bpm"] = librosa.clicks(
        times=np.arange(0.0, 4.0, 0.5), sr=SR, length=int(SR * 4)
    ).astype(np.float32).reshape(-1, 1)

    # Tempo ramp 90 -> 150 BPM: beat trackers that lock to a fixed grid follow
    # this badly, which is the point of having it.
    times, now, bpm = [], 0.0, 90.0
    while now < 6.0:
        times.append(now)
        now += 60.0 / bpm
        bpm += 4.0
    out["tempo_ramp"] = librosa.clicks(times=np.array(times), sr=SR, length=int(SR * 6)).astype(np.float32).reshape(-1, 1)

    # Stereo: hard anti-phase, the case that collapses to silence in mono.
    mono = _tone(300.0, 1.0)
    out["stereo_antiphase"] = np.stack([mono, -mono], axis=1)

    # Partially decorrelated: shared centre plus independent noise per side.
    noise_l = rng.normal(0, 0.15, mono.size).astype(np.float32)
    noise_r = rng.normal(0, 0.15, mono.size).astype(np.float32)
    out["stereo_wide"] = np.stack([mono + noise_l, mono + noise_r], axis=1)

    out["stereo_identical"] = np.stack([mono, mono], axis=1)

    # A hard splice: 300 Hz cut mid-cycle into 900 Hz, no crossfade. Stands in
    # for the discontinuous stream the plan asks for - a seek, a source change,
    # a dropped buffer.
    a = _tone(300.0, 0.5)
    b = _tone(900.0, 0.5)
    out["discontinuity"] = np.concatenate([a, b]).reshape(-1, 1)

    return out


def _quantise(data: np.ndarray) -> np.ndarray:
    """To int16 and back, so the oracle analyses exactly what Kotlin will read."""
    clipped = np.clip(data, -1.0, 32767.0 / 32768.0)
    return (np.rint(clipped * 32768.0).astype("<i2").astype(np.float32) / 32768.0)


def _expected(name: str, data: np.ndarray) -> dict:
    """What the oracle says about this fixture."""
    mono = data.mean(axis=1)
    rms = float(np.sqrt(np.mean(mono.astype(np.float64) ** 2)))
    exp: dict = {
        "rms": rms,
        "spectralCentroidHz": (
            float(np.mean(librosa.feature.spectral_centroid(y=mono, sr=SR))) if rms > 0 else 0.0
        ),
        "zeroCrossingRate": float(np.mean(librosa.feature.zero_crossing_rate(y=mono))),
    }

    if data.shape[1] == 2:
        left = data[:, 0].astype(np.float64)
        right = data[:, 1].astype(np.float64)

        # Correlation from L and R directly - the textbook definition. This is
        # the half that is a real oracle: StereoField reaches the same number
        # through mid/side identities (sum(L*R) = sum(m^2) - sum(s^2), and so
        # on) to avoid reconstructing the channels, and if that algebra were
        # wrong nothing inside the app would notice. Computed here the obvious
        # way, in another language, over the same bytes.
        num = float(np.sum(left * right))
        den = np.sqrt(float(np.sum(left * left)) * float(np.sum(right * right)))
        # Silence reads 1, not 0: a silent passage is not "decorrelated".
        exp["stereoCorrelation"] = float(num / den) if den > 0 else 1.0

        # Width is this project's own quantity, not a standard one, so the
        # oracle mirrors its definition - s / (m + s) over RMS magnitudes - and
        # checks transcription and indexing rather than the definition. Note
        # what the naive ratio sqrt(ss)/sqrt(mm) would do here: anti-phase has
        # no mid at all, so it would divide by zero and report the WIDEST
        # possible signal as width 0. It did, before this comment existed.
        mid = (left + right) / 2.0
        side = (left - right) / 2.0
        m = float(np.sqrt(np.mean(mid * mid)))
        s = float(np.sqrt(np.mean(side * side)))
        exp["stereoWidth"] = float(s / (m + s)) if (m + s) > 1e-9 else 0.0

    if name.startswith(("clicks", "tempo")):
        tempo, _ = librosa.beat.beat_track(y=mono, sr=SR)
        exp["tempoBpm"] = float(np.atleast_1d(tempo)[0])

    return exp


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    for stale in OUT.glob("*.pcm"):
        stale.unlink()

    entries = []
    for name, source in sorted(_fixtures().items()):
        data = _quantise(source)
        raw = np.ascontiguousarray(np.rint(np.clip(data, -1.0, 32767.0 / 32768.0) * 32768.0), dtype="<i2").tobytes()
        (OUT / f"{name}.pcm").write_bytes(raw)
        entries.append(
            {
                "name": name,
                "file": f"{name}.pcm",
                "sampleRateHz": SR,
                "channels": int(data.shape[1]),
                "frames": int(data.shape[0]),
                "sha256": hashlib.sha256(raw).hexdigest(),
                "expected": _expected(name, data),
            }
        )

    manifest = {
        "generatorVersion": GENERATOR_VERSION,
        "note": (
            "Generated by tools/oracle/generate_corpus.py. librosa is ORACLE tier: it produces "
            "these values and never enters the runtime. Regenerate rather than hand-edit."
        ),
        "libraries": {
            "python": sys.version.split()[0],
            "librosa": librosa.__version__,
            "numpy": np.__version__,
            "scipy": scipy.__version__,
            "soundfile": soundfile.__version__,
        },
        "sampleFormat": "raw interleaved little-endian int16, no header; divide by 32768 for float",
        "tolerances": TOLERANCES,
        "notCovered": [
            "short real-music excerpts - none are available to this generator, and a "
            "corpus that claimed them without shipping them would be worse than the gap",
            "libebur128 loudness reference values - a second oracle, its own slice",
        ],
        "fixtures": entries,
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    total = sum(len((OUT / e["file"]).read_bytes()) for e in entries)
    print(f"{len(entries)} fixtures, {total / 1024:.0f} KiB, librosa {librosa.__version__}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
