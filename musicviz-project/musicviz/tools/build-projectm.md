# Rebuilding libprojectM for Android

The two shared objects the MilkDrop style needs live in the repository, once
per ABI:

```
app/src/main/jniLibs/<abi>/libprojectM-4.so     the engine (LGPL-2.1, dynamically linked), STOCK projectM 4.2
app/src/main/jniLibs/<abi>/libprojectmjni.so    the JNI bridge built from tools/projectm_jni.c
```

The APK ships `arm64-v8a` (devices) and `x86_64` (emulators). The x86_64 pair
is not a courtesy: the CI instrumented suite runs on an x86_64 emulator, and
without libs for it `ProjectMEngine.available` is false there — which made
the whole MilkDrop pipeline untestable off a phone, and is how a silently
black MilkDrop shipped past a fully green build more than once.

> **Do not build these by hand.** `.github/workflows/native-libs.yml` automates
> the whole recipe, and it is the only route that gets the page alignment
> right: NDK r28, the explicit `max-page-size=16384` linker flags, and a
> `readelf` check that fails the run if any ELF LOAD segment comes out below
> 16384. Google Play requires 16 KB page support for apps targeting Android
> 15+, and a hand build that misses it fails silently until the app will not
> load on a device.
>
> Run it from Actions. It builds every ABI in the matrix and uploads each one's
> pair as `jniLibs-<abi>-16k`, together with that ABI's `SHA256SUMS`. Download
> the artifact, drop all three files into `app/src/main/jniLibs/<abi>/` and
> commit them together; `android.yml` re-checks the hashes on every pull
> request, so a hand-built .so that slipped through is caught before a release
> rather than on a phone.
>
> A fresh engine build is not a repackage: run the MilkDrop items in
> `docs/DEVICE_CHECKS.md` (1-4, 33) afterwards.

## The engine is stock — no patches, and now none are needed

The engine is built from upstream exactly as shipped.

This used to cost something. Through projectM 4.1.7, `ProjectM::RenderFrame`
hardcoded `glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)` — upstream's own
`// ToDo: Allow external apps to provide a custom target framebuffer` sat on
the line — so the engine could only ever end a frame on the DEFAULT
framebuffer, and the app had to copy the result back off framebuffer 0. See
`ProjectMScene.kt` for the three independent ways that round-trip was unsound.

Two earlier attempts patched a render-to-FBO API onto the engine, and the patch
went stale twice — once declaring the symbol without defining it (JNI link
death on a device), once leaving `GL_BACK` set on a framebuffer object
(MilkDrop permanently black on conformant drivers). **Do not reintroduce a
patch** — no `.patch` under `tools/`, no apply step in the workflow. A patched
engine is the one failure mode this integration has actually shipped, twice.

projectM 4.2 makes the patch unnecessary: `RenderFrame` takes a target
framebuffer object, `projectm_opengl_render_frame_fbo()` exposes it on the C
API, and the `GL_BACK` draw-buffer forcing is now conditional on that target
being 0. The bridge calls it and the app hands over an FBO it owns.

## The GLES 3.2 floor — read this before changing the pinned revision

projectM 4.2 raised its own GLES requirement, and the check is **not**
overridable by any CMake option or environment variable
(`Renderer/Platform/GladLoader.cpp`):

```cpp
#ifdef USE_GLES
    glCheck.WithApi(GLApi::OpenGLES)
           .WithMinimumVersion(3, 2)
           .WithMinimumShaderLanguageVersion(3, 20)
```

That runs inside `projectm_create()`, before the laxer `CheckGLSLVersion()`
(which still accepts GLSL ES 3.00 and is therefore dead code on this path).
Below the floor, `projectm_create()` returns null.

The app declares `android:glEsVersion="0x00030000"`, `minSdk 26`, and every one
of its own shaders is `#version 300 es`. **MilkDrop is the only style whose GL
floor is higher than the app's.** There is deliberately no client-side version
gate: on an ES 3.0/3.1 context `projectm_create()` returns null, `nativeCreate`
returns 0, and `ProjectMScene` reports "projectM engine failed to initialize"
through the error channel it already has. The style stays listed and says why
it did not start, rather than vanishing from the picker with no explanation.

If a future projectM lowers that floor back to 3.0, nothing here has to change.

## Pinning

`native-libs.yml` defaults `projectm_ref` to a **commit**, not a branch:

```
2f244141320f6b97b09bf99964cc72a4efdfcfd3   ("Update libprojectM version to 4.2.0")
```

4.2 has no release tag yet, and the whole value of the recorded `SHA256SUMS` is
that the committed blobs name the exact source they came from. Do not point
this at `master`. When 4.2.0 is tagged, switch the default to the tag.

The workflow fails early, with a message naming the cause, if the chosen
revision has no `projectm_opengl_render_frame_fbo` — which is what building any
4.1.x would do.

## Upstream survey (why projectM, and not the alternatives)

- **MilkDrop3** (milkdrop2077/MilkDrop3) is the Windows Direct3D lineage of
  the original Winamp plugin. Its preset semantics are what projectM
  reimplements; none of its rendering code is portable to Android GLES.
  projectM IS the open-source MilkDrop for GLES — there is no closer source.
