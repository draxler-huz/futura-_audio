// Decoder interface for the Stage 1 player (futura::player).
//
// One implementation per container or codec family. Every implementation runs on the DECODE thread (or on
// the controller thread while probing). Nothing here is ever called from the audio callback, so decoders may
// allocate, use std::vector, take locks and read files.
//
// Contract, shared by all decoders:
//   - open() takes a file descriptor and an [offset, offset + length) range. The decoder duplicates the
//     descriptor (dup) and never closes the caller's one. length < 0 means "to end of file".
//   - Output is always interleaved STEREO float in [-1, 1] at the decoder's NATIVE sample rate
//     (TrackInfo::sampleRate). Mono sources are duplicated to both channels. More than two channels is
//     rejected with kUnsupportedFormat in open().
//   - Raw frames are the decoder's own timeline, including encoder delay and padding. The engine applies
//     TrackInfo::skipFrames and trimEndFrames (see gapless.h). Decoders therefore never trim themselves,
//     EXCEPT that they must never return more than TrackInfo::totalFrames raw frames when totalFrames >= 0.
//   - seekFrame() uses the same raw timeline. Seeking may be slow (decode and discard) where the codec
//     has no cheap seek. Each implementation documents which.
//   - Errors: a negative ErrorCode. decodeFrames() returns the frames produced (0 means end of stream) or a
//     negative ErrorCode for a decode error. After an error the decoder may be reused only via seekFrame().
//
// Pinned third-party code (see STATUS.md for exact commits and licences):
//   WAV   own parser (no dependency)
//   FLAC  dr_libs dr_flac.h
//   MP3   minimp3.h (plus our own LAME/Xing gapless header parser)
//   Vorbis stb_vorbis.c
//   Opus  libopus (plus our own Ogg demuxer and OpusHead parser)
//   AAC   Android MediaExtractor + MediaCodec (NDK)
//   ALAC  Apple open-source ALAC decoder fed by MediaExtractor packets
//   DSD   not in Stage 1 (moved to Stage 3, see STATUS.md)
#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>

#include "../audio_types.h"

namespace futura::player {

enum class Codec : int32_t {
    kUnknown = 0,
    kWav = 1,
    kFlac = 2,
    kMp3 = 3,
    kAac = 4,
    kAlac = 5,
    kVorbis = 6,
    kOpus = 7,
};

// Human-readable codec name for the UI and the format self-test. Never null.
const char* codecName(Codec codec);

// Facts about an opened track. Filled by Decoder::open(), read by the engine and by the UI.
struct TrackInfo {
    Codec codec = Codec::kUnknown;
    uint32_t sampleRate = 0;   // native rate of the decoder output (Opus: always 48000)
    int32_t channels = 0;      // channels in the SOURCE (1 or 2). Output is always stereo.
    int32_t bitDepth = 0;      // source bit depth for PCM and lossless codecs, 0 for lossy codecs
    bool lossless = false;
    // Raw frames in the file's own timeline, or -1 when unknown (for example a streamed MP3 with no
    // length information). Bounds what decodeFrames() may return.
    int64_t totalFrames = -1;
    // Gapless trimming, in raw frames at sampleRate. Zero when the file says nothing about it.
    int64_t skipFrames = 0;     // encoder delay plus decoder delay (MP3), pre-skip (Opus), priming (AAC)
    int64_t trimEndFrames = 0;  // encoder padding at the end (MP3, AAC). Ignored when totalFrames < 0.
};

class Decoder {
public:
    virtual ~Decoder() = default;

    // Opens the stream. Fills `info`. Probes headers only; must not decode the whole file.
    virtual ErrorCode open(int fd, int64_t offset, int64_t length, TrackInfo& info) = 0;

    // Decodes up to `frames` raw stereo frames into `out` (2 * frames floats, interleaved).
    // Returns the number of frames written (0 = end of stream) or a negative ErrorCode.
    virtual int64_t decodeFrames(float* out, int64_t frames) = 0;

    // Moves the read position to raw frame `frame` (clamped to [0, totalFrames] when known).
    virtual ErrorCode seekFrame(int64_t frame) = 0;
};

// Opens the right decoder for the bytes at [offset, offset + length) of `fd`, by sniffing magic bytes.
// On success `out` holds an opened decoder and `info` is filled. On failure `out` is null.
// Implemented in decoder_factory.cpp.
ErrorCode createDecoder(int fd, int64_t offset, int64_t length, std::unique_ptr<Decoder>& out, TrackInfo& info);

}  // namespace futura::player
