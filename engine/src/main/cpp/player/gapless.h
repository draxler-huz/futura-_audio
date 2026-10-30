// Gapless trimming: which raw frames of a decoded file are part of the audible track.
//
// A file's raw timeline includes encoder delay and padding (MP3 LAME/Xing, AAC iTunes gapless, Opus pre-skip).
// The audible track is the raw frames in [begin, end). Everything outside that window is dropped.
// Pure functions, no state, so they are unit tested on the host.
#pragma once

#include <cstdint>
#include <limits>

#include "decoder.h"

namespace futura::player {

struct TrimWindow {
    int64_t begin = 0;  // first raw frame kept
    int64_t end = std::numeric_limits<int64_t>::max();  // one past the last raw frame kept

    bool endKnown() const { return end != std::numeric_limits<int64_t>::max(); }
};

// Builds the window from the decoder's facts. The end is only known when totalFrames is known.
// Padding is trimmed only when the length is known, because the padding is measured from the end.
inline TrimWindow trimWindowFor(const TrackInfo& info) {
    TrimWindow w;
    w.begin = info.skipFrames > 0 ? info.skipFrames : 0;
    if (info.totalFrames >= 0) {
        int64_t end = info.totalFrames - (info.trimEndFrames > 0 ? info.trimEndFrames : 0);
        if (end < w.begin) end = w.begin;  // degenerate file: nothing audible, never a negative length
        w.end = end;
    }
    return w;
}

// Audible length in raw frames, or -1 when the source length is unknown.
inline int64_t audibleRawFrames(const TrimWindow& w) {
    return w.endKnown() ? w.end - w.begin : -1;
}

}  // namespace futura::player
