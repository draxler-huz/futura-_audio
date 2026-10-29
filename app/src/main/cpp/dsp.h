// Real-time DSP blocks. All process methods run on the audio thread and do not allocate.
#pragma once

#include <cstdint>

#include "audio_types.h"

namespace futura {

struct CompressorParams {
    bool enabled = true;
    float thresholdDb = -12.0f;
    float ratio = 4.0f;
    float attackMs = 5.0f;
    float releaseMs = 150.0f;
    float makeupDb = 0.0f;
    // Hard output ceiling. Always applied, even when compression is disabled,
    // so a loud source cannot push the output past this level.
    float ceilingDb = -1.0f;
};

// Feed-forward, stereo-linked peak compressor followed by a hard output ceiling.
class Compressor {
public:
    void configure(const CompressorParams& params, float sampleRate);
    void process(float* interleaved, int32_t frames);

private:
    CompressorParams params_{};
    float ratio_ = 4.0f;
    float attackCoef_ = 0.0f;
    float releaseCoef_ = 0.0f;
    float thresholdLin_ = 1.0f;
    float makeupLin_ = 1.0f;
    float ceilingLin_ = 1.0f;
    float envelope_ = 0.0f;
};

struct EqBand {
    EqType type = EqType::kPeaking;
    float freqHz = 1000.0f;
    float q = 0.707f;
    float gainDb = 0.0f;
};

// Cascade of RBJ biquad sections (transposed direct form II), with separate state per channel.
// With zero bands it is a bypass.
class EqChain {
public:
    void configure(const EqBand* bands, int32_t count, float sampleRate);
    void process(float* interleaved, int32_t frames);

private:
    struct Section {
        double b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0;
        double z1[kChannels] = {0.0, 0.0};
        double z2[kChannels] = {0.0, 0.0};
    };

    Section sections_[kMaxEqBands];
    int32_t count_ = 0;
};

// Per-sample gain ramp.
//  - kLinear interpolates amplitude.
//  - kLogarithmic interpolates in dB, so perceived loudness changes evenly.
//    The start is floored at -60 dB so a ramp from silence is still defined.
class VolumeRamp {
public:
    void reset(float value);
    void setTarget(float target, int32_t frames, VolumeCurve curve);
    float next();

private:
    float value_ = 0.0f;
    float start_ = 0.0f;
    float target_ = 0.0f;
    int32_t total_ = 0;
    int32_t remaining_ = 0;
    VolumeCurve curve_ = VolumeCurve::kLinear;
};

}  // namespace futura
