# Stage 1 decoder family, as one static library: futura_decoders.
# Included by engine/src/main/cpp/CMakeLists.txt (Android) and by engine/host-tests/decoder/CMakeLists.txt (host).
#
# Third-party sources are pinned to exact commits or tags, and their licences are recorded in STATUS.md.
# They are fetched at configure time, like Oboe.
include(FetchContent)

# Header-only or single-file dependencies. Populated, not built, because they have no library target.
# NOTE: GIT_SHALLOW TRUE is intentionally omitted for commit-hash pins — shallow clones cannot resolve
# arbitrary commits, only branch/tag tips.
FetchContent_Declare(futura_minimp3
    GIT_REPOSITORY https://github.com/lieff/minimp3.git
    GIT_TAG ea99364f61c14656440e8d77e9c233ccf3124633)         # CC0-1.0
FetchContent_Declare(futura_drlibs
    GIT_REPOSITORY https://github.com/mackron/dr_libs.git
    GIT_TAG e84e28a98d0503b762053415419919623e9d6c96)         # Public domain or MIT-0 (choice)
FetchContent_Declare(futura_stb
    GIT_REPOSITORY https://github.com/nothings/stb.git
    GIT_TAG 003501f6d7a8668ce8b6d875e34dd97ad0d5d5b5)         # stb_vorbis.c: public domain or MIT (choice)
foreach(dep futura_minimp3 futura_drlibs futura_stb)
    FetchContent_GetProperties(${dep})
    if(NOT ${dep}_POPULATED)
        FetchContent_Populate(${dep})
    endif()
endforeach()

# libopus, built as a static library only.
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_DEEP_PLC OFF CACHE BOOL "" FORCE)
FetchContent_Declare(futura_opus
    GIT_REPOSITORY https://github.com/xiph/opus.git
    GIT_TAG v1.5.2                                            # BSD-3-Clause
    GIT_SHALLOW TRUE)                                         # tag pin — shallow is safe here
FetchContent_MakeAvailable(futura_opus)

# Apple's open-source ALAC.
FetchContent_Declare(futura_alac
    GIT_REPOSITORY https://github.com/macosforge/alac.git
    GIT_TAG c38887c5c5e64a4b31108733bd79ca9b2496d987)         # Apache-2.0
FetchContent_GetProperties(futura_alac)
if(NOT futura_alac_POPULATED)
    FetchContent_Populate(futura_alac)
endif()

set(FUTURA_DECODER_DIR ${CMAKE_CURRENT_LIST_DIR})
set(FUTURA_DECODER_SOURCES
    ${FUTURA_DECODER_DIR}/decoder_factory.cpp
    ${FUTURA_DECODER_DIR}/decoder_wav.cpp
    ${FUTURA_DECODER_DIR}/decoder_flac.cpp
    ${FUTURA_DECODER_DIR}/decoder_mp3.cpp
    ${FUTURA_DECODER_DIR}/decoder_vorbis.cpp
    ${FUTURA_DECODER_DIR}/ogg_demux.cpp
    ${FUTURA_DECODER_DIR}/decoder_opus.cpp)

if(ANDROID)
    list(APPEND FUTURA_DECODER_SOURCES
        ${FUTURA_DECODER_DIR}/decoder_mediacodec.cpp
        ${FUTURA_DECODER_DIR}/decoder_alac.cpp
        ${futura_alac_SOURCE_DIR}/codec/ALACBitUtilities.c
        ${futura_alac_SOURCE_DIR}/codec/EndianPortable.c
        ${futura_alac_SOURCE_DIR}/codec/ag_dec.c
        ${futura_alac_SOURCE_DIR}/codec/dp_dec.c
        ${futura_alac_SOURCE_DIR}/codec/matrix_dec.c
        ${futura_alac_SOURCE_DIR}/codec/ALACDecoder.cpp)
endif()

# stb_vorbis.c is compiled as its own C translation unit. decoder_vorbis.cpp includes it header-only.
add_library(futura_decoders STATIC
    ${FUTURA_DECODER_SOURCES}
    ${futura_stb_SOURCE_DIR}/stb_vorbis.c)

set_target_properties(futura_decoders PROPERTIES POSITION_INDEPENDENT_CODE ON)

target_include_directories(futura_decoders
    PUBLIC ${FUTURA_DECODER_DIR}
    PRIVATE
        ${futura_minimp3_SOURCE_DIR}
        ${futura_drlibs_SOURCE_DIR}
        ${futura_stb_SOURCE_DIR})

if(ANDROID)
    target_include_directories(futura_decoders SYSTEM PRIVATE ${futura_alac_SOURCE_DIR}/codec)
endif()

# Our code is held to -Wall -Wextra. Third-party code is included as SYSTEM headers, and stb_vorbis.c is
# compiled with -w because it is upstream code that we do not change.
target_compile_options(futura_decoders PRIVATE -Wall -Wextra)
set_source_files_properties(${futura_stb_SOURCE_DIR}/stb_vorbis.c PROPERTIES COMPILE_OPTIONS "-w")

target_link_libraries(futura_decoders PRIVATE opus)

if(ANDROID)
    target_link_libraries(futura_decoders PRIVATE mediandk)
endif()
