// Shared types and constants for the Futura Audio native engine.
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace futura {

// Status codes returned to Kotlin. Keep in sync with NativeAudioEngine.kt.
enum ErrorCode : int32_t {
    kOk = 0,
    kIoError = -1,
    kUnsupportedFormat = -2,
    kDecodeError = -3,
    kTrackTooLong = -4,
    kEmptyTrack = -5,
    kOutputError = -6,
    kInvalidState = -7,
};

// Internal layout for every track and for the output stream:
// interleaved stereo float, nominally in [-1, 1].
constexpr int32_t kChannels = 2;

// Tracks are fully pre-loaded into memory, so this bounds the footprint
// (about 384 KB per second of audio at 48 kHz, stereo float).
constexpr double kMaxPreloadSeconds = 300.0;

// Length of the equal-power crossfade used at the loop seam.
constexpr float kLoopSeamSeconds = 0.010f;

constexpr int32_t kMaxEqBands = 8;

enum class VolumeCurve : int32_t { kLinear = 0, kLogarithmic = 1 };

enum class EqType : int32_t { kPeaking = 0, kLowShelf = 1, kHighShelf = 2 };

// A fully decoded track, resampled to the output stream's rate.
struct PcmBuffer {
    std::vector<float> samples;  // interleaved stereo
    uint32_t sampleRate = 0;

    size_t frames() const { return samples.size() / kChannels; }
};

}  // namespace futura
