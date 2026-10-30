// Round trip through Apple's ALAC encoder and our AppleAlacCodec wrapper. ALAC is lossless, so every sample
// must come back exactly. This exercises the Apple decoder integration that the Android build uses.
// Only the MP4 container (NDK MediaExtractor) is not covered here. That path runs on device only.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>

#include "ALACAudioTypes.h"
#include "ALACEncoder.h"
#include "decoder_common.h"

int main() {
    const uint32_t rate = 44100;
    const int32_t channels = 2;
    const size_t frames = 4096 * 5 + 1234;  // several full packets, then a partial one

    // Input: 16-bit interleaved stereo sine pair.
    std::vector<int16_t> pcm(frames * 2);
    for (size_t i = 0; i < frames; ++i) {
        pcm[2 * i] = static_cast<int16_t>(12000.0 * std::sin(2.0 * M_PI * 440.0 * static_cast<double>(i) / rate));
        pcm[2 * i + 1] = static_cast<int16_t>(9000.0 * std::sin(2.0 * M_PI * 660.0 * static_cast<double>(i) / rate));
    }

    ALACEncoder enc;
    AudioFormatDescription out{};
    out.mSampleRate = rate;
    out.mFormatID = kALACFormatAppleLossless;
    out.mFormatFlags = 1;  // 1 = 16-bit in ALACEncoder's convention
    out.mChannelsPerFrame = channels;
    out.mBitsPerChannel = 16;
    if (enc.InitializeEncoder(out) != 0) {
        std::printf("FAIL encoder init\n");
        return 1;
    }
    uint32_t cookieSize = enc.GetMagicCookieSize(channels);
    std::vector<uint8_t> cookie(cookieSize);
    enc.GetMagicCookie(cookie.data(), &cookieSize);
    cookie.resize(cookieSize);

    AudioFormatDescription in{};
    in.mSampleRate = rate;
    in.mFormatID = kALACFormatLinearPCM;
    in.mFormatFlags = kALACFormatFlagIsSignedInteger | kALACFormatFlagIsPacked;
    in.mBytesPerPacket = static_cast<uint32_t>(channels * 2);
    in.mFramesPerPacket = 1;
    in.mBytesPerFrame = static_cast<uint32_t>(channels * 2);
    in.mChannelsPerFrame = channels;
    in.mBitsPerChannel = 16;

    // Encode packet by packet, keeping each packet and its frame count.
    std::vector<std::vector<uint8_t>> packets;
    std::vector<size_t> packetFrames;
    std::vector<uint8_t> writeBuf(64 * 1024);
    for (size_t pos = 0; pos < frames;) {
        const size_t n = std::min<size_t>(4096, frames - pos);
        int32_t bytes = static_cast<int32_t>(n * 4);
        if (enc.Encode(in, out, reinterpret_cast<unsigned char*>(pcm.data() + pos * 2),
                       writeBuf.data(), &bytes) != 0 || bytes <= 0) {
            std::printf("FAIL encode at frame %zu\n", pos);
            return 1;
        }
        packets.emplace_back(writeBuf.begin(), writeBuf.begin() + bytes);
        packetFrames.push_back(n);
        pos += n;
    }

    futura::player::AppleAlacCodec dec;
    if (dec.init(cookie.data(), cookie.size()) != futura::kOk) {
        std::printf("FAIL decoder init\n");
        return 1;
    }
    std::printf("alac rate=%u ch=%d bits=%d packets=%zu\n", dec.sampleRate(), dec.channels(), dec.bitDepth(),
                packets.size());

    std::vector<float> got;
    size_t at = 0;
    for (size_t p = 0; p < packets.size(); ++p) {
        const int64_t n = dec.decodePacket(packets[p].data(), packets[p].size(), got);
        if (n != static_cast<int64_t>(packetFrames[p])) {
            std::printf("FAIL packet %zu: decoded %lld frames, expected %zu\n", p, static_cast<long long>(n),
                        packetFrames[p]);
            return 1;
        }
        at += static_cast<size_t>(n);
    }
    if (at != frames || got.size() != frames * 2) {
        std::printf("FAIL total: %zu frames, expected %zu\n", at, frames);
        return 1;
    }

    size_t mismatches = 0;
    for (size_t i = 0; i < frames * 2; ++i) {
        const float expect = static_cast<float>(pcm[i]) / 32768.0f;
        if (got[i] != expect) ++mismatches;
    }
    std::printf("samples=%zu mismatches=%zu\n", frames * 2, mismatches);
    std::printf("%s\n", mismatches == 0 ? "PASS" : "FAIL");
    return mismatches == 0 ? 0 : 1;
}
