# Rebuilding libprojectM for Android

The two shared objects the MilkDrop style needs live in the repository:

```
app/src/main/jniLibs/arm64-v8a/libprojectM-4.so     the engine (LGPL-2.1, dynamically linked), STOCK v4.1.7
app/src/main/jniLibs/arm64-v8a/libmilkdropjni.so    the JNI bridge built from tools/milkdrop_jni.c
```

The APK ships arm64-v8a only.

> **Do not build these by hand.** `.github/workflows/native-libs.yml` ("Rebuild
> native libs (16 KB aligned)") automates the whole recipe below, and it is the
> only route that gets the page alignment right: NDK r28, the explicit
> `max-page-size=16384` linker flags, and a `readelf` check that fails the run
> if any ELF LOAD segment comes out below 16384. Google Play requires 16 KB page
> support for apps targeting Android 15+, and a hand build that misses it fails
> silently until the app will not load on a device.
>
> Run it from Actions with the projectM release tag as input. It uploads the two
> stripped .so files as the `jniLibs-arm64-v8a-16k` artifact — it does **not**
> commit them. Download it, drop both into `app/src/main/jniLibs/arm64-v8a/` and
> commit; the gate in `.github/workflows/release.yml` re-checks the alignment of
> whatever is committed, so a hand-built .so that slipped through is caught
> before a release rather than on a phone.
>
> A fresh engine build is not a repackage: run the MilkDrop items in
> `docs/DEVICE_CHECKS.md` (1-4, 33) afterwards.

## The engine is stock — no patches

The engine is built from the upstream release tag exactly as shipped.
`projectm_opengl_render_frame` ends its frame on the DEFAULT framebuffer (the
only target where upstream's `glDrawBuffers(GL_BACK)` is legal), and
`MilkdropScene` copies the frame off framebuffer 0 into its own texture for
the post/composite pipeline.

An earlier integration patched a render-to-FBO API onto the engine
(`projectm_opengl_render_frame_fbo`), and the patch went stale twice — once
declaring the symbol without defining it (JNI link death on a device), once
leaving `GL_BACK` set on a framebuffer object (MilkDrop permanently black on
conformant drivers). The rebuild's premise is that there is no patch to go
stale; `MilkdropIntegrationTest` fails the build if a `.patch` reappears in
`tools/` or an apply step reappears in the workflow.

## The recipe the workflow implements

```
# IMPORTANT: build from a release tag, never master. master carries an
# experimental GL bootstrap (GLResolver/GladLoader "strict context gate") that
# is in no release; the .so it produces has no GLES linkage at all, so
# projectm_create can fail on-device and the style renders black with no error
# anywhere. This was the root cause of the v0.3.2 MilkDrop bug.
git clone --branch v4.1.7 --depth 1 --recurse-submodules \
  https://github.com/projectM-visualizer/projectm.git

cmake -B build-android -S projectm \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON -DENABLE_PLAYLIST=ON \
  -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384,-z,common-page-size=16384" \
  -G Ninja
ninja -C build-android

# The bridge. Its exported symbols are exactly what MilkdropEngine.kt declares
# as external fun (nativeCreate/Destroy, nativeResize, nativeAddPcmMono,
# nativeRender, nativeSetTexturePaths, nativeLoadPreset, nativeGetLastError,
# nativeSetBeatSensitivity, nativeSetPresetLocked); a missing one is an
# UnsatisfiedLinkError at first use, not at build time — the workflow checks
# the exports with llvm-nm, and JniAbiTest re-checks the committed binary.
# The second -I is for the CMake-GENERATED projectM_export.h, which lives in
# the build tree, not the source tree.
$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang \
  -shared -fPIC -O2 -o libmilkdropjni.so \
  musicviz-project/musicviz/tools/milkdrop_jni.c \
  -I projectm/src/api/include -I build-android/src/api/include -L. -lprojectM-4 -llog \
  -Wl,-z,max-page-size=16384,-z,common-page-size=16384
```

## Adding an ABI

Repeat the cmake/ninja/clang steps per ABI, drop each pair into its own
`jniLibs/<abi>/` directory, and extend `abiFilters` in `app/build.gradle.kts`.
Every ABI is checked by the same alignment gate.
