# MusicViz R8 configuration (release builds only).
#
# The default proguard-android-optimize.txt already keeps annotations, the
# support/AndroidX entry points and — via -keepclasseswithmembernames — any
# class that declares native methods. The rules below cover the things R8
# cannot see from bytecode alone.

# ---------------------------------------------------------------------------
# JNI bridge to libprojectmjni.so
# ---------------------------------------------------------------------------
# pm_jni.c uses static symbol registration
# (Java_dev_musicviz_render_scene_PMBridge_nativeCreate, ...), so both the
# class name and the method names have to survive minification exactly.
-keepclasseswithmembernames,includedescriptorclasses class dev.musicviz.render.scene.PMBridge {
    native <methods>;
}
-keep class dev.musicviz.render.scene.PMBridge { *; }

# Belt and braces for anything else that grows a native method later.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ---------------------------------------------------------------------------
# Media3 / ExoPlayer
# ---------------------------------------------------------------------------
# Media3 resolves extractors, decoders and audio-processor implementations by
# name at runtime; it ships consumer rules for its own classes, but the
# reflective renderer lookup also touches platform audiofx wrappers.
-dontwarn androidx.media3.**
-keep class androidx.media3.exoplayer.audio.** { *; }

# ---------------------------------------------------------------------------
# Kotlin / coroutines
# ---------------------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ---------------------------------------------------------------------------
# JTransforms
# ---------------------------------------------------------------------------
# Pulls in optional Apache Commons Math / large-array helpers that are not on
# the Android classpath; the FFT paths MusicViz uses do not need them.
-dontwarn org.jtransforms.**
-dontwarn pl.edu.icm.**
-dontwarn org.apache.commons.math3.**

# ---------------------------------------------------------------------------
# Crash reports
# ---------------------------------------------------------------------------
# MusicVizApp writes crash-latest.txt for the in-app crash banner. Keeping
# source file + line numbers makes those traces readable while still
# obfuscating names; the mapping file is uploaded to Play alongside the AAB.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
