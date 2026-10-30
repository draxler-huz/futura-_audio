// FLAC decoder on dr_libs dr_flac.h (public domain or MIT-0; see STATUS.md). Mono and stereo only.
// FLAC is naturally gapless, so there is no skip or trim. Seeking is exact (seek table or binary search).
#define DR_FLAC_IMPLEMENTATION
#include "dr_flac.h"

#include <vector>

#include "decoder_common.h"

namespace futura::player {
namespace {

// Cursor handed to dr_flac. Reads go through the bounded FdReader, so the window is respected.
struct FlacIo {
    const FdReader* reader = nullptr;
    int64_t pos = 0;
};

size_t onRead(void* userData, void* dst, size_t bytes) {
    FlacIo* io = static_cast<FlacIo*>(userData);
    const int64_t got = io->reader->readAt(io->pos, dst, static_cast<int64_t>(bytes));
    if (got <= 0) return 0;
    io->pos += got;
    return static_cast<size_t>(got);
}

drflac_bool32 onSeek(void* userData, int offset, drflac_seek_origin origin) {
    FlacIo* io = static_cast<FlacIo*>(userData);
    int64_t base = 0;
    if (origin == DRFLAC_SEEK_CUR) base = io->pos;
    if (origin == DRFLAC_SEEK_END) base = io->reader->size();
    const int64_t target = base + offset;
    if (target < 0 || target > io->reader->size()) return DRFLAC_FALSE;
    io->pos = target;
    return DRFLAC_TRUE;
}

drflac_bool32 onTell(void* userData, drflac_int64* cursor) {
    *cursor = static_cast<FlacIo*>(userData)->pos;
    return DRFLAC_TRUE;
}

class FlacDecoder : public Decoder {
public:
    ~FlacDecoder() override {
        if (flac_) drflac_close(flac_);
    }

    ErrorCode open(int fd, int64_t offset, int64_t length, TrackInfo& info) override {
        ErrorCode ec = reader_.open(fd, offset, length);
        if (ec != kOk) return ec;

        io_.reader = &reader_;
        io_.pos = 0;
        flac_ = drflac_open(onRead, onSeek, onTell, &io_, nullptr);
        if (!flac_) return kUnsupportedFormat;
        if (flac_->channels < 1 || flac_->channels > 2) return kUnsupportedFormat;

        channels_ = static_cast<int32_t>(flac_->channels);
        info.codec = Codec::kFlac;
        info.sampleRate = flac_->sampleRate;
        info.channels = channels_;
        info.bitDepth = static_cast<int32_t>(flac_->bitsPerSample);
        info.lossless = true;
        info.totalFrames = flac_->totalPCMFrameCount > 0 ? static_cast<int64_t>(flac_->totalPCMFrameCount) : -1;
        info.skipFrames = 0;
        info.trimEndFrames = 0;
        totalFrames_ = info.totalFrames;
        return kOk;
    }

    int64_t decodeFrames(float* out, int64_t frames) override {
        if (!flac_ || frames <= 0) return 0;
        if (totalFrames_ >= 0 && cursor_ + frames > totalFrames_) frames = totalFrames_ - cursor_;
        if (frames <= 0) return 0;

        if (channels_ == 2) {
            const uint64_t got = drflac_read_pcm_frames_f32(flac_, static_cast<drflac_uint64>(frames), out);
            cursor_ += static_cast<int64_t>(got);
            return static_cast<int64_t>(got);
        }
        // Mono: read into scratch, then duplicate to both channels.
        const size_t need = static_cast<size_t>(frames);
        if (mono_.size() < need) mono_.resize(need);
        const uint64_t got = drflac_read_pcm_frames_f32(flac_, static_cast<drflac_uint64>(frames), mono_.data());
        for (uint64_t i = 0; i < got; ++i) {
            out[2 * i] = mono_[i];
            out[2 * i + 1] = mono_[i];
        }
        cursor_ += static_cast<int64_t>(got);
        return static_cast<int64_t>(got);
    }

    ErrorCode seekFrame(int64_t frame) override {
        if (!flac_) return kInvalidState;
        if (frame < 0) frame = 0;
        if (totalFrames_ >= 0 && frame > totalFrames_) frame = totalFrames_;
        if (!drflac_seek_to_pcm_frame(flac_, static_cast<drflac_uint64>(frame))) return kDecodeError;
        cursor_ = frame;
        return kOk;
    }

private:
    FdReader reader_;
    FlacIo io_;
    drflac* flac_ = nullptr;
    int32_t channels_ = 0;
    int64_t totalFrames_ = -1;
    int64_t cursor_ = 0;
    std::vector<float> mono_;
};

}  // namespace

std::unique_ptr<Decoder> makeFlacDecoder() { return std::make_unique<FlacDecoder>(); }

}  // namespace futura::player
