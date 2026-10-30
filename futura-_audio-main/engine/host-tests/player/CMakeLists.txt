# Host build of the Stage 1 playback engine tests (engine/host-tests/player/run.sh).
# Oboe is replaced by oboe/Oboe.h in this directory, which really runs the callback. The decoders are real.
cmake_minimum_required(VERSION 3.16)
project(futura_player_host_tests LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(PLAYER_CPP ${CMAKE_CURRENT_SOURCE_DIR}/../../src/main/cpp)
include(${PLAYER_CPP}/player/decoders.cmake)

add_executable(player_test
    player_test.cpp
    oboe/Oboe.cpp
    ${PLAYER_CPP}/player/player_engine.cpp
    ${PLAYER_CPP}/player/resampler.cpp)
target_include_directories(player_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${PLAYER_CPP})
target_compile_options(player_test PRIVATE -Wall -Wextra)
target_link_libraries(player_test PRIVATE futura_decoders Threads::Threads)
find_package(Threads REQUIRED)
