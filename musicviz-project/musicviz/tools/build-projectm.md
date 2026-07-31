# Rebuilding libprojectM for Android

> **Do not run this by hand.** `.github/workflows/native-libs.yml` automates the
> whole thing and gates the result: it resolves an NDK r28+ (16 KB page alignment
> is mandatory for Google Play), applies `projectm-v417-render-fbo-backport.patch`,
> links the JNI bridge with `-Wl,--no-undefined`, strips, then refuses to commit
> unless every ELF LOAD segment is >= 16384, the SONAME is unversioned, and every
> `external fun` in `PMBridge.kt` is exported. A hand build skips all of that and
> the failure is silent until the app crashes on a device.
>
> Run it from Actions ("Rebuild native libs (16 KB aligned)"); it commits the
> rebuilt .so back to the branch. Then run the MilkDrop items in
> docs/DEVICE_CHECKS.md - a fresh engine build is not a repackage.
>
> The recipe below is kept as the reference the workflow implements.

```
# IMPORTANT: build from a release tag, never master (master carries
# experimental GL bootstrap code that breaks Android rendering).
git clone --branch v4.1.7 --depth 1 --recurse-submodules https://github.com/projectM-visualizer/projectm.git
cmake -B build-android -S projectm \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON -DENABLE_PLAYLIST=ON -G Ninja
ninja -C build-android
```
Apply `projectm-v417-render-fbo-backport.patch` (the ONLY complete backport;
it alone patches ProjectMCWrapper.cpp to actually define
`projectm_opengl_render_frame_fbo`). Then compile `pm_jni.c` (see app/src/main/jniLibs notes) with the NDK clang for
aarch64, linking `-lprojectM-4`, then copy both .so files into
`app/src/main/jniLibs/arm64-v8a/`. Add more ABIs by repeating per ABI and
extending `abiFilters`.
