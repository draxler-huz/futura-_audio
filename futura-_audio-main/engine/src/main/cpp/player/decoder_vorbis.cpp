// Ogg Vorbis decoder on stb_vorbis (public domain or MIT, see STATUS.md). Mono and stereo only.
// Vorbis has no encoder delay metadata, so nothing is trimmed. Seeking is exact (stb_vorbis_seek).
#include <cstdio>
#include <unistd.h>
#include <vector>

#include "decoder_common.h"

// stb_vorbis.c is compiled once, as its own C translation unit, by decoders.cmake. Here we only need its
// declarations, so the implementation is switched off for this include.
extern "C" {
#define STB_VORBIS_HEADER_ONLY
#include "stb_vorbis.c"
#undef STB_VORBIS_HEADER_ONLY
}

namespace futura::player {
namespace {

class VorbisDecoder : public Decoder {
public:
    ~VorbisDecoder() override {
        if (vorbis_) stb_vorbis_close(vorbis_);  // also closes the FILE* (close_on_free = 1)
    }

    ErrorCode open(int fd, int64_t offset, int64_t length, TrackInfo& info) override {
        FdReader probe;
        ErrorCode ec = probe.open(fd, offset, length);
        if (ec != kOk) return ec;
        const int64_t windowSize = probe.size();
        if (windowSize <= 0) return kEmptyTrack;

        // stb_vorbis reads through a FILE*. Give it its own descriptor, positioned at the window start.
        const int dupFd = ::dup(fd);
        if (dupFd < 0) return kIoError;
        FILE* file = ::fdopen(dupFd, "rb");
        if (!file) {
            ::close(dupFd);
            return kIoError;
        }
        if (std::fseek(file, static_cast<long>(offset), SEEK_SET) != 0) {
            std::fclose(file);
            return kIoError;
        }

        int error = 0;
        vorbis_ = stb_vorbis_open_file_section(file, 1 /* close the FILE* with the decoder */, &error, nullptr,
                                               static_cast<unsigned int>(windowSize));
        if (!vorbis_) return kUnsupportedFormat;  // the FILE* was closed by stb_vorbis on failure

        const stb_vorbis_info vi = stb_vorbis_get_info(vorbis_);
        if (vi.channels < 1 || vi.channels > 2 || vi.sample_rate == 0) return kUnsupportedFormat;
        channels_ = vi.channels;
        sampleRate_ = vi.sample_rate;
        totalFrames_ = static_cast<int64_t>(stb_vorbis_stream_length_in_samples(vorbis_));

        info.codec = Codec::kVorbis;
        info.sampleRate = sampleRate_;
        info.channels = channels_;
        info.bitDepth = 0;
        info.lossless = false;
        info.totalFrames = totalFrames_ > 0 ? totalFrames_ : -1;
        info.skipFrames = 0;
        info.trimEndFrames = 0;
        if (info.totalFrames < 0) totalFrames_ = -1;
        return kOk;
    }

    int64_t decodeFrames(float* out, int64_t frames) override {
        if (!vorbis_ || frames <= 0) return 0;
        if (totalFrames_ >= 0 && cursor_ + frames > totalFrames_) frames = totalFrames_ - cursor_;
        if (frames <= 0) return 0;

        const size_t floatsNeeded = static_cast<size_t>(frames * channels_);
        if (scratch_.size() < floatsNeeded) scratch_.resize(floatsNeeded);
        const int got = stb_vorbis_get_samples_float_interleaved(vorbis_, channels_, scratch_.data(),
                                                                 static_cast<int>(floatsNeeded));
        if (got < 0) return kDecodeError;
        for (int i = 0; i < got; ++i) {
            const float l = scratch_[static_cast<size_t>(i * channels_)];
            const float r = channels_ == 2 ? scratch_[static_cast<size_t>(i * 2 + 1)] : l;
            out[2 * i] = l;
            out[2 * i + 1] = r;
        }
        cursor_ += got;
        return got;
    }

    ErrorCode seekFrame(int64_t frame) override {
        if (!vorbis_) return kInvalidState;
        if (frame < 0) frame = 0;
        if (totalFrames_ >= 0 && frame > totalFrames_) frame = totalFrames_;
        if (stb_vorbis_seek(vorbis_, static_cast<unsigned int>(frame)) == 0) return kDecodeError;
        cursor_ = frame;
        return kOk;
    }

private:
    stb_vorbis* vorbis_ = nullptr;
    int32_t channels_ = 0;
    uint32_t sampleRate_ = 0;
    int64_t totalFrames_ = -1;
    int64_t cursor_ = 0;
    std::vector<float> scratch_;
};

}  // namespace

std::unique_ptr<Decoder> makeVorbisDecoder() { return std::make_unique<VorbisDecoder>(); }

}  // namespace futura::player
