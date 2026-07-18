#include <jni.h>
#include <projectM-4/projectM.h>

/*
 * Minimal JNI bridge over the projectM 4 C API. All functions that touch GL
 * (create, render, destroy after create) must be called on the GL thread.
 */

JNIEXPORT jlong JNICALL
Java_dev_musicviz_render_scene_PMBridge_nativeCreate(JNIEnv *env, jobject thiz) {
    projectm_handle h = projectm_create();
    if (h) {
        projectm_set_fps(h, 60);
        projectm_set_mesh_size(h, 48, 32);
        projectm_set_soft_cut_duration(h, 3.0);
        projectm_set_preset_duration(h, 30.0);
        projectm_set_aspect_correction(h, true);
    }
    return (jlong) h;
}

JNIEXPORT void JNICALL
Java_dev_musicviz_render_scene_PMBridge_nativeDestroy(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle) projectm_destroy((projectm_handle) handle);
}

JNIEXPORT void JNICALL
Java_dev_musicviz_render_scene_PMBridge_nativeResize(JNIEnv *env, jobject thiz, jlong handle,
                                                     jint width, jint height) {
    if (handle) projectm_set_window_size((projectm_handle) handle, (size_t) width, (size_t) height);
}

JNIEXPORT void JNICALL
Java_dev_musicviz_render_scene_PMBridge_nativeAddPcmMono(JNIEnv *env, jobject thiz, jlong handle,
                                                         jfloatArray samples, jint count) {
    if (!handle || !samples) return;
    jfloat *data = (*env)->GetFloatArrayElements(env, samples, NULL);
    if (data) {
        projectm_pcm_add_float((projectm_handle) handle, data, (unsigned int) count, PROJECTM_MONO);
        (*env)->ReleaseFloatArrayElements(env, samples, data, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_dev_musicviz_render_scene_PMBridge_nativeRender(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle) projectm_opengl_render_frame((projectm_handle) handle);
}

JNIEXPORT void JNICALL
Java_dev_musicviz_render_scene_PMBridge_nativeLoadPreset(JNIEnv *env, jobject thiz, jlong handle,
                                                         jstring path, jboolean smooth) {
    if (!handle || !path) return;
    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath) {
        projectm_load_preset_file((projectm_handle) handle, cpath, smooth);
        (*env)->ReleaseStringUTFChars(env, path, cpath);
    }
}

JNIEXPORT void JNICALL
Java_dev_musicviz_render_scene_PMBridge_nativeSetBeatSensitivity(JNIEnv *env, jobject thiz,
                                                                 jlong handle, jfloat value) {
    if (handle) projectm_set_beat_sensitivity((projectm_handle) handle, value);
}

JNIEXPORT void JNICALL
Java_dev_musicviz_render_scene_PMBridge_nativeSetPresetLocked(JNIEnv *env, jobject thiz,
                                                              jlong handle, jboolean locked) {
    if (handle) projectm_set_preset_locked((projectm_handle) handle, locked);
}
