cmake_minimum_required(VERSION 3.22.1)
project(futura_audio LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Oboe is provided as a prefab package by the Gradle dependency com.google.oboe:oboe.
find_package(oboe REQUIRED CONFIG)

add_library(futura_audio SHARED
    audio_engine.cpp
    dsp.cpp
    gapless_loop.cpp
    track_loader.cpp
    jni_bridge.cpp)

target_compile_options(futura_audio PRIVATE -Wall -Wextra)

# 16 KB page alignment for Android 15+ devices. NDK r28 already defaults to this;
# the flag makes it explicit so ci/guards/alignment_guard.sh always passes.
target_link_options(futura_audio PRIVATE -Wl,-z,max-page-size=16384)

target_link_libraries(futura_audio PRIVATE
    oboe::oboe
    mediandk
    android
    log)
