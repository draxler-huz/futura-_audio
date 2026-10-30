// Streaming sample-rate converter: cubic (Catmull-Rom) interpolation between stereo float frames.
//
// Decode thread only. It is never called from the audio callback. It may allocate in configure().
//
// Quality: good enough for music at the rates Android devices use. It is NOT a bit-exact or
// windowed-sinc resampler. That is a documented Stage 1 trade-off, see STATUS.md.
// When the input rate equals the output rate, active() is false and the engine copies frames directly.
#pragma once

#include <cstdint>
#include <vector>

namespace futura::player {

class Resampler {
public:
    // Allocates scratch for up to `maxInFrames` input frames per call. Controller thread only.
    void configure(uint32_t inRate, uint32_t outRate, int64_t maxInFrames);

    bool active() const { return inRate_ != outRate_ && inRate_ > 0 && outRate_ > 0; }

    // Forgets history and phase, so the next input starts a new stream (seek or new track).
    void reset();

    // Upper bound on the output frames that process(in) plus flush() can produce for `inFrames` input frames.
    int64_t maxOutputFor(int64_t inFrames) const;

    // Converts `n` input frames. Writes produced frames to `out` (2 floats per frame, at most `maxOut` frames).
    // Returns frames written. Input that cannot yet be interpolated is kept for the next call.
    int64_t process(const float* in, int64_t n, float* out, int64_t maxOut);

    // Emits the tail held back by process(), holding the last real frame after the end of the input.
    int64_t flush(float* out, int64_t maxOut);

private:
    int64_t emit(float* out, int64_t maxOut, int64_t lastUsableIndex);

    uint32_t inRate_ = 0;
    uint32_t outRate_ = 0;
    double step_ = 1.0;        // input frames per output frame
    double t_ = 1.0;           // position of the next output frame, in work_ frame coordinates
    int64_t workFrames_ = 0;   // frames currently held in work_
    int64_t workCapacity_ = 0;
    std::vector<float> work_;  // stereo, interleaved: history plus pending input
};

}  // namespace futura::player
