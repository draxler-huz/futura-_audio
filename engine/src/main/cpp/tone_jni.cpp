// JNI entry points for com.example.wakeupmusic.engine.EngineBridge.
// The Kotlin handle is a raw pointer to a ToneEngine. Kotlin owns it and must call nativeDestroy once.
#include <jni.h>

#include "tone_engine.h"

using futura::ToneEngine;

namespace {

ToneEngine* engineOf(jlong handle) { return reinterpret_cast<ToneEngine*>(handle); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_wakeupmusic_engine_EngineBridge_nativeCreate(JNIEnv* /*env*/, jobject /*thiz*/) {
    return reinterpret_cast<jlong>(new ToneEngine());
}

JNIEXPORT void JNICALL
Java_com_example_wakeupmusic_engine_EngineBridge_nativeDestroy(JNIEnv* /*env*/, jobject /*thiz*/,
                                                               jlong handle) {
    delete engineOf(handle);
}

JNIEXPORT jint JNICALL
Java_com_example_wakeupmusic_engine_EngineBridge_nativeOpen(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    ToneEngine* e = engineOf(handle);
    if (e == nullptr) return futura::kInvalidState;
    return e->open();
}

JNIEXPORT void JNICALL
Java_com_example_wakeupmusic_engine_EngineBridge_nativeClose(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (ToneEngine* e = engineOf(handle)) e->close();
}

JNIEXPORT void JNICALL
Java_com_example_wakeupmusic_engine_EngineBridge_nativeSetPlaying(JNIEnv* /*env*/, jobject /*thiz*/,
                                                                  jlong handle, jboolean playing) {
    if (ToneEngine* e = engineOf(handle)) e->setPlaying(playing == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_example_wakeupmusic_engine_EngineBridge_nativeSetFrequency(JNIEnv* /*env*/, jobject /*thiz*/,
                                                                    jlong handle, jfloat hz) {
    if (ToneEngine* e = engineOf(handle)) e->setFrequency(hz);
}

JNIEXPORT void JNICALL
Java_com_example_wakeupmusic_engine_EngineBridge_nativeSetGain(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle,
                                                               jfloat gain) {
    if (ToneEngine* e = engineOf(handle)) e->setGain(gain);
}

// Returns [sampleRate, framesPerBurst, audioApi, deviceId], or null if the handle is invalid.
JNIEXPORT jintArray JNICALL
Java_com_example_wakeupmusic_engine_EngineBridge_nativeStreamInfo(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    ToneEngine* e = engineOf(handle);
    if (e == nullptr) return nullptr;

    const futura::ToneStreamInfo info = e->info();
    const jint values[4] = {info.sampleRate, info.framesPerBurst, info.audioApi, info.deviceId};
    jintArray out = env->NewIntArray(4);
    if (out != nullptr) env->SetIntArrayRegion(out, 0, 4, values);
    return out;
}

JNIEXPORT jint JNICALL
Java_com_example_wakeupmusic_engine_EngineBridge_nativeXRunCount(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    ToneEngine* e = engineOf(handle);
    return e != nullptr ? static_cast<jint>(e->xRunCount()) : 0;
}

}  // extern "C"
