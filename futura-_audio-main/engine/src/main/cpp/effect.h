// Real-time effect interface. Effects run in the order they are chained.
//
// Stage 0 defines the interface only. No effects exist yet, so nothing is chained.
// Later stages add implementations.
//
// Rules for process(), which runs on the audio thread:
//   - no allocation
//   - no locks (no mutex, no try_lock, no condition variables)
//   - no logging or I/O
//   - parameters arrive through a lock-free queue, never by calling into the effect
#pragma once

#include <cstdint>

namespace futura {

class Effect {
public:
    virtual ~Effect() = default;

    // Processes `frames` interleaved stereo float frames in place.
    virtual void process(float* buffer, int32_t frames) = 0;
};

}  // namespace futura
