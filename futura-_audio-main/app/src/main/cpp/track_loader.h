// Decodes an audio file into the engine's internal PCM format.
#pragma once

#include <cstdint>

#include "audio_types.h"

namespace futura {

// Decodes the audio stream found at [offset, offset + length) of `fd`.
// WAV (PCM 8/16/24/32-bit, IEEE float) is parsed directly. Everything else
// (MP3, AAC/ADTS, FLAC, OGG Vorbis, and any other container the platform
// extractor understands) goes through NDK MediaExtractor and MediaCodec.
// The output is interleaved stereo float, resampled to `dstRate`.
// `length` < 0 means "to end of file". Does not take ownership of `fd`.
ErrorCode decodeTrack(int fd, int64_t offset, int64_t length, uint32_t dstRate, PcmBuffer& out);

}  // namespace futura
