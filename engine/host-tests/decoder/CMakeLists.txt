# Host build of the Stage 1 decoder family, for engine/host-tests/decoder/run.sh.
cmake_minimum_required(VERSION 3.16)
project(futura_decoder_host_tests LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(${CMAKE_CURRENT_SOURCE_DIR}/../../src/main/cpp/player/decoders.cmake)

add_executable(decoder_test test_decoders.cpp)
target_link_libraries(decoder_test PRIVATE futura_decoders)
target_compile_options(decoder_test PRIVATE -Wall -Wextra -Werror)

# ALAC round trip: Apple's encoder and decoder, with decoder_alac.cpp (Android-guarded) compiled with __ANDROID__
# defined, so the wrapper can run on the host. The NDK MediaExtractor path is not part of this test.
set(ALAC_DIR ${futura_alac_SOURCE_DIR}/codec)
add_executable(alac_roundtrip
    alac_roundtrip.cpp
    ${FUTURA_DECODER_DIR}/decoder_alac.cpp
    ${ALAC_DIR}/ALACEncoder.cpp
    ${ALAC_DIR}/ALACDecoder.cpp
    ${ALAC_DIR}/ALACBitUtilities.c
    ${ALAC_DIR}/EndianPortable.c
    ${ALAC_DIR}/ag_enc.c
    ${ALAC_DIR}/ag_dec.c
    ${ALAC_DIR}/dp_enc.c
    ${ALAC_DIR}/dp_dec.c
    ${ALAC_DIR}/matrix_enc.c
    ${ALAC_DIR}/matrix_dec.c)
target_include_directories(alac_roundtrip SYSTEM PRIVATE ${ALAC_DIR})
target_include_directories(alac_roundtrip PRIVATE ${FUTURA_DECODER_DIR})
target_compile_definitions(alac_roundtrip PRIVATE __ANDROID__)
set_source_files_properties(${FUTURA_DECODER_DIR}/decoder_alac.cpp PROPERTIES COMPILE_DEFINITIONS __ANDROID__)
target_link_libraries(alac_roundtrip PRIVATE futura_decoders)
