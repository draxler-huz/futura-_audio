// Shared helpers and internal factory declarations for the decoder family (futura::player).
// Not part of the engine's public surface. Only decoder_*.cpp and the decoder tests include this.
#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

#include "../audio_types.h"
#include "decoder.h"

namespace futura::player {

// Read-only, bounded view of [offset, offset + length) of a file descriptor.
// open() dup()s the descriptor, so the reader owns its own copy. The caller keeps ownership of `fd`.
// Reads use pread(), so there is no shared file position and no lock is needed.
class FdReader {
public:
    FdReader() = default;
    ~FdReader();
    FdReader(const FdReader&) = delete;
    FdReader& operator=(const FdReader&) = delete;

    // length < 0 means "to end of file". Returns kOk, or kIoError if the descriptor cannot be read.
    ErrorCode open(int fd, int64_t offset, int64_t length);

    // Bytes in the window.
    int64_t size() const { return size_; }
    bool isOpen() const { return fd_ >= 0; }

    // Reads up to `n` bytes starting at window offset `pos`. Returns bytes read (0 at or past the end),
    // or -1 on an I/O error.
    int64_t readAt(int64_t pos, void* dst, int64_t n) const;

private:
    int fd_ = -1;
    int64_t base_ = 0;
    int64_t size_ = 0;
};

inline uint16_t readLe16(const uint8_t* p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }
inline uint32_t readLe32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}
inline uint32_t readBe32(const uint8_t* p) {
    return (static_cast<uint32_t>(p[0]) << 24) | (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) | static_cast<uint32_t>(p[3]);
}

// Concrete decoder constructors. Each is defined in its own decoder_*.cpp.
std::unique_ptr<Decoder> makeWavDecoder();
std::unique_ptr<Decoder> makeFlacDecoder();
std::unique_ptr<Decoder> makeMp3Decoder();
std::unique_ptr<Decoder> makeVorbisDecoder();
std::unique_ptr<Decoder> makeOpusDecoder();

// Android only. Defined in decoder_mediacodec.cpp. Chooses AAC (MediaCodec) or ALAC (Apple) from the track MIME.
#if defined(__ANDROID__)
ErrorCode makeMp4Decoder(int fd, int64_t offset, int64_t length, std::unique_ptr<Decoder>& out, TrackInfo& info);
#endif

// Apple open-source ALAC frame decoder, wrapped for one packet at a time. Android only (decoder_alac.cpp).
// Output is always stereo float. Mono is duplicated. Only 16, 24 and 32 bit ALAC is accepted.
#if defined(__ANDROID__)
class AppleAlacCodec {
public:
    AppleAlacCodec();
    ~AppleAlacCodec();
    AppleAlacCodec(const AppleAlacCodec&) = delete;
    AppleAlacCodec& operator=(const AppleAlacCodec&) = delete;

    // `cookie` is the ALAC magic cookie (the 'alac' atom payload, from the track's "csd-0" buffer).
    ErrorCode init(const uint8_t* cookie, size_t size);

    uint32_t sampleRate() const;
    int32_t channels() const;
    int32_t bitDepth() const;
    uint32_t framesPerPacket() const;

    // Decodes one ALAC packet, appending interleaved stereo float frames to `out`.
    // Returns the number of frames appended, or a negative ErrorCode.
    int64_t decodePacket(const uint8_t* packet, size_t size, std::vector<float>& out);

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
#endif

}  // namespace futura::player
