#include "resampler.h"

#include <cmath>
#include <cstring>

namespace futura::player {
namespace {

// Keeps a little history before the first real sample, so the first outputs have neighbours to interpolate.
// Work starts with one silent frame and a read position of 1.0, which leaves room for p0.
constexpr int64_t kLeadFrames = 1;
// Slack for leftover input that could not yet be interpolated.
constexpr int64_t kSlackFrames = 8;

inline float catmullRom(float p0, float p1, float p2, float p3, float f) {
    return p1 + 0.5f * f *
                    (p2 - p0 + f * (2.0f * p0 - 5.0f * p1 + 4.0f * p2 - p3 + f * (3.0f * (p1 - p2) + p3 - p0)));
}

}  // namespace

void Resampler::configure(uint32_t inRate, uint32_t outRate, int64_t maxInFrames) {
    inRate_ = inRate;
    outRate_ = outRate;
    step_ = (outRate_ > 0) ? static_cast<double>(inRate_) / static_cast<double>(outRate_) : 1.0;
    workCapacity_ = kLeadFrames + maxInFrames + kSlackFrames + 4;
    work_.assign(static_cast<size_t>(workCapacity_) * 2, 0.0f);
    reset();
}

void Resampler::reset() {
    std::memset(work_.data(), 0, work_.size() * sizeof(float));
    workFrames_ = kLeadFrames;
    t_ = static_cast<double>(kLeadFrames);
}

int64_t Resampler::maxOutputFor(int64_t inFrames) const {
    if (!active()) return inFrames;
    // Outputs per input frame is outRate / inRate. Add margin for the held-back frames and rounding.
    return static_cast<int64_t>(std::ceil((static_cast<double>(inFrames) + kLeadFrames + kSlackFrames) /
                                          step_)) + 4;
}

int64_t Resampler::process(const float* in, int64_t n, float* out, int64_t maxOut) {
    if (n <= 0) return 0;
    // Append the new input after the held-back frames. Leftover is bounded by kSlackFrames, see emit().
    if (workFrames_ + n > workCapacity_) {
        // Caller respected configure()'s maxInFrames. Keep the newest frames rather than overflow.
        const int64_t drop = workFrames_ + n - workCapacity_;
        if (drop >= workFrames_) return 0;  // cannot happen with sane sizing; refuse instead of corrupting
        std::memmove(work_.data(), work_.data() + drop * 2, static_cast<size_t>(workFrames_ - drop) * 2 * sizeof(float));
        workFrames_ -= drop;
        t_ -= static_cast<double>(drop);
        if (t_ < 1.0) t_ = 1.0;
    }
    std::memcpy(work_.data() + workFrames_ * 2, in, static_cast<size_t>(n) * 2 * sizeof(float));
    workFrames_ += n;

    // The last real frame is workFrames_ - 1. Catmull-Rom needs p2 = floor(t) + 1, so require that to exist.
    return emit(out, maxOut, workFrames_ - 2);
}

int64_t Resampler::flush(float* out, int64_t maxOut) {
    // Two frames of silence let the final real frames be interpolated. Outputs past the real end are not emitted.
    const int64_t realLast = workFrames_ - 1;
    if (realLast < 0 || workFrames_ + 2 > workCapacity_) return 0;
    // Hold the last real frame. Silence here would pull the final outputs towards zero.
    for (int64_t k = 0; k < 2; ++k) {
        std::memcpy(work_.data() + (workFrames_ + k) * 2, work_.data() + realLast * 2, 2 * sizeof(float));
    }
    workFrames_ += 2;
    return emit(out, maxOut, realLast);
}

// Emits every output whose four-point neighbourhood ends at or before `lastUsableIndex`, then discards input
// that no later output needs. Leftover input stays in work_ for the next call.
int64_t Resampler::emit(float* out, int64_t maxOut, int64_t lastUsableIndex) {
    int64_t written = 0;
    while (written < maxOut) {
        const int64_t i = static_cast<int64_t>(std::floor(t_));
        if (i + 1 > lastUsableIndex) break;  // needs p2 = i + 1, which is not available yet
        const float f = static_cast<float>(t_ - static_cast<double>(i));
        const float* p0 = work_.data() + (i - 1) * 2;
        const float* p1 = work_.data() + i * 2;
        const float* p2 = work_.data() + (i + 1) * 2;
        const float* p3 = work_.data() + (i + 2) * 2;
        out[written * 2] = catmullRom(p0[0], p1[0], p2[0], p3[0], f);
        out[written * 2 + 1] = catmullRom(p0[1], p1[1], p2[1], p3[1], f);
        ++written;
        t_ += step_;
    }

    // Drop frames before floor(t) - 1. Keep the rest, which the next call needs.
    const int64_t keepFrom = static_cast<int64_t>(std::floor(t_)) - 1;
    if (keepFrom > 0 && keepFrom <= workFrames_) {
        std::memmove(work_.data(), work_.data() + keepFrom * 2,
                     static_cast<size_t>(workFrames_ - keepFrom) * 2 * sizeof(float));
        workFrames_ -= keepFrom;
        t_ -= static_cast<double>(keepFrom);
    }
    return written;
}

}  // namespace futura::player
