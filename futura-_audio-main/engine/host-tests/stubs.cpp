// Host-only: implements the stub stream factory and a fake decoder (the real one needs the NDK).
#include <cmath>
#include "oboe/Oboe.h"
#include "track_loader.h"

namespace oboe {
namespace {
class StubStream : public AudioStream {};
}  // namespace
Result AudioStreamBuilder::openStream(std::shared_ptr<AudioStream>& out) {
    out = std::make_shared<StubStream>();
    return Result::OK;
}
}  // namespace oboe

namespace futura {
ErrorCode decodeTrack(int, int64_t, int64_t, uint32_t dstRate, PcmBuffer& out) {
    // Two seconds of a 220 Hz stereo sine, so the alarm path has real audio to play.
    out.sampleRate = dstRate;
    out.samples.resize(static_cast<size_t>(dstRate) * 2 * 2);
    for (size_t i = 0; i < out.frames(); ++i) {
        const float v = 0.5f * std::sin(2.0 * 3.14159265358979 * 220.0 * i / dstRate);
        out.samples[2 * i] = v;
        out.samples[2 * i + 1] = v;
    }
    return kOk;
}
}  // namespace futura
