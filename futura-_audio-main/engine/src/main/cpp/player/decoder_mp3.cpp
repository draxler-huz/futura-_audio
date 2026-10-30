// MP3 decoder on minimp3 (CC0; see STATUS.md), with our own parser for the Xing/Info frame and the LAME tag.
//
// Gapless (LAME/Xing). The first MPEG frame may be a Xing or Info frame. It holds no audio, so it is not
// output. When a LAME tag is present:
//   totalFrames  = Xing frame count * samples per frame (1152 MPEG-1, 576 MPEG-2/2.5)
//   skipFrames   = encoder delay (12 bits, LAME tag offset 0x15) + 529 decoder delay
//   trimEndFrames = encoder padding (the other 12 bits) - 529, clamped at 0. The 529 is the decoder delay
//                   again, so the end trim is padding - 529 (ffmpeg: first_discard = frames*spf - end_pad + 529).
// The version string is "LAME..." from LAME itself, or "Lavc..." / "Lavf..." from ffmpeg. The layout is the same.
// Host tests check these values against ffmpeg's own decode and ffprobe.
// Without a Xing frame, totalFrames is -1 and nothing is trimmed.
//
// Seeking is slow: it resets the decoder and decodes and discards audio from the start of the file.
// Float output: minimp3 then does not saturate at +/-1.0 like its int16 output does, so it agrees with
// float decoders (ffmpeg) on loud passages.
#define MINIMP3_FLOAT_OUTPUT
#define MINIMP3_IMPLEMENTATION
#include "minimp3.h"

#include <cstring>
#include <vector>

#include "decoder_common.h"

namespace futura::player {
namespace {

constexpr size_t kBufBytes = 64 * 1024;
constexpr size_t kRefillBelow = 16 * 1024;  // refill when fewer unread bytes remain than this
constexpr int64_t kDecoderDelay = 529;
constexpr size_t kHeaderCopy = 256;         // enough for a Xing frame's header, side info and LAME tag
constexpr int64_t kLameDelayOffset = 0x15;  // from the "LAME" tag start

// Parses the Xing/Info header in the first frame's bytes. Returns true when one is present.
bool parseXing(const uint8_t* h, size_t len, int64_t& frames, int64_t& delay, int64_t& padding, bool& hasLame,
               int& samplesPerFrame) {
    frames = -1;
    delay = 0;
    padding = 0;
    hasLame = false;
    if (len < 4 || h[0] != 0xFF) return false;

    const int version = (h[1] >> 3) & 0x3;  // 3 = MPEG-1, 2 = MPEG-2, 0 = MPEG-2.5
    const bool mono = ((h[3] >> 6) & 0x3) == 3;
    const bool mpeg1 = version == 3;
    samplesPerFrame = mpeg1 ? 1152 : 576;
    const bool crc = (h[1] & 0x1) == 0;
    const size_t sideInfo = mpeg1 ? (mono ? 17 : 32) : (mono ? 9 : 17);
    const size_t tag = 4 + sideInfo + (crc ? 2 : 0);

    if (len < tag + 8) return false;
    if (std::memcmp(h + tag, "Xing", 4) != 0 && std::memcmp(h + tag, "Info", 4) != 0) return false;

    const uint32_t flags = readBe32(h + tag + 4);
    size_t pos = tag + 8;
    if (flags & 0x1) {
        if (pos + 4 > len) return false;
        frames = readBe32(h + pos);
        pos += 4;
    }
    if (flags & 0x2) pos += 4;    // byte count
    if (flags & 0x4) pos += 100;  // TOC
    if (flags & 0x8) pos += 4;    // quality

    const bool lameLike = std::memcmp(h + pos, "LAME", 4) == 0 || std::memcmp(h + pos, "Lavc", 4) == 0 ||
                          std::memcmp(h + pos, "Lavf", 4) == 0;
    if (pos + kLameDelayOffset + 3 <= len && lameLike) {
        const size_t d = pos + kLameDelayOffset;
        const uint32_t v = (static_cast<uint32_t>(h[d]) << 16) | (static_cast<uint32_t>(h[d + 1]) << 8) | h[d + 2];
        delay = static_cast<int64_t>(v >> 12);
        padding = static_cast<int64_t>(v & 0xFFF);
        hasLame = true;
    }
    return true;
}

// Reads the ID3v2 tag size at the start of the file, if one is present. Returns the bytes to skip.
int64_t id3Size(const FdReader& reader) {
    uint8_t h[10];
    if (reader.readAt(0, h, 10) != 10 || std::memcmp(h, "ID3", 3) != 0) return 0;
    const int64_t body = ((h[6] & 0x7F) << 21) | ((h[7] & 0x7F) << 14) | ((h[8] & 0x7F) << 7) | (h[9] & 0x7F);
    return 10 + body + ((h[5] & 0x10) ? 10 : 0);
}

class Mp3Decoder : public Decoder {
public:
    Mp3Decoder() : buf_(kBufBytes), pcm_(MINIMP3_MAX_SAMPLES_PER_FRAME) {}

    ErrorCode open(int fd, int64_t offset, int64_t length, TrackInfo& info) override {
        ErrorCode ec = reader_.open(fd, offset, length);
        if (ec != kOk) return ec;

        dataStart_ = id3Size(reader_);
        resetStream();

        // Read the first frame. It decides the format, and it may be the Xing frame.
        int spc = 0;
        mp3dec_frame_info_t fi{};
        if (!decodeNext(spc, fi)) return kUnsupportedFormat;
        if (fi.channels < 1 || fi.channels > 2 || fi.hz <= 0) return kUnsupportedFormat;
        channels_ = fi.channels;
        sampleRate_ = static_cast<uint32_t>(fi.hz);

        int64_t frames = -1;
        int64_t delay = 0;
        int64_t padding = 0;
        bool hasLame = false;
        int spf = 0;
        xing_ = parseXing(lastHeader_, lastHeaderLen_, frames, delay, padding, hasLame, spf);
        if (xing_) {
            // The Xing frame is not audio. Drop it, and keep decoding from the frame after it.
            pcmPos_ = pcmCount_ = 0;
            totalFrames_ = frames > 0 ? frames * spf : -1;
            samplesPerFrame_ = spf;
            if (hasLame) {
                skipFrames_ = delay + kDecoderDelay;
                trimEndFrames_ = padding > kDecoderDelay ? padding - kDecoderDelay : 0;
            }
        } else {
            // No Xing frame: the first frame is audio. Keep its samples as the current block.
            pcmCount_ = spc;
            pcmPos_ = 0;
        }

        info.codec = Codec::kMp3;
        info.sampleRate = sampleRate_;
        info.channels = channels_;
        info.bitDepth = 0;
        info.lossless = false;
        info.totalFrames = totalFrames_;
        info.skipFrames = skipFrames_;
        info.trimEndFrames = trimEndFrames_;
        return kOk;
    }

    int64_t decodeFrames(float* out, int64_t frames) override {
        if (frames <= 0) return 0;
        const int64_t limit = totalFrames_ >= 0 ? totalFrames_ : INT64_MAX;

        int64_t done = 0;
        while (done < frames && rawPos_ < limit) {
            if (pcmPos_ >= pcmCount_ && !nextBlock()) break;

            int64_t n = pcmCount_ - pcmPos_;
            if (n > frames - done) n = frames - done;
            if (n > limit - rawPos_) n = limit - rawPos_;

            for (int64_t i = 0; i < n; ++i) {
                const size_t src = static_cast<size_t>((pcmPos_ + i) * channels_);
                const float l = pcm_[src];
                const float r = channels_ == 2 ? pcm_[src + 1] : l;
                out[2 * (done + i)] = l;
                out[2 * (done + i) + 1] = r;
            }
            pcmPos_ += n;
            done += n;
            rawPos_ += n;
        }
        return done;
    }

    ErrorCode seekFrame(int64_t frame) override {
        if (frame < 0) frame = 0;
        if (totalFrames_ >= 0 && frame > totalFrames_) frame = totalFrames_;

        resetStream();
        if (xing_) {
            int spc = 0;
            mp3dec_frame_info_t fi{};
            decodeNext(spc, fi);  // discard the Xing frame
        }
        // Slow path: decode and discard until the target. Seeking into a long file costs real time.
        while (rawPos_ < frame) {
            if (pcmPos_ >= pcmCount_ && !nextBlock()) break;
            int64_t n = pcmCount_ - pcmPos_;
            if (n > frame - rawPos_) n = frame - rawPos_;
            pcmPos_ += n;
            rawPos_ += n;
        }
        return kOk;
    }

private:
    // Puts the stream back at the first audio byte with a fresh decoder.
    void resetStream() {
        mp3dec_init(&dec_);
        bufPos_ = bufLen_ = 0;
        fileNext_ = dataStart_;
        eof_ = false;
        pcmPos_ = pcmCount_ = 0;
        rawPos_ = 0;
    }

    // Loads the next block of samples into pcm_. Returns false at end of stream.
    bool nextBlock() {
        int spc = 0;
        mp3dec_frame_info_t fi{};
        if (!decodeNext(spc, fi)) return false;
        pcmCount_ = spc;
        pcmPos_ = 0;
        return true;
    }

    // Tops up buf_ from the file when fewer than kRefillBelow unread bytes remain.
    void refill() {
        if (eof_) return;
        const size_t unread = bufLen_ - bufPos_;
        if (unread >= kRefillBelow) return;
        std::memmove(buf_.data(), buf_.data() + bufPos_, unread);
        bufPos_ = 0;
        bufLen_ = unread;
        const int64_t got = reader_.readAt(fileNext_, buf_.data() + bufLen_,
                                           static_cast<int64_t>(kBufBytes - bufLen_));
        if (got <= 0) {
            eof_ = true;  // end of file, or an I/O error, both end the stream
            return;
        }
        fileNext_ += got;
        bufLen_ += static_cast<size_t>(got);
    }

    // Decodes the next frame that yields samples. Frames that minimp3 skips (junk or a broken bit reservoir)
    // are passed over. Returns false at end of stream.
    bool decodeNext(int& spc, mp3dec_frame_info_t& fi) {
        for (;;) {
            refill();
            const size_t avail = bufLen_ - bufPos_;
            if (avail == 0) return false;

            spc = mp3dec_decode_frame(&dec_, buf_.data() + bufPos_, static_cast<int>(avail), pcm_.data(), &fi);
            if (fi.frame_bytes == 0) {
                // No frame in these bytes. Keep a tail so a frame that straddles the boundary can be found.
                if (eof_) {
                    bufPos_ = bufLen_;
                    return false;
                }
                if (avail > kRefillBelow / 2) bufPos_ = bufLen_ - kRefillBelow / 2;
                else bufPos_ = bufLen_;
                continue;
            }

            // Keep the first frame's header for the Xing check, before the buffer moves on.
            if (!firstFrameSeen_) {
                lastHeaderLen_ = static_cast<size_t>(fi.frame_bytes) < kHeaderCopy
                                     ? static_cast<size_t>(fi.frame_bytes) : kHeaderCopy;
                std::memcpy(lastHeader_, buf_.data() + bufPos_ + fi.frame_offset, lastHeaderLen_);
                firstFrameSeen_ = true;
            }
            bufPos_ += static_cast<size_t>(fi.frame_offset + fi.frame_bytes);

            if (spc > 0) return true;
        }
    }

    FdReader reader_;
    mp3dec_t dec_{};
    std::vector<uint8_t> buf_;
    size_t bufPos_ = 0;
    size_t bufLen_ = 0;
    int64_t fileNext_ = 0;
    int64_t dataStart_ = 0;
    bool eof_ = false;

    std::vector<mp3d_sample_t> pcm_;  // interleaved, current block
    int64_t pcmCount_ = 0;            // samples per channel in pcm_
    int64_t pcmPos_ = 0;              // next unread sample per channel

    bool firstFrameSeen_ = false;
    uint8_t lastHeader_[kHeaderCopy] = {};
    size_t lastHeaderLen_ = 0;

    bool xing_ = false;
    int samplesPerFrame_ = 0;
    int32_t channels_ = 0;
    uint32_t sampleRate_ = 0;
    int64_t totalFrames_ = -1;
    int64_t skipFrames_ = 0;
    int64_t trimEndFrames_ = 0;
    int64_t rawPos_ = 0;
};

}  // namespace

std::unique_ptr<Decoder> makeMp3Decoder() { return std::make_unique<Mp3Decoder>(); }

}  // namespace futura::player
