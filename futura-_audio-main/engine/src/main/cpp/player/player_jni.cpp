// JNI entry points for com.example.wakeupmusic.engine.PlayerBridge (Kotlin side: PlayerEngine.kt).
// The Kotlin handle is a raw pointer to a PlayerEngine. Kotlin owns it and must call nativeDestroy once.
// Every file descriptor passed in is owned by native code and closed there.
#include <jni.h>
#include <unistd.h>

#include <string>

#include "player_engine.h"

using futura::player::PlayerEngine;
using futura::player::PlayerStats;

namespace {

PlayerEngine* engineOf(jlong handle) { return reinterpret_cast<PlayerEngine*>(handle); }

jstring toJString(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_wakeupmusic_engine_PlayerBridge_nativeCreate(JNIEnv* /*env*/, jobject /*thiz*/) {
    return reinterpret_cast<jlong>(new PlayerEngine());
}

JNIEXPORT void JNICALL
Java_com_example_wakeupmusic_engine_PlayerBridge_nativeDestroy(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    delete engineOf(handle);
}

JNIEXPORT jint JNICALL
Java_com_example_wakeupmusic_engine_PlayerBridge_nativeOpen(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    PlayerEngine* e = engineOf(handle);
    if (e == nullptr) return futura::kInvalidState;
    return e->open();
}

JNIEXPORT void JNICALL
Java_com_example_wakeupmusic_engine_PlayerBridge_nativeClose(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (PlayerEngine* e = engineOf(handle)) e->close();
}

JNIEXPORT jint JNICALL
Java_com_example_wakeupmusic_engine_PlayerBridge_nativeLoadCurrent(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle,
                                                                   jint fd, jlong offset, jlong length,
                                                                   jlong startMs) {
    PlayerEngine* e = engineOf(handle);
    if (e == nullptr) {
        ::close(fd);
        return futura::kInvalidState;
    }
    return e->loadCurrent(static_cast<int>(fd), static_cast<int64_t>(offset), static_cast<int64_t>(length),
                          static_cast<int64_t>(startMs));
}

JNIEXPORT jint JNICALL
Java_com_example_wakeupmusic_engine_PlayerBridge_nativeLoadNext(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle,
                                                                jint fd, jlong offset, jlong length) {
    PlayerEngine* e = engineOf(handle);
    if (e == nullptr) {
        ::close(fd);
        return futura::kInvalidState;
    }
    return e->loadNext(static_cast<int>(fd), static_cast<int64_t>(offset), static_cast<int64_t>(length));
}

JNIEXPORT void JNICALL
Java_com_example_wakeupmusic_engine_PlayerBridge_nativeClearNext(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (PlayerEngine* e = engineOf(handle)) e->clearNext();
}

JNIEXPORT jint JNICALL
Java_com_example_wakeupmusic_engine_PlayerBridge_nativeStart(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    PlayerEngine* e = engineOf(handle);
    if (e == nullptr) return futura::kInvalidState;
    return e->start();
}

JNIEXPORT void JNICALL
Java_com_example_wakeupmusic_engine_PlayerBridge_nativePause(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (PlayerEngine* e = engineOf(handle)) e->pause();
}

JNIEXPORT void JNICALL
Java_com_example_wakeupmusic_engine_PlayerBridge_nativeResume(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (PlayerEngine* e = engineOf(handle)) e->resume();
}

JNIEXPORT void JNICALL
Java_com_example_wakeupmusic_engine_PlayerBridge_nativeStop(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (PlayerEngine* e = engineOf(handle)) e->stop();
}

JNIEXPORT jint JNICALL
Java_com_example_wakeupmusic_engine_PlayerBridge_nativeSeekMs(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle,
                                                              jlong ms) {
    PlayerEngine* e = engineOf(handle);
    if (e == nullptr) return futura::kInvalidState;
    return e->seekMs(static_cast<int64_t>(ms));
}

JNIEXPORT void JNICALL
Java_com_example_wakeupmusic_engine_PlayerBridge_nativeSetVolume(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle,
                                                                 jfloat target, jint rampMs) {
    if (PlayerEngine* e = engineOf(handle)) e->setVolume(static_cast<float>(target), static_cast<int32_t>(rampMs));
}

JNIEXPORT jboolean JNICALL
Java_com_example_wakeupmusic_engine_PlayerBridge_nativeIsPlaying(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    PlayerEngine* e = engineOf(handle);
    return (e != nullptr && e->isPlaying()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_wakeupmusic_engine_PlayerBridge_nativeEnded(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    PlayerEngine* e = engineOf(handle);
    return (e != nullptr && e->isEnded()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_example_wakeupmusic_engine_PlayerBridge_nativePositionMs(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    PlayerEngine* e = engineOf(handle);
    return e == nullptr ? 0 : static_cast<jlong>(e->positionMs());
}

JNIEXPORT jlong JNICALL
Java_com_example_wakeupmusic_engine_PlayerBridge_nativeDurationMs(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    PlayerEngine* e = engineOf(handle);
    return e == nullptr ? -1 : static_cast<jlong>(e->durationMs());
}

JNIEXPORT jint JNICALL
Java_com_example_wakeupmusic_engine_PlayerBridge_nativeSwitchCount(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    PlayerEngine* e = engineOf(handle);
    return e == nullptr ? 0 : static_cast<jint>(e->switchCount());
}

// Order must match EngineStats in PlayerEngine.kt (FIELD_COUNT = 11).
JNIEXPORT jlongArray JNICALL
Java_com_example_wakeupmusic_engine_PlayerBridge_nativeStats(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    PlayerEngine* e = engineOf(handle);
    if (e == nullptr) return nullptr;
    const PlayerStats s = e->stats();
    const jlong values[11] = {
        s.xruns,
        s.bufferSizeFrames,
        s.framesPerBurst,
        s.outputSampleRate,
        s.callbackNsLast / 1000,
        s.callbackNsAvg / 1000,
        s.callbackNsMax / 1000,
        s.underrunFrames,
        s.callbackCount,
        s.decodeErrors,
        s.audioApi,
    };
    jlongArray out = env->NewLongArray(11);
    if (out != nullptr) env->SetLongArrayRegion(out, 0, 11, values);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_example_wakeupmusic_engine_PlayerBridge_nativeTrackInfo(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    PlayerEngine* e = engineOf(handle);
    if (e == nullptr) return nullptr;
    return toJString(env, futura::player::trackInfoToKeyValues(e->currentTrackInfo()));
}

JNIEXPORT jstring JNICALL
Java_com_example_wakeupmusic_engine_PlayerBridge_nativeProbe(JNIEnv* env, jobject /*thiz*/, jint fd, jlong offset,
                                                             jlong length, jint maxSeconds) {
    return toJString(env, futura::player::probeToKeyValues(static_cast<int>(fd), static_cast<int64_t>(offset),
                                                           static_cast<int64_t>(length),
                                                           static_cast<int32_t>(maxSeconds)));
}

}  // extern "C"
