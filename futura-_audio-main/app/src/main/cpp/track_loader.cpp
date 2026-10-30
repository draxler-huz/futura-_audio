#include "track_loader.h"

#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>

namespace futura {
namespace {

constexpr int64_t kTimeoutUs = 10000;

// Key name of AMEDIAFORMAT_KEY_PCM_ENCODING. Written out so the code does not
// depend on which NDK API level defines the macro.
constexpr const char* kPcmEncodingKey = "pcm-encoding";

enum class SampleKind { kU8, kS16, kS24, kS32, kF32 };

size_t bytesPerSample(SampleKind kind) {
    switch (kind) {
        case SampleKind::kU8: return 1;
        case SampleKind::kS16: return 2;
        case SampleKind::kS24: return 3;
        case SampleKind::kS32: return 4;
        case SampleKind::kF32: return 4;
    }
    return 2;
}

uint16_t le16(const uint8_t* p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }

uint32_t le32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

bool readAt(int fd, int64_t pos, void* dst, size_t n) {
    uint8_t* p = static_cast<uint8_t*>(dst);
    while (n > 0) {
        const ssize_t r = pread(fd, p, n, static_cast<off_t>(pos));
        if (r <= 0) return false;
        p += r;
        pos += r;
        n -= static_cast<size_t>(r);
    }
    return true;
}

// Reads one sample from a little-endian buffer and returns it as float in [-1, 1].
float readSample(const uint8_t* p, SampleKind kind) {
    switch (kind) {
        case SampleKind::kU8:
            return (static_cast<int32_t>(p[0]) - 128) / 128.0f;
        case SampleKind::kS16:
            return static_cast<int16_t>(le16(p)) / 32768.0f;
        case SampleKind::kS24: {
            int32_t v = p[0] | (p[1] << 8) | (p[2] << 16);
            if (v & 0x800000) v -= 0x1000000;  // sign-extend 24-bit
            return static_cast<float>(v) / 8388608.0f;
        }
        case SampleKind::kS32:
            return static_cast<int32_t>(le32(p)) / 2147483648.0f;
        case SampleKind::kF32: {
            float f;
            std::memcpy(&f, p, sizeof f);
            return f;
        }
    }
    return 0.0f;
}

// Appends whole frames from `data` as interleaved stereo.
// Mono is duplicated to both channels. More than two channels keep the first two.
void appendStereo(const uint8_t* data, size_t bytes, int channels, SampleKind kind,
                  std::vector<float>& stereo) {
    const size_t bps = bytesPerSample(kind);
    const size_t frameBytes = bps * static_cast<size_t>(channels);
    const size_t frames = bytes / frameBytes;
    const size_t base = stereo.size();
    stereo.resize(base + frames * kChannels);
    for (size_t f = 0; f < frames; ++f) {
        const uint8_t* frame = data + f * frameBytes;
        const float left = readSample(frame, kind);
        const float right = channels > 1 ? readSample(frame + bps, kind) : left;
        stereo[base + f * 2] = left;
        stereo[base + f * 2 + 1] = right;
    }
}

size_t maxFramesAt(uint32_t sampleRate) {
    return static_cast<size_t>(static_cast<double>(sampleRate) * kMaxPreloadSeconds);
}

// ------------------------------------------------------------------ WAV path

struct WavLayout {
    int channels = 0;
    uint32_t sampleRate = 0;
    SampleKind kind = SampleKind::kS16;
    int64_t dataOffset = 0;  // absolute file offset of the PCM payload
    int64_t dataSize = 0;
};

bool looksLikeWav(int fd, int64_t offset) {
    uint8_t h[12];
    return readAt(fd, offset, h, sizeof h) && std::memcmp(h, "RIFF", 4) == 0 &&
           std::memcmp(h + 8, "WAVE", 4) == 0;
}

ErrorCode parseWav(int fd, int64_t base, int64_t length, WavLayout& out) {
    const int64_t end = base + length;
    int64_t pos = base + 12;
    bool haveFmt = false;
    bool haveData = false;
    uint16_t formatTag = 0;
    uint16_t bits = 0;
    int channels = 0;
    uint32_t rate = 0;

    // Scan chunks. Bounded so a corrupt header cannot loop forever.
    for (int n = 0; n < 64 && pos + 8 <= end && !(haveFmt && haveData); ++n) {
        uint8_t hdr[8];
        if (!readAt(fd, pos, hdr, sizeof hdr)) return kIoError;
        const uint32_t size = le32(hdr + 4);
        const int64_t body = pos + 8;

        if (std::memcmp(hdr, "fmt ", 4) == 0) {
            if (size < 16 || body + 16 > end) return kDecodeError;
            uint8_t f[40] = {};
            const size_t take = std::min<size_t>(size, sizeof f);
            if (!readAt(fd, body, f, take)) return kIoError;
            formatTag = le16(f);
            channels = le16(f + 2);
            rate = le32(f + 4);
            bits = le16(f + 14);
            // WAVE_FORMAT_EXTENSIBLE: the real format tag is the first two bytes of the sub-format GUID.
            if (formatTag == 0xFFFE && take >= 26) formatTag = le16(f + 24);
            haveFmt = true;
        } else if (std::memcmp(hdr, "data", 4) == 0) {
            const int64_t avail = end - body;
            // 0xFFFFFFFF is a streaming placeholder. Treat it as "to end of file".
            out.dataOffset = body;
            out.dataSize = (size == 0xFFFFFFFFu || static_cast<int64_t>(size) > avail)
                               ? avail
                               : static_cast<int64_t>(size);
            haveData = true;
        }

        pos = body + static_cast<int64_t>(size) + (size & 1u);  // chunks are word-aligned
    }

    if (!haveFmt || !haveData) return kDecodeError;
    if (channels < 1 || channels > 8 || rate == 0) return kUnsupportedFormat;

    if (formatTag == 1 && bits == 8) {
        out.kind = SampleKind::kU8;
    } else if (formatTag == 1 && bits == 16) {
        out.kind = SampleKind::kS16;
    } else if (formatTag == 1 && bits == 24) {
        out.kind = SampleKind::kS24;
    } else if (formatTag == 1 && bits == 32) {
        out.kind = SampleKind::kS32;
    } else if (formatTag == 3 && bits == 32) {
        out.kind = SampleKind::kF32;
    } else {
        return kUnsupportedFormat;
    }

    out.channels = channels;
    out.sampleRate = rate;
    return kOk;
}

ErrorCode decodeWavFrames(int fd, const WavLayout& w, std::vector<float>& stereo) {
    const size_t blockAlign = bytesPerSample(w.kind) * static_cast<size_t>(w.channels);
    const size_t chunkBytes = std::max<size_t>(blockAlign, (64 * 1024 / blockAlign) * blockAlign);
    const size_t maxFrames = maxFramesAt(w.sampleRate);
    std::vector<uint8_t> buf(chunkBytes);

    int64_t done = 0;
    while (done < w.dataSize) {
        size_t want = static_cast<size_t>(
            std::min<int64_t>(static_cast<int64_t>(chunkBytes), w.dataSize - done));
        want -= want % blockAlign;  // only whole frames
        if (want == 0) break;
        if (!readAt(fd, w.dataOffset + done, buf.data(), want)) return kIoError;
        appendStereo(buf.data(), want, w.channels, w.kind, stereo);
        if (stereo.size() / kChannels > maxFrames) return kTrackTooLong;
        done += static_cast<int64_t>(want);
    }
    return kOk;
}

// ------------------------------------------------------------ Compressed path

struct ExtractorDeleter {
    void operator()(AMediaExtractor* e) const { AMediaExtractor_delete(e); }
};
struct CodecDeleter {
    void operator()(AMediaCodec* c) const {
        AMediaCodec_stop(c);  // harmless if the codec never started
        AMediaCodec_delete(c);
    }
};
struct FormatDeleter {
    void operator()(AMediaFormat* f) const { AMediaFormat_delete(f); }
};
using ExtractorPtr = std::unique_ptr<AMediaExtractor, ExtractorDeleter>;
using CodecPtr = std::unique_ptr<AMediaCodec, CodecDeleter>;
using FormatPtr = std::unique_ptr<AMediaFormat, FormatDeleter>;

// Values from android.media.AudioFormat, as reported in the codec's pcm-encoding key.
bool sampleKindFromPcmEncoding(int32_t enc, SampleKind& kind) {
    switch (enc) {
        case 2: kind = SampleKind::kS16; return true;   // ENCODING_PCM_16BIT (codec default)
        case 3: kind = SampleKind::kU8; return true;    // ENCODING_PCM_8BIT
        case 4: kind = SampleKind::kF32; return true;   // ENCODING_PCM_FLOAT
        case 21: kind = SampleKind::kS24; return true;  // ENCODING_PCM_24BIT_PACKED
        case 22: kind = SampleKind::kS32; return true;  // ENCODING_PCM_32BIT
        default: return false;
    }
}

ErrorCode decodeCompressed(int fd, int64_t base, int64_t length, std::vector<float>& stereo,
                           uint32_t& sourceRate) {
    ExtractorPtr extractor(AMediaExtractor_new());
    if (!extractor) return kDecodeError;
    if (AMediaExtractor_setDataSourceFd(extractor.get(), fd, base, length) != AMEDIA_OK) {
        return kUnsupportedFormat;
    }

    // Pick the first audio track.
    const size_t trackCount = AMediaExtractor_getTrackCount(extractor.get());
    size_t audioTrack = trackCount;
    FormatPtr trackFormat;
    std::string mime;
    for (size_t i = 0; i < trackCount && audioTrack == trackCount; ++i) {
        FormatPtr candidate(AMediaExtractor_getTrackFormat(extractor.get(), i));
        const char* m = nullptr;
        if (candidate && AMediaFormat_getString(candidate.get(), AMEDIAFORMAT_KEY_MIME, &m) &&
            m != nullptr && std::strncmp(m, "audio/", 6) == 0) {
            audioTrack = i;
            mime = m;  // copy before the format can be released
            trackFormat = std::move(candidate);
        }
    }
    if (audioTrack == trackCount) return kUnsupportedFormat;
    if (AMediaExtractor_selectTrack(extractor.get(), audioTrack) != AMEDIA_OK) return kDecodeError;

    CodecPtr codec(AMediaCodec_createDecoderByType(mime.c_str()));
    if (!codec) return kUnsupportedFormat;
    if (AMediaCodec_configure(codec.get(), trackFormat.get(), nullptr, nullptr, 0) != AMEDIA_OK) {
        return kUnsupportedFormat;
    }
    if (AMediaCodec_start(codec.get()) != AMEDIA_OK) return kDecodeError;

    SampleKind kind = SampleKind::kS16;
    int outChannels = 0;
    bool haveOutputFormat = false;
    bool inputDone = false;
    bool outputDone = false;

    while (!outputDone) {
        if (!inputDone) {
            const ssize_t inIdx = AMediaCodec_dequeueInputBuffer(codec.get(), kTimeoutUs);
            if (inIdx >= 0) {
                size_t capacity = 0;
                uint8_t* buffer = AMediaCodec_getInputBuffer(codec.get(), static_cast<size_t>(inIdx), &capacity);
                const ssize_t sampleSize =
                    buffer != nullptr ? AMediaExtractor_readSampleData(extractor.get(), buffer, capacity) : -1;
                if (sampleSize < 0) {
                    AMediaCodec_queueInputBuffer(codec.get(), static_cast<size_t>(inIdx), 0, 0, 0,
                                                 AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    inputDone = true;
                } else {
                    const uint64_t pts = static_cast<uint64_t>(AMediaExtractor_getSampleTime(extractor.get()));
                    AMediaCodec_queueInputBuffer(codec.get(), static_cast<size_t>(inIdx), 0,
                                                 static_cast<size_t>(sampleSize), pts, 0);
                    AMediaExtractor_advance(extractor.get());
                }
            } else if (inIdx != AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
                return kDecodeError;
            }
        }

        AMediaCodecBufferInfo info{};
        const ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec.get(), &info, kTimeoutUs);

        if (outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            FormatPtr outFormat(AMediaCodec_getOutputFormat(codec.get()));
            if (!outFormat) return kDecodeError;
            int32_t ch = 0;
            int32_t rate = 0;
            int32_t enc = 2;  // MediaCodec's default PCM output is 16-bit
            AMediaFormat_getInt32(outFormat.get(), AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);
            AMediaFormat_getInt32(outFormat.get(), AMEDIAFORMAT_KEY_SAMPLE_RATE, &rate);
            AMediaFormat_getInt32(outFormat.get(), kPcmEncodingKey, &enc);
            if (ch < 1 || rate <= 0 || !sampleKindFromPcmEncoding(enc, kind)) return kUnsupportedFormat;
            outChannels = ch;
            sourceRate = static_cast<uint32_t>(rate);
            haveOutputFormat = true;
        } else if (outIdx == AMEDIACODEC_INFO_TRY_AGAIN_LATER ||
                   outIdx == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
            // Nothing to do this round.
        } else if (outIdx < 0) {
            return kDecodeError;
        } else {
            if (!haveOutputFormat) return kDecodeError;
            const size_t idx = static_cast<size_t>(outIdx);
            if (info.size > 0) {
                size_t capacity = 0;
                const uint8_t* data = AMediaCodec_getOutputBuffer(codec.get(), idx, &capacity);
                const bool inRange = data != nullptr && info.offset >= 0 &&
                                     static_cast<size_t>(info.offset) + static_cast<size_t>(info.size) <= capacity;
                if (!inRange) {
                    AMediaCodec_releaseOutputBuffer(codec.get(), idx, false);
                    return kDecodeError;
                }
                appendStereo(data + info.offset, static_cast<size_t>(info.size), outChannels, kind, stereo);
                if (stereo.size() / kChannels > maxFramesAt(sourceRate)) {
                    AMediaCodec_releaseOutputBuffer(codec.get(), idx, false);
                    return kTrackTooLong;
                }
            }
            AMediaCodec_releaseOutputBuffer(codec.get(), idx, false);
            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) outputDone = true;
        }
    }
    return kOk;
}

// ------------------------------------------------------------------ Resampler

// 4-point, 3rd-order Hermite interpolation per channel. Good enough for alarm-length
// material; it does not apply an anti-alias filter when downsampling.
void resampleStereo(const std::vector<float>& in, uint32_t srcRate, uint32_t dstRate,
                    std::vector<float>& out) {
    const int64_t inFrames = static_cast<int64_t>(in.size() / kChannels);
    const double ratio = static_cast<double>(srcRate) / static_cast<double>(dstRate);
    const int64_t outFrames = static_cast<int64_t>(std::floor(static_cast<double>(inFrames) / ratio));
    out.assign(static_cast<size_t>(outFrames) * kChannels, 0.0f);

    auto tap = [&](int64_t idx, int32_t c) -> float {
        idx = std::clamp<int64_t>(idx, 0, inFrames - 1);
        return in[static_cast<size_t>(idx) * kChannels + static_cast<size_t>(c)];
    };

    for (int64_t i = 0; i < outFrames; ++i) {
        const double pos = static_cast<double>(i) * ratio;
        const int64_t i1 = static_cast<int64_t>(pos);
        const float t = static_cast<float>(pos - static_cast<double>(i1));
        for (int32_t c = 0; c < kChannels; ++c) {
            const float x0 = tap(i1 - 1, c);
            const float x1 = tap(i1, c);
            const float x2 = tap(i1 + 1, c);
            const float x3 = tap(i1 + 2, c);
            const float c1 = 0.5f * (x2 - x0);
            const float c2 = x0 - 2.5f * x1 + 2.0f * x2 - 0.5f * x3;
            const float c3 = 0.5f * (x3 - x0) + 1.5f * (x1 - x2);
            out[static_cast<size_t>(i) * kChannels + static_cast<size_t>(c)] =
                ((c3 * t + c2) * t + c1) * t + x1;
        }
    }
}

}  // namespace

// ------------------------------------------------------------------ Public API

ErrorCode decodeTrack(int fd, int64_t offset, int64_t length, uint32_t dstRate, PcmBuffer& out) {
    if (offset < 0 || dstRate == 0) return kInvalidState;
    if (length < 0) {
        struct stat st {};
        if (fstat(fd, &st) != 0) return kIoError;
        length = static_cast<int64_t>(st.st_size) - offset;
    }
    if (length <= 0) return kEmptyTrack;

    std::vector<float> stereo;
    uint32_t sourceRate = 0;
    ErrorCode ec = kOk;

    if (looksLikeWav(fd, offset)) {
        WavLayout wav;
        ec = parseWav(fd, offset, length, wav);
        if (ec == kOk) {
            ec = decodeWavFrames(fd, wav, stereo);
            sourceRate = wav.sampleRate;
        }
    } else {
        ec = decodeCompressed(fd, offset, length, stereo, sourceRate);
    }
    if (ec != kOk) return ec;
    if (stereo.empty() || sourceRate == 0) return kEmptyTrack;

    if (sourceRate == dstRate) {
        out.samples = std::move(stereo);
    } else {
        resampleStereo(stereo, sourceRate, dstRate, out.samples);
    }
    if (out.samples.empty()) return kEmptyTrack;
    if (out.frames() > maxFramesAt(dstRate)) return kTrackTooLong;

    out.sampleRate = dstRate;
    return kOk;
}

}  // namespace futura
