cmake_minimum_required(VERSION 3.22.1)
project(futura_audio LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# 16 KB page alignment for every shared library we link, including the ones built below.
# Prebuilt Oboe binaries from the Maven AAR are 4 KB aligned, which is why Oboe is built from source here.
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# Oboe is built from source at a pinned release tag.
include(FetchContent)
FetchContent_Declare(
    oboe
    GIT_REPOSITORY https://github.com/google/oboe.git
    GIT_TAG 1.8.0
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(oboe)

add_library(futura_audio SHARED
    audio_engine.cpp
    dsp.cpp
    gapless_loop.cpp
    track_loader.cpp
    jni_bridge.cpp)

target_compile_options(futura_audio PRIVATE -Wall -Wextra)

target_link_libraries(futura_audio PRIVATE
    oboe
    mediandk
    android
    log)
