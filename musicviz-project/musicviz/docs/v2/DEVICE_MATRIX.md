# MusicViz 2.0 Device Matrix

Replaces `docs/DEVICE_CHECKS.md`, which was a reconstruction with unknown
entries. **`NOT RUN` is never a pass, and is never inherited from the old
checklist.** A cell becomes `PASS` only with a recorded device, build and date
in `VERIFICATION_LOG.md`.

## Environment constraint recorded at Phase 0

This session's container has **no physical or emulated Android device**, and the
app is built `arm64-v8a` only, so no emulator image can run it even in
principle (see `INVENTORY.md` → Native packaging). Every cell below is therefore
`NOT RUN` at the baseline, and every GL, performance, thermal and lifecycle gate
in the master plan is **`BLOCKED_ENVIRONMENT`** until a device is available.

This does not block Phases 0–3 (JVM-testable), but it hard-blocks the Phase 4
exit gate onward.

## Reference devices

| Tier | Device | SoC / GPU | OS / API | Status |
|---|---|---|---|---|
| Low | — | — | — | `NOT PROVISIONED` |
| Mid | — | — | — | `NOT PROVISIONED` |
| High | — | — | — | `NOT PROVISIONED` |

At least one Adreno and one Mali device are required before release, plus one
GLES 3.1 compute-capable device (plan §5).

## Matrix

Legend: `NOT RUN` · `PASS` · `FAIL` · `BLOCKED_ENVIRONMENT` · `N/A (justified)`

### Platform

| Cell | Status |
|---|---|
| API 26 compatibility path | `NOT RUN` |
| API 29 compatibility path | `NOT RUN` |
| API 34 lifecycle/permission/FGS | `NOT RUN` |
| API 35 lifecycle + `mediaProcessing` FGS | `NOT RUN` |
| API 36 lifecycle/permission/FGS | `NOT RUN` |
| 4 KB page-size arm64 | `NOT RUN` |
| 16 KB page-size arm64 | `NOT RUN` |

### GPU

| Cell | Status |
|---|---|
| Adreno GLES 3.0 baseline | `NOT RUN` |
| Mali GLES 3.0 baseline | `NOT RUN` |
| GLES 3.1 compute path | `NOT RUN` |
| No half-float filter/render target fallback | `NOT RUN` |
| EGL context loss + recreation | `NOT RUN` |
| Shader compile failure → last-known-good | `NOT RUN` |

### Audio

| Cell | Status |
|---|---|
| Player PCM 44.1 kHz mono/stereo | `NOT RUN` |
| Player PCM 48 kHz mono/stereo | `NOT RUN` |
| Pause/resume, seek, gapless track switch | `NOT RUN` |
| Microphone grant / deny / revoke | `NOT RUN` |
| Playback capture: allowed, protected/silent app, projection revoke | `NOT RUN` |

### Lifecycle and outputs

| Cell | Status |
|---|---|
| Activity rotate / recreate | `NOT RUN` |
| Process background/foreground, screen off, audio continuing | `NOT RUN` |
| Wallpaper visible / hidden / preview / settings change | `NOT RUN` |
| Battery saver, thermal pressure | `NOT RUN` |
| Export cancel, UI recreation, storage full, encoder rejection | `NOT RUN` |
| Export capability ladder (4K60 → 4K30 → 1080p60 → 1080p30 → approved fallback) | `NOT RUN` |

### Performance (plan §5 targets)

| Cell | Target | Status |
|---|---|---|
| Live balanced | p95 ≤ 16.7 ms @ 1080p60, ≤1% missed frames over 10 min | `NOT RUN` |
| Live low | p95 ≤ 33.3 ms @ 720p30 over 20 min | `NOT RUN` |
| Analysis | avg < 3 ms, p95 < 6 ms per base hop | `NOT RUN` |
| Memory | balanced ≤128 MiB, high ≤192 MiB, 4K export ≤384 MiB | `NOT RUN` |
| A/V drift | ≤ ±20 ms at start, after seek, at end | `NOT RUN` |
| Thermal degradation and recovery | no crash or black output at severe | `NOT RUN` |

### Accessibility and safety

| Cell | Status |
|---|---|
| Safe-visuals onboarding, fresh install | `NOT RUN` |
| Safe-visuals migration, upgraded install with no v2 choice | `NOT RUN` |
| Reduced motion independent of safety | `NOT RUN` |
| TalkBack traversal and labels | `NOT RUN` |
| Font scaling without clipped controls | `NOT RUN` |
