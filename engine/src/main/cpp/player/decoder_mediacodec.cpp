// MP4 / M4A decoder on the NDK media stack. Android only.
//   audio/mp4a-latm (AAC)  -> AMediaCodec (platform AAC decoder)
//   audio/alac             -> AMediaExtractor packets, decoded by AppleAlacCodec (decoder_alac.cpp)
// Gapless: "encoder-delay" and "encoder-padding" from the track format become skipFrames and trimEndFrames.
// UNVERIFIED ON DEVICE: this file cannot be compiled or run on the host (no NDK here). It is written against the
// public NDK media API (NdkMediaExtractor.h, NdkMediaCodec.h, NdkMediaFormat.h) and must be checked on a device.
#if defined(__ANDROID__)

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>
#include <unistd.h>

#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "decoder_common.h"

namespace futura::player {
namespace {

constexpr int64_t kCodecTimeoutUs = 10000;
constexpr size_t kAlacPacketBytes = 256 * 1024;
constexpr int kMaxSpins = 2000;  // bound on codec round trips per refill, so a stalled codec cannot hang us

using ExtractorPtr = std::unique_ptr<AMediaExtractor, media_status_t (*)(AMediaExtractor*)>;
using CodecPtr = std::unique_ptr<AMediaCodec, media_status_t (*)(AMediaCodec*)>;
using FormatPtr = std::unique_ptr<AMediaFormat, media_status_t (*)(AMediaFormat*)>;

class Mp4Decoder : public Decoder {
public:
    ~Mp4Decoder() override {
        if (codec_) {
            AMediaCodec_stop(codec_.get());
        }
        codec_.reset();
        extractor_.reset();
        if (ownFd_ >= 0) ::close(ownFd_);
    }

    ErrorCode open(int fd, int64_t offset, int64_t length, TrackInfo& info) {
        ErrorCode ec = reader_.open(fd, offset, length);
        if (ec != kOk) return ec;

        // The extractor reads through its own descriptor, because the caller closes `fd` after we return.
        ownFd_ = ::dup(fd);
        if (ownFd_ < 0) return kIoError;

        extractor_ = ExtractorPtr(AMediaExtractor_new(), AMediaExtractor_delete);
        if (!extractor_) return kUnsupportedFormat;
        if (AMediaExtractor_setDataSourceFd(extractor_.get(), ownFd_, offset, reader_.size()) != AMEDIA_OK) {
            return kUnsupportedFormat;
        }

        const size_t trackCount = AMediaExtractor_getTrackCount(extractor_.get());
        size_t audioTrack = trackCount;
        FormatPtr format(nullptr, AMediaFormat_delete);
        std::string mime;
        for (size_t i = 0; i < trackCount && audioTrack == trackCount; ++i) {
            FormatPtr f(AMediaExtractor_getTrackFormat(extractor_.get(), i), AMediaFormat_delete);
            const char* m = nullptr;
            if (f && AMediaFormat_getString(f.get(), "mime", &m) && m && std::strncmp(m, "audio/", 6) == 0) {
                audioTrack = i;
                mime = m;
                format = std::move(f);
            }
        }
        if (audioTrack == trackCount || !format) return kUnsupportedFormat;
        if (AMediaExtractor_selectTrack(extractor_.get(), audioTrack) != AMEDIA_OK) return kUnsupportedFormat;

        int32_t rate = 0;
        int32_t channels = 0;
        AMediaFormat_getInt32(format.get(), "sample-rate", &rate);
        AMediaFormat_getInt32(format.get(), "channel-count", &channels);
        int64_t durationUs = 0;
        AMediaFormat_getInt64(format.get(), "durationUs", &durationUs);
        int32_t delay = 0;
        int32_t padding = 0;
        const int64_t skip = AMediaFormat_getInt32(format.get(), "encoder-delay", &delay) ? delay : 0;
        const int64_t trim = AMediaFormat_getInt32(format.get(), "encoder-padding", &padding) ? padding : 0;

        if (rate <= 0 || channels < 1 || channels > 2) return kUnsupportedFormat;
        sampleRate_ = static_cast<uint32_t>(rate);
        channels_ = channels;

        if (mime == "audio/mp4a-latm") {
            codec_ = CodecPtr(AMediaCodec_createDecoderByType("audio/mp4a-latm"), AMediaCodec_delete);
            if (!codec_) return kUnsupportedFormat;
            if (AMediaCodec_configure(codec_.get(), format.get(), nullptr, nullptr, 0) != AMEDIA_OK) {
                return kUnsupportedFormat;
            }
            if (AMediaCodec_start(codec_.get()) != AMEDIA_OK) return kUnsupportedFormat;
            mode_ = Mode::kAac;
            info.codec = Codec::kAac;
            info.lossless = false;
            info.bitDepth = 0;
            info.skipFrames = skip;
            info.trimEndFrames = trim;
        } else if (mime == "audio/alac") {
            void* csd = nullptr;
            size_t csdSize = 0;
            if (!AMediaFormat_getBuffer(format.get(), "csd-0", &csd, &csdSize) || !csd) return kUnsupportedFormat;
            if (alac_.init(static_cast<const uint8_t*>(csd), csdSize) != kOk) return kUnsupportedFormat;
            if (alac_.channels() != channels_ || alac_.sampleRate() != sampleRate_) {
                sampleRate_ = alac_.sampleRate();
                channels_ = alac_.channels();
            }
            packet_.resize(kAlacPacketBytes);
            mode_ = Mode::kAlac;
            info.codec = Codec::kAlac;
            info.lossless = true;
            info.bitDepth = alac_.bitDepth();
            info.skipFrames = skip;
            info.trimEndFrames = trim;
        } else {
            return kUnsupportedFormat;  // other audio MIME types in MP4 are not in Stage 1
        }

        info.sampleRate = sampleRate_;
        info.channels = channels_;
        // Duration is in the container's own timeline, which includes the priming and padding that are trimmed.
        info.totalFrames = durationUs > 0 ? durationUs * static_cast<int64_t>(sampleRate_) / 1000000 : -1;
        totalFrames_ = info.totalFrames;
        return kOk;
    }

    int64_t decodeFrames(float* out, int64_t frames) override {
        if (frames <= 0) return 0;
        const int64_t limit = totalFrames_ >= 0 ? totalFrames_ : INT64_MAX;

        int64_t done = 0;
        while (done < frames && rawPos_ < limit) {
            if (pendingPos_ >= pendingFrames()) {
                pending_.clear();
                pendingPos_ = 0;
                const int r = refill();
                if (r < 0) return done > 0 ? done : r;
                if (r == 0) break;
                continue;
            }
            int64_t n = pendingFrames() - pendingPos_;
            if (n > frames - done) n = frames - done;
            if (n > limit - rawPos_) n = limit - rawPos_;
            std::memcpy(out + 2 * done, pending_.data() + 2 * pendingPos_, static_cast<size_t>(n) * 2 * sizeof(float));
            pendingPos_ += n;
            done += n;
            rawPos_ += n;
        }
        return done;
    }

    ErrorCode seekFrame(int64_t frame) override {
        if (frame < 0) frame = 0;
        if (totalFrames_ >= 0 && frame > totalFrames_) frame = totalFrames_;

        const int64_t us = frame * 1000000 / static_cast<int64_t>(sampleRate_);
        if (AMediaExtractor_seekTo(extractor_.get(), us, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC) != AMEDIA_OK) {
            return kDecodeError;
        }
        if (codec_) AMediaCodec_flush(codec_.get());
        inputEos_ = false;
        outputEos_ = false;
        pending_.clear();
        pendingPos_ = 0;

        // The codec returns from the nearest sync sample. Decode and discard up to the requested frame.
        const int64_t syncFrame = AMediaExtractor_getSampleTime(extractor_.get()) * static_cast<int64_t>(sampleRate_) / 1000000;
        rawPos_ = syncFrame < frame ? syncFrame : frame;
        while (rawPos_ < frame) {
            if (pendingPos_ >= pendingFrames()) {
                pending_.clear();
                pendingPos_ = 0;
                if (refill() <= 0) break;
            }
            int64_t n = pendingFrames() - pendingPos_;
            if (n > frame - rawPos_) n = frame - rawPos_;
            pendingPos_ += n;
            rawPos_ += n;
        }
        return kOk;
    }

private:
    enum class Mode { kNone, kAac, kAlac };

    int64_t pendingFrames() const { return static_cast<int64_t>(pending_.size() / 2); }

    // Appends decoded stereo frames to pending_. Returns 1 when frames were added, 0 at end of stream, -1 on error.
    int refill() { return mode_ == Mode::kAlac ? refillAlac() : refillAac(); }

    int refillAlac() {
        for (;;) {
            const ssize_t n = AMediaExtractor_readSampleData(extractor_.get(), packet_.data(), packet_.size());
            if (n < 0) return 0;  // end of track
            const int64_t got = alac_.decodePacket(packet_.data(), static_cast<size_t>(n), pending_);
            AMediaExtractor_advance(extractor_.get());
            if (got < 0) return -1;
            if (got > 0) return 1;  // packets with no samples are skipped
        }
    }

    int refillAac() {
        for (int spins = 0; spins < kMaxSpins; ++spins) {
            if (!inputEos_) feedAac();

            AMediaCodecBufferInfo info{};
            const ssize_t idx = AMediaCodec_dequeueOutputBuffer(codec_.get(), &info, kCodecTimeoutUs);
            if (idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                readOutputFormat();
                continue;
            }
            if (idx < 0) continue;  // try again later, or buffers changed

            size_t size = 0;
            const uint8_t* buf = AMediaCodec_getOutputBuffer(codec_.get(), static_cast<size_t>(idx), &size);
            if (buf && info.size > 0) appendPcm(buf + info.offset, static_cast<size_t>(info.size));
            AMediaCodec_releaseOutputBuffer(codec_.get(), static_cast<size_t>(idx), false);

            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) outputEos_ = true;
            if (pendingFrames() > 0) return 1;
            if (outputEos_) return 0;
        }
        return -1;  // the codec produced nothing for too long
    }

    // Queues one compressed sample from the extractor, or the end-of-stream flag.
    void feedAac() {
        const ssize_t idx = AMediaCodec_dequeueInputBuffer(codec_.get(), kCodecTimeoutUs);
        if (idx < 0) return;
        size_t cap = 0;
        uint8_t* buf = AMediaCodec_getInputBuffer(codec_.get(), static_cast<size_t>(idx), &cap);
        if (!buf) return;
        const ssize_t n = AMediaExtractor_readSampleData(extractor_.get(), buf, cap);
        if (n < 0) {
            AMediaCodec_queueInputBuffer(codec_.get(), static_cast<size_t>(idx), 0, 0, 0,
                                         AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
            inputEos_ = true;
            return;
        }
        const uint64_t pts = static_cast<uint64_t>(AMediaExtractor_getSampleTime(extractor_.get()));
        AMediaCodec_queueInputBuffer(codec_.get(), static_cast<size_t>(idx), 0, static_cast<size_t>(n), pts, 0);
        AMediaExtractor_advance(extractor_.get());
    }

    void readOutputFormat() {
        FormatPtr f(AMediaCodec_getOutputFormat(codec_.get()), AMediaFormat_delete);
        if (!f) return;
        int32_t channels = 0;
        if (AMediaFormat_getInt32(f.get(), "channel-count", &channels) && channels >= 1 && channels <= 2) {
            channels_ = channels;
        }
        int32_t encoding = 2;  // ENCODING_PCM_16BIT when the key is absent
        AMediaFormat_getInt32(f.get(), "pcm-encoding", &encoding);
        pcmEncoding_ = encoding;
    }

    // Converts `bytes` of codec output in the current PCM encoding and appends stereo float frames.
    void appendPcm(const uint8_t* src, size_t bytes) {
        const int ch = channels_;
        size_t bytesPerSample = 2;
        if (pcmEncoding_ == 3) bytesPerSample = 1;        // ENCODING_PCM_8BIT
        else if (pcmEncoding_ == 4) bytesPerSample = 4;   // ENCODING_PCM_FLOAT
        else if (pcmEncoding_ == 21) bytesPerSample = 3;  // ENCODING_PCM_24BIT_PACKED
        else if (pcmEncoding_ == 22) bytesPerSample = 4;  // ENCODING_PCM_32BIT

        const size_t samples = bytes / bytesPerSample;
        const size_t frames = samples / static_cast<size_t>(ch);
        const size_t base = pending_.size();
        pending_.resize(base + frames * 2);

        for (size_t f = 0; f < frames; ++f) {
            float v[2] = {0.0f, 0.0f};
            for (int c = 0; c < ch; ++c) {
                const uint8_t* p = src + (f * static_cast<size_t>(ch) + static_cast<size_t>(c)) * bytesPerSample;
                float s = 0.0f;
                switch (pcmEncoding_) {
                    case 3:
                        s = (static_cast<float>(p[0]) - 128.0f) / 128.0f;
                        break;
                    case 4: {
                        std::memcpy(&s, p, sizeof s);
                        break;
                    }
                    case 21: {
                        int32_t x = static_cast<int32_t>(p[0] | (p[1] << 8) | (p[2] << 16));
                        if (x & 0x800000) x |= ~0xFFFFFF;
                        s = static_cast<float>(x) / 8388608.0f;
                        break;
                    }
                    case 22: {
                        int32_t x;
                        std::memcpy(&x, p, sizeof x);
                        s = static_cast<float>(x) / 2147483648.0f;
                        break;
                    }
                    default: {
                        int16_t x;
                        std::memcpy(&x, p, sizeof x);
                        s = static_cast<float>(x) / 32768.0f;
                        break;
                    }
                }
                v[c == 0 ? 0 : 1] = s;
            }
            if (ch == 1) v[1] = v[0];
            pending_[base + 2 * f] = v[0];
            pending_[base + 2 * f + 1] = v[1];
        }
    }

    FdReader reader_;
    int ownFd_ = -1;
    ExtractorPtr extractor_{nullptr, AMediaExtractor_delete};
    CodecPtr codec_{nullptr, AMediaCodec_delete};
    AppleAlacCodec alac_;
    Mode mode_ = Mode::kNone;
    uint32_t sampleRate_ = 0;
    int32_t channels_ = 0;
    int64_t totalFrames_ = -1;
    int64_t rawPos_ = 0;
    bool inputEos_ = false;
    bool outputEos_ = false;
    int32_t pcmEncoding_ = 2;
    std::vector<float> pending_;   // stereo interleaved
    int64_t pendingPos_ = 0;       // in frames
    std::vector<uint8_t> packet_;  // ALAC packet buffer
};

}  // namespace

ErrorCode makeMp4Decoder(int fd, int64_t offset, int64_t length, std::unique_ptr<Decoder>& out, TrackInfo& info) {
    auto dec = std::make_unique<Mp4Decoder>();
    const ErrorCode ec = dec->open(fd, offset, length, info);
    if (ec != kOk) return ec;
    out = std::move(dec);
    return kOk;
}

}  // namespace futura::player

#endif  // __ANDROID__
