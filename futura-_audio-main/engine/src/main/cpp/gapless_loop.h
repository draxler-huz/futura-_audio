// Reads a pre-loaded track forever without gaps or clicks.
#pragma once

#include <cstddef>

namespace futura {

// Seam behaviour:
// The last `seamFrames` of the track are crossfaded (equal power) into the first `seamFrames`.
// Each pass then resumes at frame `seamFrames`, so the loop period is (frames - seamFrames).
// Output never contains a silent buffer, and the wrap point has no discontinuity.
class GaplessLoop {
public:
    // `samples` is interleaved stereo and must stay alive while this loop is in use.
    void reset(const float* samples, size_t frames, size_t seamFrames);
    void rewind();

    // Fills `out` with `numFrames` interleaved stereo frames.
    void read(float* out, size_t numFrames);

private:
    const float* data_ = nullptr;
    size_t frames_ = 0;
    size_t seam_ = 0;
    size_t pos_ = 0;
};

}  // namespace futura
