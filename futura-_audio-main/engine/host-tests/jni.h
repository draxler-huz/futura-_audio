// Host-only JNI stand-in: just enough types for the bridge files to compile.
#pragma once
#include <cstdint>
typedef int32_t jint; typedef int64_t jlong; typedef uint8_t jboolean; typedef float jfloat;
typedef void* jobject; typedef struct _jintArray* jintArray; typedef int32_t jsize;
#define JNI_TRUE 1
#define JNI_FALSE 0
#define JNIEXPORT
#define JNICALL
struct JNIEnv {
    jintArray NewIntArray(jsize) { return nullptr; }
    void SetIntArrayRegion(jintArray, jsize, jsize, const jint*) {}
};
