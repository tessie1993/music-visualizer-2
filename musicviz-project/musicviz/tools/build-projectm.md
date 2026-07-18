# Rebuilding libprojectM for Android

```
git clone --depth 1 --recurse-submodules https://github.com/projectM-visualizer/projectm.git
cmake -B build-android -S projectm \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON -DENABLE_PLAYLIST=ON -G Ninja
ninja -C build-android
```
Compile `pm_jni.c` (see app/src/main/jniLibs notes) with the NDK clang for
aarch64, linking `-lprojectM-4`, then copy both .so files into
`app/src/main/jniLibs/arm64-v8a/`. Add more ABIs by repeating per ABI and
extending `abiFilters`.
