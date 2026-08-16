# 0003 — The mono downmix is the front pair, not the mean of every channel

Status: accepted
Slice: V2-2-05b
Date: 2026-08-16

## Context

Every band, beat, chroma and waveform the app draws is computed from one mono signal. Today
`PcmRingBuffer` derives it at capture as the mean of **all** decoded channels:

```kotlin
repeat(channelCount) { acc += samples[s]; s++ }
data[slot] = acc / channelCount
```

`SampleRing` keeps planar channels and derives the pair on read, as §5.1 requires ("stereo
preserved to the analysis boundary; downmixing happens per-feature, not at capture"). But it
keeps **two** channels, and V2-2-02 chose the front pair deliberately: folding surrounds into a
width measurement reports an image no two-speaker playback produces.

That choice, made for *width*, silently decides the *mono downmix* too. For a 5.1 source the
legacy mid is the mean of six channels including LFE; the V2 mid is the mean of the front two.
`MidSideParityTest` measures the divergence rather than assuming it: bit-identical for mono and
stereo, differing on more than half the samples of a five-channel fixture.

## Decision

The V2 mono downmix is the mean of the front pair. Sources with more than two channels change
behaviour when the readers migrate.

## Why

- **It matches what is heard.** The tap sits above the sink, so a 5.1 stream reaches it with six
  channels, but the device plays two. Weighting LFE and surrounds into the signal that drives the
  visuals describes audio the listener is not getting.
- **The alternative costs the property §5.1 asks for.** Preserving an all-channel mean means
  computing it at capture, which is the mid/side-at-capture design being replaced. Keeping every
  channel planar instead would make the ring's width grow with the source and its capacity a
  function of content.
- **The affected set is small.** Multichannel content on a phone is rare, and the difference is
  in a downmix, not in whether audio arrives.

## Consequences

- Mono and stereo — every common case — are bit-identical, and pinned as such.
- Surround content produces different visuals after the reader migration than before it. Named
  here rather than discovered from a bug report.
- `MidSideWindow` needs `SampleRing.sourceChannelCount`, because a mono source leaves channel 1
  silent and averaging it in would halve the signal. That trap is a test, not a comment.
- If a future slice wants the all-channel mean back, it belongs as a *feature* computed from a
  wider ring, not as a capture-time fold — and it needs a new ADR superseding this one.
