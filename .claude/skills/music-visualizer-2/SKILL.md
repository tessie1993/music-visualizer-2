---
name: music-visualizer-2
description: Repository facts and entrypoint for the strict MusicViz 2.0 harness.
---

# MusicViz repository entrypoint

Read `musicviz-project/musicviz/docs/v2/MASTER_PLAN.md` completely before
acting. That file owns scope, architecture, sequencing, retirement, testing and
release gates. This skill does not define a separate plan.

Verified baseline facts:

- Android/Kotlin/Jetpack Compose application.
- Gradle root: `musicviz-project/musicviz`; app module: `:app`.
- Main package: `dev.musicviz`; JDK 17+; compileSdk 36 at the audit baseline.
- GLES 3.0 baseline with optional GLES 3.1 paths.
- JUnit/Robolectric/Compose tests under `app/src/test`; ktlint, Android lint and
  detekt are configured in Gradle.
- Main-source paths and identifiers are parsed by source-text tests. Search the
  test tree before a move/rename and replace weak gates with behavioral or
  architecture tests only through the master plan.
- Mutable preallocated buffers are intentional in real-time audio/render paths.

Do not use generic KMP, npm, TypeScript, Python, Kotest, MockK, Kover, browser,
GAN-harness, SaaS, or autonomous-loop instructions in this repository unless a
future accepted ADR deliberately adds the relevant tool.
