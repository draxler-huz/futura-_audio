// Opus decoder on libopus (BSD-3-Clause, pinned v1.5.2; see STATUS.md), with our own Ogg reader.
//
// Gapless (RFC 7845). The raw timeline starts at sample 0 and includes the pre-skip.
//   totalFrames  = granule position of the last page (raw frames, at 48 kHz)
//   skipFrames   = pre-skip from the OpusHead packet
//   trimEndFrames = 0 (the granule already ends the stream at the right sample)
// The OpusHead output gain is applied here, as ffmpeg does.
// Mapping family 0 (and family 1 with one or two channels) is supported. More channels are rejected.
// Seeking decodes and discards from the start, which is slow for long files.
#include <opus.h>

#include <cmath>
#include <cstring>
#include <memory>
#include <vector>

#include "decoder_common.h"
#include "ogg_demux.h"

namespace futura::player {
namespace {

constexpr uint32_t kOpusRate = 48000;
constexpr int kMaxFramesPerPacket = 5760;  // 120 ms at 48 kHz, the largest Opus packet

class OpusStreamDecoder : public Decoder {
public:
    ~OpusStreamDecoder() override {
        if (dec_) opus_decoder_destroy(dec_);
    }

    ErrorCode open(int fd, int64_t offset, int64_t length, TrackInfo& info) override {
        ErrorCode ec = reader_.open(fd, offset, length);
        if (ec != kOk) return ec;
        ogg_ = std::make_unique<OggPacketReader>(reader_);

        std::vector<uint8_t> head;
        int64_t granule = -1;
        if (ogg_->nextPacket(head, granule) != 1) return kUnsupportedFormat;
        if (head.size() < 19 || std::memcmp(head.data(), "OpusHead", 8) != 0) return kUnsupportedFormat;

        const uint8_t version = head[8];
        if ((version & 0xF0) != 0) return kUnsupportedFormat;  // major version other than 0
        channels_ = head[9];
        preSkip_ = readLe16(head.data() + 10);
        const int16_t outputGainQ8 = static_cast<int16_t>(readLe16(head.data() + 16));
        const uint8_t mappingFamily = head[18];
        if (channels_ < 1 || channels_ > 2) return kUnsupportedFormat;  // multistream layouts are not in Stage 1
        (void)mappingFamily;  // family 0 and family 1 with 1-2 channels both decode as a single stream here

        // The second packet is OpusTags (comments). Its content is not used, but it must be consumed.
        std::vector<uint8_t> tags;
        if (ogg_->nextPacket(tags, granule) != 1) return kUnsupportedFormat;

        int err = OPUS_OK;
        dec_ = opus_decoder_create(static_cast<opus_int32>(kOpusRate), channels_, &err);
        if (err != OPUS_OK || !dec_) return kUnsupportedFormat;

        gain_ = static_cast<float>(std::pow(10.0, static_cast<double>(outputGainQ8) / (20.0 * 256.0)));
        pcm_.resize(static_cast<size_t>(kMaxFramesPerPacket * channels_));

        const int64_t last = ogg_->lastGranule();
        totalFrames_ = last >= 0 ? last : -1;

        info.codec = Codec::kOpus;
        info.sampleRate = kOpusRate;
        info.channels = channels_;
        info.bitDepth = 0;
        info.lossless = false;
        info.totalFrames = totalFrames_;
        info.skipFrames = preSkip_;
        info.trimEndFrames = 0;
        return kOk;
    }

    int64_t decodeFrames(float* out, int64_t frames) override {
        if (!dec_ || frames <= 0) return 0;
        const int64_t limit = totalFrames_ >= 0 ? totalFrames_ : INT64_MAX;

        int64_t done = 0;
        while (done < frames && rawPos_ < limit) {
            if (pcmPos_ >= pcmCount_) {
                const int r = nextPacketDecoded();
                if (r < 0) return done > 0 ? done : r;
                if (r == 0) break;
            }
            int64_t n = pcmCount_ - pcmPos_;
            if (n > frames - done) n = frames - done;
            if (n > limit - rawPos_) n = limit - rawPos_;

            for (int64_t i = 0; i < n; ++i) {
                const size_t src = static_cast<size_t>((pcmPos_ + i) * channels_);
                out[2 * (done + i)] = pcm_[src];
                out[2 * (done + i) + 1] = channels_ == 2 ? pcm_[src + 1] : pcm_[src];
            }
            pcmPos_ += n;
            done += n;
            rawPos_ += n;
        }
        return done;
    }

    ErrorCode seekFrame(int64_t frame) override {
        if (!dec_ || !ogg_) return kInvalidState;
        if (frame < 0) frame = 0;
        if (totalFrames_ >= 0 && frame > totalFrames_) frame = totalFrames_;

        // Rewind to the first audio packet, with a clean decoder state.
        ogg_->rewind();
        std::vector<uint8_t> scratch;
        int64_t granule = -1;
        if (ogg_->nextPacket(scratch, granule) != 1) return kDecodeError;  // OpusHead
        if (ogg_->nextPacket(scratch, granule) != 1) return kDecodeError;  // OpusTags
        opus_decoder_ctl(dec_, OPUS_RESET_STATE);
        pcmCount_ = pcmPos_ = 0;
        rawPos_ = 0;

        // Slow path: decode and discard until the target.
        while (rawPos_ < frame) {
            if (pcmPos_ >= pcmCount_) {
                const int r = nextPacketDecoded();
                if (r <= 0) break;
            }
            int64_t n = pcmCount_ - pcmPos_;
            if (n > frame - rawPos_) n = frame - rawPos_;
            pcmPos_ += n;
            rawPos_ += n;
        }
        return kOk;
    }

private:
    // Decodes the next audio packet into pcm_. Returns 1 when samples were produced, 0 at end, or an error.
    int nextPacketDecoded() {
        std::vector<uint8_t> packet;
        int64_t granule = -1;
        const int r = ogg_->nextPacket(packet, granule);
        if (r <= 0) return r == 0 ? 0 : static_cast<int>(kDecodeError);

        const int n = opus_decode_float(dec_, packet.data(), static_cast<opus_int32>(packet.size()), pcm_.data(),
                                        kMaxFramesPerPacket, 0);
        if (n < 0) return static_cast<int>(kDecodeError);
        if (gain_ != 1.0f) {
            const size_t total = static_cast<size_t>(n) * static_cast<size_t>(channels_);
            for (size_t i = 0; i < total; ++i) pcm_[i] *= gain_;
        }
        pcmCount_ = n;
        pcmPos_ = 0;
        return 1;
    }

    FdReader reader_;
    std::unique_ptr<OggPacketReader> ogg_;
    OpusDecoder* dec_ = nullptr;
    int32_t channels_ = 0;
    uint16_t preSkip_ = 0;
    float gain_ = 1.0f;
    int64_t totalFrames_ = -1;
    int64_t rawPos_ = 0;
    std::vector<float> pcm_;
    int64_t pcmCount_ = 0;
    int64_t pcmPos_ = 0;
};

}  // namespace

std::unique_ptr<Decoder> makeOpusDecoder() { return std::make_unique<OpusStreamDecoder>(); }

}  // namespace futura::player
