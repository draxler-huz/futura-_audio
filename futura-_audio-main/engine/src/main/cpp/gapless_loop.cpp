#include "gapless_loop.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace futura {
namespace {
constexpr float kHalfPi = 1.57079632679f;
}  // namespace

void GaplessLoop::reset(const float* samples, size_t frames, size_t seamFrames) {
    data_ = frames > 0 ? samples : nullptr;
    frames_ = frames;
    // Keep the seam well inside the track so the head and tail regions never overlap.
    seam_ = std::min(seamFrames, frames / 4);
    rewind();
}

void GaplessLoop::rewind() { pos_ = 0; }

void GaplessLoop::read(float* out, size_t numFrames) {
    if (data_ == nullptr || frames_ == 0) {
        std::memset(out, 0, numFrames * 2 * sizeof(float));
        return;
    }

    const size_t seamStart = frames_ - seam_;
    for (size_t i = 0; i < numFrames; ++i) {
        const float* cur = data_ + pos_ * 2;
        float l = cur[0];
        float r = cur[1];

        if (seam_ > 0 && pos_ >= seamStart) {
            // Equal-power crossfade: tail fades out (cos) while head fades in (sin).
            const size_t k = pos_ - seamStart;
            const float theta = kHalfPi * (static_cast<float>(k) / static_cast<float>(seam_));
            const float wTail = std::cos(theta);
            const float wHead = std::sin(theta);
            const float* head = data_ + k * 2;
            l = l * wTail + head[0] * wHead;
            r = r * wTail + head[1] * wHead;
        }

        out[2 * i] = l;
        out[2 * i + 1] = r;

        if (++pos_ >= frames_) pos_ = seam_;
    }
}

}  // namespace futura
