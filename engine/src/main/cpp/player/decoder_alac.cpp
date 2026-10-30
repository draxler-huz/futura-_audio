// ALAC frame decoder on Apple's open-source ALAC (Apache-2.0, pinned; see STATUS.md).
// Android only: the packets come from NdkMediaExtractor (decoder_mediacodec.cpp).
// UNVERIFIED ON DEVICE: this file has not been compiled against the NDK or run. Its ALAC part was compiled and
// exercised on the host only as far as the host allowed (see the decoder test notes in STATUS.md).
#if defined(__ANDROID__)

#include <cstring>
#include <vector>

#include "ALACBitUtilities.h"
#include "ALACDecoder.h"
#include "decoder_common.h"

namespace futura::player {

struct AppleAlacCodec::Impl {
    ALACDecoder decoder;
    std::vector<uint8_t> outBytes;
    uint32_t sampleRate = 0;
    int32_t channels = 0;
    int32_t bitDepth = 0;
    uint32_t framesPerPacket = 0;
};

AppleAlacCodec::AppleAlacCodec() : impl_(std::make_unique<Impl>()) {}
AppleAlacCodec::~AppleAlacCodec() = default;

ErrorCode AppleAlacCodec::init(const uint8_t* cookie, size_t size) {
    if (!cookie || size < 24) return kUnsupportedFormat;

    // Init() may read the cookie in place, so give it a private copy.
    std::vector<uint8_t> copy(cookie, cookie + size);
    if (impl_->decoder.Init(copy.data(), static_cast<uint32_t>(size)) != 0) return kUnsupportedFormat;

    const ALACSpecificConfig& c = impl_->decoder.mConfig;
    if (c.numChannels < 1 || c.numChannels > 2) return kUnsupportedFormat;
    if (c.bitDepth != 16 && c.bitDepth != 24 && c.bitDepth != 32) return kUnsupportedFormat;

    impl_->sampleRate = c.sampleRate;
    impl_->channels = c.numChannels;
    impl_->bitDepth = c.bitDepth;
    impl_->framesPerPacket = c.frameLength > 0 ? c.frameLength : 4096;
    return kOk;
}

uint32_t AppleAlacCodec::sampleRate() const { return impl_->sampleRate; }
int32_t AppleAlacCodec::channels() const { return impl_->channels; }
int32_t AppleAlacCodec::bitDepth() const { return impl_->bitDepth; }
uint32_t AppleAlacCodec::framesPerPacket() const { return impl_->framesPerPacket; }

int64_t AppleAlacCodec::decodePacket(const uint8_t* packet, size_t size, std::vector<float>& out) {
    if (!packet || size == 0) return 0;

    // The decoder only reads from the bit buffer, so the const cast does not write through it.
    BitBuffer bits;
    BitBufferInit(&bits, const_cast<uint8_t*>(packet), static_cast<uint32_t>(size));

    const uint32_t channels = static_cast<uint32_t>(impl_->channels);
    const uint32_t bytesPerSample = static_cast<uint32_t>(impl_->bitDepth / 8);
    const uint32_t capacity = impl_->framesPerPacket;
    impl_->outBytes.resize(static_cast<size_t>(capacity * channels * bytesPerSample));

    uint32_t produced = 0;
    if (impl_->decoder.Decode(&bits, impl_->outBytes.data(), capacity, channels, &produced) != 0) {
        return kDecodeError;
    }
    if (produced > capacity) return kDecodeError;

    const uint8_t* src = impl_->outBytes.data();
    const size_t base = out.size();
    out.resize(base + static_cast<size_t>(produced) * 2);
    float* dst = out.data() + base;

    for (uint32_t f = 0; f < produced; ++f) {
        for (uint32_t ch = 0; ch < channels; ++ch) {
            const uint8_t* p = src + (static_cast<size_t>(f) * channels + ch) * bytesPerSample;
            float v = 0.0f;
            if (impl_->bitDepth == 16) {
                int16_t s;
                std::memcpy(&s, p, sizeof s);
                v = static_cast<float>(s) / 32768.0f;
            } else if (impl_->bitDepth == 24) {
                int32_t s = static_cast<int32_t>(p[0] | (p[1] << 8) | (p[2] << 16));
                if (s & 0x800000) s |= ~0xFFFFFF;
                v = static_cast<float>(s) / 8388608.0f;
            } else {
                int32_t s;
                std::memcpy(&s, p, sizeof s);
                v = static_cast<float>(s) / 2147483648.0f;
            }
            dst[2 * f + (ch == 0 ? 0 : 1)] = v;
        }
        if (channels == 1) dst[2 * f + 1] = dst[2 * f];
    }
    return static_cast<int64_t>(produced);
}

}  // namespace futura::player

#endif  // __ANDROID__
