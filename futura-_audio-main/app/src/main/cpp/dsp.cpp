#include "dsp.h"

#include <algorithm>
#include <cmath>

namespace futura {
namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr float kDenormalFloor = 1e-20f;
constexpr float kLogFloor = 0.001f;  // -60 dB

float dbToLinear(float db) { return std::pow(10.0f, db / 20.0f); }

}  // namespace

// ---------------------------------------------------------------- Compressor

void Compressor::configure(const CompressorParams& p, float sampleRate) {
    params_ = p;
    ratio_ = std::clamp(p.ratio, 1.0f, 100.0f);
    const float sr = std::max(sampleRate, 1.0f);
    attackCoef_ = std::exp(-1.0f / (std::max(p.attackMs, 0.01f) * 0.001f * sr));
    releaseCoef_ = std::exp(-1.0f / (std::max(p.releaseMs, 1.0f) * 0.001f * sr));
    thresholdLin_ = std::max(dbToLinear(p.thresholdDb), 1e-6f);
    makeupLin_ = dbToLinear(p.makeupDb);
    ceilingLin_ = dbToLinear(std::min(p.ceilingDb, 0.0f));
}

void Compressor::process(float* x, int32_t frames) {
    const bool compress = params_.enabled;
    const float makeup = compress ? makeupLin_ : 1.0f;
    for (int32_t i = 0; i < frames; ++i) {
        float& l = x[i * kChannels];
        float& r = x[i * kChannels + 1];

        // Stereo-linked peak detector: both channels share one gain, so the stereo image stays intact.
        const float peak = std::max(std::fabs(l), std::fabs(r));
        const float coef = peak > envelope_ ? attackCoef_ : releaseCoef_;
        envelope_ = coef * envelope_ + (1.0f - coef) * peak;
        if (envelope_ < kDenormalFloor) envelope_ = 0.0f;

        float gain = makeup;
        if (compress && envelope_ > thresholdLin_) {
            // Gain reduction in dB is over * (1 - 1/ratio), which is the same as this power law.
            gain *= std::pow(envelope_ / thresholdLin_, -(1.0f - 1.0f / ratio_));
        }

        l = std::clamp(l * gain, -ceilingLin_, ceilingLin_);
        r = std::clamp(r * gain, -ceilingLin_, ceilingLin_);
    }
}

// ---------------------------------------------------------------- EqChain

void EqChain::configure(const EqBand* bands, int32_t count, float sampleRate) {
    count_ = std::clamp(count, 0, static_cast<int32_t>(kMaxEqBands));
    const double fs = std::max(static_cast<double>(sampleRate), 1.0);

    for (int32_t i = 0; i < count_; ++i) {
        const EqBand& band = bands[i];
        const double f = std::clamp(static_cast<double>(band.freqHz), 20.0, 0.45 * fs);
        const double q = std::clamp(static_cast<double>(band.q), 0.1, 20.0);
        const double A = std::pow(10.0, static_cast<double>(band.gainDb) / 40.0);
        const double w0 = 2.0 * kPi * f / fs;
        const double cs = std::cos(w0);
        const double alpha = std::sin(w0) / (2.0 * q);

        double b0 = 1.0, b1 = 0.0, b2 = 0.0, a0 = 1.0, a1 = 0.0, a2 = 0.0;
        switch (band.type) {
            case EqType::kPeaking:
                b0 = 1.0 + alpha * A;
                b1 = -2.0 * cs;
                b2 = 1.0 - alpha * A;
                a0 = 1.0 + alpha / A;
                a1 = -2.0 * cs;
                a2 = 1.0 - alpha / A;
                break;
            case EqType::kLowShelf: {
                const double sa = 2.0 * std::sqrt(A) * alpha;
                b0 = A * ((A + 1.0) - (A - 1.0) * cs + sa);
                b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cs);
                b2 = A * ((A + 1.0) - (A - 1.0) * cs - sa);
                a0 = (A + 1.0) + (A - 1.0) * cs + sa;
                a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cs);
                a2 = (A + 1.0) + (A - 1.0) * cs - sa;
                break;
            }
            case EqType::kHighShelf: {
                const double sa = 2.0 * std::sqrt(A) * alpha;
                b0 = A * ((A + 1.0) + (A - 1.0) * cs + sa);
                b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cs);
                b2 = A * ((A + 1.0) + (A - 1.0) * cs - sa);
                a0 = (A + 1.0) - (A - 1.0) * cs + sa;
                a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cs);
                a2 = (A + 1.0) - (A - 1.0) * cs - sa;
                break;
            }
        }

        Section& s = sections_[i];
        s.b0 = b0 / a0;
        s.b1 = b1 / a0;
        s.b2 = b2 / a0;
        s.a1 = a1 / a0;
        s.a2 = a2 / a0;
    }
}

void EqChain::process(float* x, int32_t frames) {
    if (count_ == 0) return;
    for (int32_t i = 0; i < frames; ++i) {
        for (int32_t c = 0; c < kChannels; ++c) {
            double v = x[i * kChannels + c];
            for (int32_t s = 0; s < count_; ++s) {
                Section& sec = sections_[s];
                const double out = sec.b0 * v + sec.z1[c];
                sec.z1[c] = sec.b1 * v - sec.a1 * out + sec.z2[c];
                sec.z2[c] = sec.b2 * v - sec.a2 * out;
                v = out;
            }
            x[i * kChannels + c] = static_cast<float>(v);
        }
    }
}

// ---------------------------------------------------------------- VolumeRamp

void VolumeRamp::reset(float value) {
    value_ = std::clamp(value, 0.0f, 1.0f);
    target_ = value_;
    total_ = 0;
    remaining_ = 0;
}

void VolumeRamp::setTarget(float target, int32_t frames, VolumeCurve curve) {
    target_ = std::clamp(target, 0.0f, 1.0f);
    curve_ = curve;
    if (frames <= 0) {
        value_ = target_;
        total_ = 0;
        remaining_ = 0;
        return;
    }
    start_ = value_;
    total_ = frames;
    remaining_ = frames;
}

float VolumeRamp::next() {
    if (remaining_ == 0) return value_;

    --remaining_;
    if (remaining_ == 0) {
        // Land exactly on the target so the ramp never leaves a residual offset.
        value_ = target_;
        return value_;
    }

    const float t = static_cast<float>(total_ - remaining_) / static_cast<float>(total_);
    if (curve_ == VolumeCurve::kLinear) {
        value_ = start_ + (target_ - start_) * t;
    } else {
        const float fromDb = 20.0f * std::log10(std::max(start_, kLogFloor));
        const float toDb = 20.0f * std::log10(std::max(target_, kLogFloor));
        value_ = std::pow(10.0f, (fromDb + (toDb - fromDb) * t) / 20.0f);
    }
    return value_;
}

}  // namespace futura
