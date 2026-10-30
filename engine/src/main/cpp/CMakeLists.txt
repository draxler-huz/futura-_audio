cmake_minimum_required(VERSION 3.22)
project("engine-lib")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Oboe integration
find_package(oboe CONFIG REQUIRED)

# Android hardware audio
include_directories(
    ${{ANDROID_NDK}/sources/third_party/oboe
    ${{ANDROID_NDK}}/sources/android/sound/openhardware
)

# Thread-safe ring buffer source
file(GLOB engine_src
    "${CMAKE_CURRENT_SOURCE_DIR}/*.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/*.h"
)

add_library(engine-lib
    SHARED
    ${engine_src}
)

target_include_directories(engine-lib PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    ${{ANDROID_NDK}}/sources/third_party/oboe
)

target_link_libraries(engine-lib
    oboe
    android
    m
    log
)

set_target_properties(engine-lib PROPERTIES
    CXX_STANDARD 17
    ANDROID_PLATFORM android-21
)

set_output_properties(engine-lib
    LIBRARY_OUTPUT_DIRECTORY
    ${CMAKE_ANDROID_OUT_LIB}
)