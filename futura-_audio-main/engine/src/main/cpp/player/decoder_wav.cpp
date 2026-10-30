// Streaming WAV decoder: PCM 8/16/24/32-bit integer and IEEE float 32-bit, mono or stereo.
// Handles WAVE_FORMAT_EXTENSIBLE. No gapless trimming (WAV has no encoder delay).
#include <cstring>
#include <vector>

#include "decoder_common.h"

namespace futura::player {
namespace {

constexpr uint16_t kFormatPcm = 1;
constexpr uint16_t kFormatFloat = 3;
constexpr uint16_t kFormatExtensible = 0xFFFE;

class WavDecoder : public Decoder {
public:
    ErrorCode open(int fd, int64_t offset, int64_t length, TrackInfo& info) override {
        ErrorCode ec = reader_.open(fd, offset, length);
        if (ec != kOk) return ec;

        uint8_t riff[12];
        if (reader_.readAt(0, riff, 12) != 12) return kUnsupportedFormat;
        if (std::memcmp(riff, "RIFF", 4) != 0 || std::memcmp(riff + 8, "WAVE", 4) != 0) return kUnsupportedFormat;

        bool haveFmt = false;
        uint16_t formatTag = 0;
        uint16_t bitsPerSample = 0;
        int64_t pos = 12;
        const int64_t fileSize = reader_.size();

        while (pos + 8 <= fileSize) {
            uint8_t chunk[8];
            if (reader_.readAt(pos, chunk, 8) != 8) return kIoError;
            const uint32_t chunkSize = readLe32(chunk + 4);
            const int64_t body = pos + 8;

            if (std::memcmp(chunk, "fmt ", 4) == 0) {
                if (chunkSize < 16 || body + 16 > fileSize) return kUnsupportedFormat;
                uint8_t fmt[40] = {};
                const int64_t want = chunkSize < sizeof fmt ? chunkSize : sizeof fmt;
                if (reader_.readAt(body, fmt, want) != want) return kIoError;

                formatTag = readLe16(fmt);
                channels_ = readLe16(fmt + 2);
                sampleRate_ = readLe32(fmt + 4);
                blockAlign_ = readLe16(fmt + 12);
                bitsPerSample = readLe16(fmt + 14);

                if (formatTag == kFormatExtensible) {
                    if (chunkSize < 26) return kUnsupportedFormat;
                    // The first two bytes of the sub-format GUID are the real format tag.
                    formatTag = readLe16(fmt + 24);
                }
                haveFmt = true;
            } else if (std::memcmp(chunk, "data", 4) == 0) {
                if (!haveFmt) return kUnsupportedFormat;  // data before fmt: not a layout we accept
                dataOffset_ = body;
                int64_t dataSize = chunkSize;
                // 0 and 0xFFFFFFFF mean "use the rest of the file" (streamed or truncated headers).
                if (chunkSize == 0 || chunkSize == 0xFFFFFFFFu || body + dataSize > fileSize) {
                    dataSize = fileSize - body;
                }
                return finishOpen(formatTag, bitsPerSample, dataSize, info);
            }

            pos = body + static_cast<int64_t>(chunkSize) + (chunkSize & 1);  // chunks are word aligned
        }
        return kUnsupportedFormat;  // no data chunk
    }

    int64_t decodeFrames(float* out, int64_t frames) override {
        if (frames <= 0) return 0;
        int64_t remaining = totalFrames_ < 0 ? frames : totalFrames_ - cursor_;
        if (remaining <= 0) return 0;
        if (frames > remaining) frames = remaining;

        const int64_t bytesWanted = frames * blockAlign_;
        if (static_cast<int64_t>(buf_.size()) < bytesWanted) buf_.resize(static_cast<size_t>(bytesWanted));
        const int64_t got = reader_.readAt(dataOffset_ + cursor_ * blockAlign_, buf_.data(), bytesWanted);
        if (got < 0) return kIoError;

        const int64_t gotFrames = got / blockAlign_;
        for (int64_t f = 0; f < gotFrames; ++f) {
            const uint8_t* src = buf_.data() + f * blockAlign_;
            float left = convert(src);
            float right = channels_ == 2 ? convert(src + bytesPerSample_) : left;
            out[2 * f] = left;
            out[2 * f + 1] = right;
        }
        cursor_ += gotFrames;
        return gotFrames;
    }

    ErrorCode seekFrame(int64_t frame) override {
        if (frame < 0) frame = 0;
        if (totalFrames_ >= 0 && frame > totalFrames_) frame = totalFrames_;
        cursor_ = frame;
        return kOk;
    }

private:
    ErrorCode finishOpen(uint16_t formatTag, uint16_t bitsPerSample, int64_t dataSize, TrackInfo& info) {
        if (channels_ < 1 || channels_ > 2) return kUnsupportedFormat;
        if (sampleRate_ == 0) return kUnsupportedFormat;

        if (formatTag == kFormatPcm) {
            if (bitsPerSample != 8 && bitsPerSample != 16 && bitsPerSample != 24 && bitsPerSample != 32) {
                return kUnsupportedFormat;
            }
            kind_ = bitsPerSample == 8 ? Kind::kU8 : bitsPerSample == 16 ? Kind::kS16
                  : bitsPerSample == 24 ? Kind::kS24 : Kind::kS32;
        } else if (formatTag == kFormatFloat) {
            if (bitsPerSample != 32) return kUnsupportedFormat;
            kind_ = Kind::kF32;
        } else {
            return kUnsupportedFormat;
        }

        bytesPerSample_ = bitsPerSample / 8;
        if (blockAlign_ != bytesPerSample_ * channels_) blockAlign_ = static_cast<uint16_t>(bytesPerSample_ * channels_);
        totalFrames_ = dataSize / blockAlign_;

        info.codec = Codec::kWav;
        info.sampleRate = sampleRate_;
        info.channels = channels_;
        info.bitDepth = bitsPerSample;
        info.lossless = kind_ != Kind::kF32;  // float WAV is a lossless container of float samples
        info.totalFrames = totalFrames_;
        info.skipFrames = 0;
        info.trimEndFrames = 0;
        return kOk;
    }

    // Converts the sample starting at `p` to float in [-1, 1].
    float convert(const uint8_t* p) const {
        switch (kind_) {
            case Kind::kU8:
                return (static_cast<float>(p[0]) - 128.0f) / 128.0f;
            case Kind::kS16:
                return static_cast<float>(static_cast<int16_t>(readLe16(p))) / 32768.0f;
            case Kind::kS24: {
                int32_t v = static_cast<int32_t>(p[0] | (p[1] << 8) | (p[2] << 16));
                if (v & 0x800000) v |= ~0xFFFFFF;  // sign-extend 24 to 32 bits
                return static_cast<float>(v) / 8388608.0f;
            }
            case Kind::kS32:
                return static_cast<float>(static_cast<int32_t>(readLe32(p))) / 2147483648.0f;
            case Kind::kF32: {
                float f;
                std::memcpy(&f, p, sizeof f);
                return f;
            }
        }
        return 0.0f;
    }

    enum class Kind { kU8, kS16, kS24, kS32, kF32 };

    FdReader reader_;
    Kind kind_ = Kind::kS16;
    uint16_t channels_ = 0;
    uint32_t sampleRate_ = 0;
    uint16_t blockAlign_ = 0;
    int64_t bytesPerSample_ = 0;
    int64_t dataOffset_ = 0;
    int64_t totalFrames_ = 0;
    int64_t cursor_ = 0;
    std::vector<uint8_t> buf_;
};

}  // namespace

std::unique_ptr<Decoder> makeWavDecoder() { return std::make_unique<WavDecoder>(); }

}  // namespace futura::player
