#include "tone_engine.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <thread>
#include <utility>

namespace futura {
namespace {

constexpr double kTwoPi = 6.28318530717958647692;
constexpr float kRampSeconds = 0.010f;      // fade in and out, so starting and stopping does not click
constexpr float kDefaultFrequencyHz = 440.0f;
constexpr float kDefaultGain = 0.2f;        // about -14 dBFS
constexpr float kMinRampLevel = 0.001f;     // keeps a fade-out from silence well defined

// Maps Oboe's audio API enum to the fixed codes in ToneStreamInfo. Explicit, so it does not depend on enum values.
int32_t audioApiCode(oboe::AudioApi api) {
    switch (api) {
        case oboe::AudioApi::AAudio:
            return 1;
        case oboe::AudioApi::OpenSLES:
            return 2;
        case oboe::AudioApi::Unspecified:
        default:
            return 0;
    }
}

}  // namespace

ToneEngine::ToneEngine() = default;

ToneEngine::~ToneEngine() { close(); }

ErrorCode ToneEngine::open() {
    std::lock_guard<std::mutex> lock(controlMutex_);
    if (stream_) return kOk;

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Shared);
    builder.setFormat(oboe::AudioFormat::Float);
    builder.setChannelCount(oboe::ChannelCount::Stereo);
    builder.setUsage(oboe::Usage::Media);
    builder.setContentType(oboe::ContentType::Music);
    builder.setDataCallback(this);

    std::shared_ptr<oboe::AudioStream> s;
    if (builder.openStream(s) != oboe::Result::OK || !s) return kOutputError;

    const int32_t rate = s->getSampleRate();
    if (rate <= 0) return kOutputError;

    // Start from known defaults on every open. Queued before the stream starts, so the first callback applies them.
    enqueue(ToneCommand{ToneCommandKind::kSetPlaying, 0.0f});
    enqueue(ToneCommand{ToneCommandKind::kSetFrequency, kDefaultFrequencyHz});
    enqueue(ToneCommand{ToneCommandKind::kSetGain, kDefaultGain});

    info_.sampleRate = rate;
    info_.framesPerBurst = s->getFramesPerBurst();
    info_.audioApi = audioApiCode(s->getAudioApi());
    info_.deviceId = s->getDeviceId();

    streamRate_.store(rate, std::memory_order_release);
    if (s->requestStart() != oboe::Result::OK) {
        info_ = ToneStreamInfo{};
        s->close();
        return kOutputError;
    }
    stream_ = std::move(s);
    return kOk;
}

void ToneEngine::close() {
    std::lock_guard<std::mutex> lock(controlMutex_);
    if (!stream_) return;
    stream_->stop();   // blocks until the callback has returned
    stream_->close();
    stream_.reset();
    info_ = ToneStreamInfo{};
}

void ToneEngine::setPlaying(bool playing) {
    enqueue(ToneCommand{ToneCommandKind::kSetPlaying, playing ? 1.0f : 0.0f});
}

void ToneEngine::setFrequency(float hz) {
    enqueue(ToneCommand{ToneCommandKind::kSetFrequency, std::clamp(hz, 20.0f, 20000.0f)});
}

void ToneEngine::setGain(float linear) {
    enqueue(ToneCommand{ToneCommandKind::kSetGain, std::clamp(linear, 0.0f, 1.0f)});
}

void ToneEngine::enqueue(const ToneCommand& cmd) {
    std::lock_guard<std::mutex> lock(producerMutex_);
    // The audio thread drains the queue on every callback, so a full queue only happens while the stream is
    // stopped. Wait briefly for room, then drop the command rather than block forever.
    for (int attempt = 0; attempt < 100; ++attempt) {
        if (commands_.push(cmd)) return;
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
}

ToneStreamInfo ToneEngine::info() const {
    std::lock_guard<std::mutex> lock(controlMutex_);
    return info_;
}

int32_t ToneEngine::xRunCount() {
    std::lock_guard<std::mutex> lock(controlMutex_);
    if (!stream_) return 0;
    oboe::ResultWithValue<int32_t> xruns = stream_->getXRunCount();
    return xruns.error() == oboe::Result::OK ? xruns.value() : 0;
}

void ToneEngine::applyCommands() {
    // Audio thread. Pops every pending command in order. No locks, no allocation.
    ToneCommand cmd;
    while (commands_.pop(cmd)) {
        switch (cmd.kind) {
            case ToneCommandKind::kSetPlaying:
                playing_ = cmd.value > 0.5f;
                break;
            case ToneCommandKind::kSetFrequency:
                frequency_ = cmd.value;
                break;
            case ToneCommandKind::kSetGain:
                gain_ = cmd.value;
                break;
        }
    }
}

oboe::DataCallbackResult ToneEngine::onAudioReady(oboe::AudioStream* /*stream*/, void* audioData,
                                                  int32_t numFrames) {
    float* out = static_cast<float*>(audioData);
    applyCommands();

    const float sr = static_cast<float>(streamRate_.load(std::memory_order_acquire));
    if (sr <= 0.0f) {
        std::memset(out, 0, static_cast<size_t>(numFrames) * 2 * sizeof(float));
        return oboe::DataCallbackResult::Continue;
    }

    const double increment = kTwoPi * static_cast<double>(frequency_) / static_cast<double>(sr);
    // Scale the step to the level being ramped, so every fade lasts kRampSeconds whatever the gain is.
    const float step = std::max({gain_, amplitude_, kMinRampLevel}) / (kRampSeconds * sr);
    const float target = playing_ ? gain_ : 0.0f;

    for (int32_t i = 0; i < numFrames; ++i) {
        // Move the amplitude towards its target, one step per frame.
        if (amplitude_ < target) {
            amplitude_ = std::min(target, amplitude_ + step);
        } else if (amplitude_ > target) {
            amplitude_ = std::max(target, amplitude_ - step);
        }

        const float sample = amplitude_ * static_cast<float>(std::sin(phase_));
        out[2 * i] = sample;
        out[2 * i + 1] = sample;

        phase_ += increment;
        if (phase_ >= kTwoPi) phase_ -= kTwoPi;
    }
    return oboe::DataCallbackResult::Continue;
}

}  // namespace futura
