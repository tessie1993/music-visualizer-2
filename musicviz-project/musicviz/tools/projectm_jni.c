#include <jni.h>
#include <string.h>
#include <stdio.h>
#include <android/log.h>
#include <projectM-4/projectM.h>
#include <projectM-4/render_opengl.h>

#define TAG "projectm-jni"
/* LOGI names user-chosen preset and texture paths, and native code cannot see
 * BuildConfig.DEBUG - so the gate that keeps RingLog's echo out of release
 * builds does not reach here. native-libs.yml compiles this file with -DNDEBUG,
 * so the shipped bridge carries none of these. Errors still log either way. */
#ifdef NDEBUG
#define LOGI(...) ((void) 0)
#else
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#endif
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/*
 * JNI bridge over the STOCK projectM 4.2 C API (no patches).
 *
 * WHY THIS FILE EXISTS IN THIS SHAPE
 *
 * The previous bridge could only call projectm_opengl_render_frame(), which in
 * projectM <= 4.1.7 ends every frame on the DEFAULT framebuffer - ProjectM.cpp
 * hardcoded `glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)` with an upstream ToDo
 * against it. The app therefore had to let the engine paint framebuffer 0 and
 * then claw the frame back with glReadBuffer(GL_BACK) + glCopyTexSubImage2D.
 * That round-trip is unsound here for three independent reasons:
 *
 *   - it stamps the raw, ungraded engine frame onto the window every frame,
 *     underneath whatever the compositor draws afterwards;
 *   - offscreen and export render into an FBO whose EGL draw surface is not
 *     the screen, so the readback samples a surface that may be smaller than
 *     the copy region, or not a colour surface at all - undefined texels,
 *     which is what "MilkDrop renders black" actually was;
 *   - the copy is sized from the WINDOW while the scene renders at the
 *     thermal governor's scaled resolution, so the two can disagree.
 *
 * projectM 4.2 fixes it upstream: RenderFrame takes a target framebuffer and
 * only forces the GL_BACK draw buffer when that target is 0. So this bridge
 * exposes projectm_opengl_render_frame_fbo and the app hands it an FBO it
 * owns. There is no readback, no dependence on the default framebuffer, and
 * the on-screen and offscreen paths run exactly the same code.
 *
 * All GL-touching functions (create, render, load, destroy) must run on the
 * GL thread, which also means the error buffer needs no locking.
 */

static char g_last_error[512] = {0};

static void on_preset_switch_failed(const char *preset_filename, const char *message, void *user_data) {
    (void) user_data;
    snprintf(g_last_error, sizeof(g_last_error), "%s: %s",
             preset_filename ? preset_filename : "?", message ? message : "unknown error");
    LOGE("preset switch failed: %s", g_last_error);
}

JNIEXPORT jlong JNICALL
Java_dev_geode_render_scene_ProjectMEngine_nativeCreate(JNIEnv *env, jobject thiz) {
    projectm_handle h = projectm_create();
    if (h) {
        projectm_set_fps(h, 60);
        projectm_set_mesh_size(h, 48, 32);
        projectm_set_soft_cut_duration(h, 3.0);
        projectm_set_preset_duration(h, 999999.0); /* never auto-switch */
        projectm_set_preset_locked(h, true);
        projectm_set_aspect_correction(h, true);
        projectm_set_preset_switch_failed_event_callback(h, on_preset_switch_failed, NULL);
        LOGI("projectM instance created");
    } else {
        LOGE("projectm_create returned NULL");
    }
    return (jlong) h;
}

JNIEXPORT void JNICALL
Java_dev_geode_render_scene_ProjectMEngine_nativeDestroy(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle) projectm_destroy((projectm_handle) handle);
}

/*
 * The size projectM renders AT, which is the size of the target framebuffer -
 * not the window. RenderFrame sets glViewport(0, 0, w, h) from this before it
 * composites into the target, so a value that disagrees with the target's
 * dimensions letterboxes or crops the frame.
 */
JNIEXPORT void JNICALL
Java_dev_geode_render_scene_ProjectMEngine_nativeResize(JNIEnv *env, jobject thiz, jlong handle,
                                                        jint width, jint height) {
    if (handle && width > 0 && height > 0) {
        projectm_set_window_size((projectm_handle) handle, (size_t) width, (size_t) height);
    }
}

JNIEXPORT void JNICALL
Java_dev_geode_render_scene_ProjectMEngine_nativeAddPcmMono(JNIEnv *env, jobject thiz, jlong handle,
                                                            jfloatArray samples, jint count) {
    if (!handle || !samples || count <= 0) return;
    /* Clamp to the array's real length: a Java-side caller bug must surface
     * as short audio, not as an out-of-bounds heap read inside projectM. */
    jsize len = (*env)->GetArrayLength(env, samples);
    if (count > len) count = len;
    if (count <= 0) return;
    jfloat *data = (*env)->GetFloatArrayElements(env, samples, NULL);
    if (data) {
        projectm_pcm_add_float((projectm_handle) handle, data, (unsigned int) count, PROJECTM_MONO);
        (*env)->ReleaseFloatArrayElements(env, samples, data, JNI_ABORT);
    }
}

/*
 * Renders one frame into [fbo], which the caller owns and keeps.
 *
 * fbo == 0 means the default framebuffer and is legal, but the app never asks
 * for it: every caller has a colour-attached FBO of its own. projectM binds
 * the target itself and leaves it bound, along with a viewport sized from
 * nativeResize - the Kotlin side restores both.
 */
JNIEXPORT void JNICALL
Java_dev_geode_render_scene_ProjectMEngine_nativeRenderToFbo(JNIEnv *env, jobject thiz,
                                                             jlong handle, jint fbo) {
    if (handle) projectm_opengl_render_frame_fbo((projectm_handle) handle, (uint32_t) fbo);
}

JNIEXPORT void JNICALL
Java_dev_geode_render_scene_ProjectMEngine_nativeSetTexturePaths(JNIEnv *env, jobject thiz, jlong handle,
                                                                 jobjectArray dirs) {
    if (!handle || !dirs) return;
    jsize n = (*env)->GetArrayLength(env, dirs);
    if (n <= 0 || n > 8) return;
    const char *paths[8];
    /* What GetStringUTFChars actually returned, kept apart from paths[]:
     * it is NULL on OOM, and NULL must neither be logged, handed to
     * projectM, nor released (all three are UB). */
    const char *owned[8];
    jstring strs[8];
    for (jsize i = 0; i < n; i++) {
        strs[i] = (jstring) (*env)->GetObjectArrayElement(env, dirs, i);
        owned[i] = strs[i] ? (*env)->GetStringUTFChars(env, strs[i], NULL) : NULL;
        paths[i] = owned[i] ? owned[i] : "";
        LOGI("texture search path[%d]: %s", (int) i, paths[i]);
    }
    projectm_set_texture_search_paths((projectm_handle) handle, paths, (size_t) n);
    for (jsize i = 0; i < n; i++) {
        if (owned[i]) (*env)->ReleaseStringUTFChars(env, strs[i], owned[i]);
    }
}

JNIEXPORT void JNICALL
Java_dev_geode_render_scene_ProjectMEngine_nativeLoadPreset(JNIEnv *env, jobject thiz, jlong handle,
                                                            jstring path, jboolean smooth) {
    if (!handle || !path) return;
    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath) {
        g_last_error[0] = '\0';
        LOGI("loading preset: %s (smooth=%d)", cpath, (int) smooth);
        projectm_load_preset_file((projectm_handle) handle, cpath, smooth);
        projectm_set_preset_locked((projectm_handle) handle, true);
        (*env)->ReleaseStringUTFChars(env, path, cpath);
    }
}

JNIEXPORT jstring JNICALL
Java_dev_geode_render_scene_ProjectMEngine_nativeGetLastError(JNIEnv *env, jobject thiz) {
    if (g_last_error[0] == '\0') return NULL;
    jstring result = (*env)->NewStringUTF(env, g_last_error);
    g_last_error[0] = '\0';
    return result;
}

JNIEXPORT void JNICALL
Java_dev_geode_render_scene_ProjectMEngine_nativeSetBeatSensitivity(JNIEnv *env, jobject thiz,
                                                                    jlong handle, jfloat value) {
    if (handle) projectm_set_beat_sensitivity((projectm_handle) handle, value);
}

JNIEXPORT void JNICALL
Java_dev_geode_render_scene_ProjectMEngine_nativeSetPresetLocked(JNIEnv *env, jobject thiz,
                                                                 jlong handle, jboolean locked) {
    if (handle) projectm_set_preset_locked((projectm_handle) handle, locked);
}
