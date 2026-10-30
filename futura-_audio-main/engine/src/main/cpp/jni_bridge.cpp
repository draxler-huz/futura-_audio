// JNI entry points for com.example.wakeupmusic.audio.NativeBridge.
// The Kotlin handle is a raw pointer to an AudioEngine. Kotlin owns it and must call nativeDestroy once.
#include <jni.h>

#include <algorithm>

#include "audio_engine.h"

using futura::AudioEngine;
using futura::CompressorParams;
using futura::EqBand;
using futura::EqType;
using futura::VolumeCurve;

namespace {

AudioEngine* engineOf(jlong handle) { return reinterpret_cast<AudioEngine*>(handle); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_wakeupmusic_audio_NativeBridge_nativeCreate(JNIEnv* /*env*/, jobject /*thiz*/) {
    return reinterpret_cast<jlong>(new AudioEngine());
}

JNIEXPORT void JNICALL
Java_com_example_wakeupmusic_audio_NativeBridge_nativeDestroy(JNIEnv* /*env*/, jobject /*thiz*/,
                                                              jlong handle) {
    delete engineOf(handle);
}

JNIEXPORT jint JNICALL
Java_com_example_wakeupmusic_audio_NativeBridge_nativeLoadFile(JNIEnv* /*env*/, jobject /*thiz*/,
                                                               jlong handle, jint fd, jlong offset,
                                                               jlong length) {
    AudioEngine* e = engineOf(handle);
    if (e == nullptr) return futura::kInvalidState;
    return e->loadFile(static_cast<int>(fd), static_cast<int64_t>(offset), static_cast<int64_t>(length));
}

JNIEXPORT jint JNICALL
Java_com_example_wakeupmusic_audio_NativeBridge_nativeStart(JNIEnv* /*env*/, jobject /*thiz*/,
                                                            jlong handle) {
    AudioEngine* e = engineOf(handle);
    if (e == nullptr) return futura::kInvalidState;
    return e->start();
}

JNIEXPORT void JNICALL
Java_com_example_wakeupmusic_audio_NativeBridge_nativePause(JNIEnv* /*env*/, jobject /*thiz*/,
                                                            jlong handle) {
    if (AudioEngine* e = engineOf(handle)) e->pause();
}

JNIEXPORT void JNICALL
Java_com_example_wakeupmusic_audio_NativeBridge_nativeResume(JNIEnv* /*env*/, jobject /*thiz*/,
                                                             jlong handle) {
    if (AudioEngine* e = engineOf(handle)) e->resume();
}

JNIEXPORT void JNICALL
Java_com_example_wakeupmusic_audio_NativeBridge_nativeStop(JNIEnv* /*env*/, jobject /*thiz*/,
                                                           jlong handle) {
    if (AudioEngine* e = engineOf(handle)) e->stop();
}

JNIEXPORT void JNICALL
Java_com_example_wakeupmusic_audio_NativeBridge_nativeSetVolume(JNIEnv* /*env*/, jobject /*thiz*/,
                                                                jlong handle, jfloat target,
                                                                jint rampMs, jint curve) {
    if (AudioEngine* e = engineOf(handle)) {
        e->setVolume(target, rampMs, curve == 1 ? VolumeCurve::kLogarithmic : VolumeCurve::kLinear);
    }
}

JNIEXPORT void JNICALL
Java_com_example_wakeupmusic_audio_NativeBridge_nativeSetCompressor(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jboolean enabled, jfloat thresholdDb,
        jfloat ratio, jfloat attackMs, jfloat releaseMs, jfloat makeupDb, jfloat ceilingDb) {
    AudioEngine* e = engineOf(handle);
    if (e == nullptr) return;
    CompressorParams p;
    p.enabled = enabled == JNI_TRUE;
    p.thresholdDb = thresholdDb;
    p.ratio = ratio;
    p.attackMs = attackMs;
    p.releaseMs = releaseMs;
    p.makeupDb = makeupDb;
    p.ceilingDb = ceilingDb;
    e->setCompressor(p);
}

JNIEXPORT void JNICALL
Java_com_example_wakeupmusic_audio_NativeBridge_nativeSetEq(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                                            jintArray types, jfloatArray freqs,
                                                            jfloatArray qs, jfloatArray gains) {
    AudioEngine* e = engineOf(handle);
    if (e == nullptr || types == nullptr || freqs == nullptr || qs == nullptr || gains == nullptr) return;

    const jsize n = std::min({env->GetArrayLength(types), env->GetArrayLength(freqs),
                              env->GetArrayLength(qs), env->GetArrayLength(gains),
                              static_cast<jsize>(futura::kMaxEqBands)});

    jint t[futura::kMaxEqBands];
    jfloat f[futura::kMaxEqBands];
    jfloat q[futura::kMaxEqBands];
    jfloat g[futura::kMaxEqBands];
    env->GetIntArrayRegion(types, 0, n, t);
    env->GetFloatArrayRegion(freqs, 0, n, f);
    env->GetFloatArrayRegion(qs, 0, n, q);
    env->GetFloatArrayRegion(gains, 0, n, g);

    EqBand bands[futura::kMaxEqBands];
    for (jsize i = 0; i < n; ++i) {
        bands[i].type = static_cast<EqType>(t[i]);
        bands[i].freqHz = f[i];
        bands[i].q = q[i];
        bands[i].gainDb = g[i];
    }
    e->setEq(bands, static_cast<int32_t>(n));
}

JNIEXPORT jboolean JNICALL
Java_com_example_wakeupmusic_audio_NativeBridge_nativeIsPlaying(JNIEnv* /*env*/, jobject /*thiz*/,
                                                                jlong handle) {
    AudioEngine* e = engineOf(handle);
    return (e != nullptr && e->isPlaying()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_example_wakeupmusic_audio_NativeBridge_nativeSampleRate(JNIEnv* /*env*/, jobject /*thiz*/,
                                                                 jlong handle) {
    AudioEngine* e = engineOf(handle);
    return e != nullptr ? static_cast<jint>(e->sampleRate()) : 0;
}

}  // extern "C"
