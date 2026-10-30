#include "audio_engine.h"

#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <thread>
#include <utility>

#include "track_loader.h"

namespace futura {
namespace {

// Closes the descriptor on scope exit. loadFile takes ownership of the fd.
struct FdCloser {
    int fd;
    ~FdCloser() {
        if (fd >= 0) ::close(fd);
    }
};

}  // namespace

AudioEngine::AudioEngine() {
    // Queue the defaults so the first audio callback applies a defined state:
    // volume 0 (silent until set), compressor at its defaults, no EQ bands.
    ParamCommand volume;
    volume.kind = ParamCommand::Kind::kVolume;
    volume.volumeTarget = 0.0f;
    enqueue(volume);

    ParamCommand compressor;
    compressor.kind = ParamCommand::Kind::kCompressor;
    enqueue(compressor);

    ParamCommand eq;
    eq.kind = ParamCommand::Kind::kEq;
    eq.eqCount = 0;
    enqueue(eq);
}

AudioEngine::~AudioEngine() {
    std::lock_guard<std::mutex> lock(controlMutex_);
    stopLocked();
    if (stream_) {
        stream_->close();
        stream_.reset();
    }
}

uint32_t AudioEngine::sampleRate() const {
    std::lock_guard<std::mutex> lock(controlMutex_);
    return sampleRate_;
}

ErrorCode AudioEngine::ensureStreamLocked() {
    if (stream_) return kOk;

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Exclusive);
    builder.setFormat(oboe::AudioFormat::Float);
    builder.setChannelCount(oboe::ChannelCount::Stereo);
    builder.setUsage(oboe::Usage::Alarm);
    builder.setContentType(oboe::ContentType::Music);
    builder.setDataCallback(this);

    std::shared_ptr<oboe::AudioStream> s;
    oboe::Result r = builder.openStream(s);
    if (r != oboe::Result::OK) {
        // Exclusive mode is not available on every device. Fall back to shared.
        builder.setSharingMode(oboe::SharingMode::Shared);
        r = builder.openStream(s);
    }
    if (r != oboe::Result::OK || !s) return kOutputError;

    // Two bursts of buffering: the lowest latency that stays glitch-free on most hardware.
    s->setBufferSizeInFrames(s->getFramesPerBurst() * 2);

    const int32_t rate = s->getSampleRate();
    if (rate <= 0) return kOutputError;
    sampleRate_ = static_cast<uint32_t>(rate);
    streamRate_.store(sampleRate_, std::memory_order_release);
    stream_ = std::move(s);
    return kOk;
}

ErrorCode AudioEngine::loadFile(int fd, int64_t offset, int64_t length) {
    FdCloser closer{fd};
    std::lock_guard<std::mutex> lock(controlMutex_);

    // Invariant: the stream runs only while playing_ is true. Stopping it first means
    // the audio thread cannot touch track_ or loop_ while they are replaced.
    stopLocked();

    ErrorCode ec = ensureStreamLocked();
    if (ec != kOk) return ec;

    PcmBuffer decoded;
    ec = decodeTrack(fd, offset, length, sampleRate_, decoded);
    if (ec != kOk) return ec;

    track_ = std::move(decoded);
    const size_t seam = static_cast<size_t>(kLoopSeamSeconds * static_cast<float>(sampleRate_));
    loop_.reset(track_.samples.data(), track_.frames(), seam);
    return kOk;
}

ErrorCode AudioEngine::start() {
    std::lock_guard<std::mutex> lock(controlMutex_);
    if (track_.frames() == 0) return kInvalidState;

    const ErrorCode ec = ensureStreamLocked();
    if (ec != kOk) return ec;
    if (playing_.load(std::memory_order_acquire)) return kOk;

    loop_.rewind();
    paused_.store(false, std::memory_order_release);
    playing_.store(true, std::memory_order_release);
    if (stream_->requestStart() != oboe::Result::OK) {
        playing_.store(false, std::memory_order_release);
        return kOutputError;
    }
    return kOk;
}

void AudioEngine::pause() { paused_.store(true, std::memory_order_release); }

void AudioEngine::resume() { paused_.store(false, std::memory_order_release); }

void AudioEngine::stop() {
    std::lock_guard<std::mutex> lock(controlMutex_);
    stopLocked();
}

void AudioEngine::stopLocked() {
    if (!playing_.exchange(false, std::memory_order_acq_rel)) return;
    // stop() blocks until the callback has returned, so the audio thread is idle afterwards.
    if (stream_) stream_->stop();
}

void AudioEngine::enqueue(const ParamCommand& cmd) {
    std::lock_guard<std::mutex> lock(producerMutex_);
    // The audio thread drains the queue on every callback. A full queue therefore only happens while the
    // stream is stopped. Wait briefly for room, then drop the command rather than block forever.
    for (int attempt = 0; attempt < 100; ++attempt) {
        if (params_.push(cmd)) return;
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
}

void AudioEngine::setVolume(float target, int32_t rampMs, VolumeCurve curve) {
    ParamCommand cmd;
    cmd.kind = ParamCommand::Kind::kVolume;
    cmd.volumeTarget = std::clamp(target, 0.0f, 1.0f);
    cmd.volumeRampMs = std::max(rampMs, 0);
    cmd.volumeCurve = curve;
    enqueue(cmd);
}

void AudioEngine::setCompressor(const CompressorParams& params) {
    ParamCommand cmd;
    cmd.kind = ParamCommand::Kind::kCompressor;
    cmd.compressor = params;
    enqueue(cmd);
}

void AudioEngine::setEq(const EqBand* bands, int32_t count) {
    ParamCommand cmd;
    cmd.kind = ParamCommand::Kind::kEq;
    cmd.eqCount = std::clamp(count, 0, static_cast<int32_t>(kMaxEqBands));
    for (int32_t i = 0; i < cmd.eqCount; ++i) {
        cmd.eqBands[i] = bands[i];
    }
    enqueue(cmd);
}

void AudioEngine::applyParams() {
    // Audio thread. Pops every pending change in the order it was queued. No locks, no allocation.
    const float sr = static_cast<float>(streamRate_.load(std::memory_order_acquire));

    ParamCommand cmd;
    while (params_.pop(cmd)) {
        switch (cmd.kind) {
            case ParamCommand::Kind::kVolume: {
                const int32_t rampFrames =
                    static_cast<int32_t>(static_cast<float>(cmd.volumeRampMs) * sr / 1000.0f);
                if (!volumeConfigured_) {
                    // The first volume setting starts from silence, so a fade-in ramps up from 0.
                    // With rampMs = 0 the target applies immediately.
                    ramp_.reset(0.0f);
                    volumeConfigured_ = true;
                }
                ramp_.setTarget(cmd.volumeTarget, rampFrames, cmd.volumeCurve);
                break;
            }
            case ParamCommand::Kind::kCompressor:
                compressor_.configure(cmd.compressor, sr);
                break;
            case ParamCommand::Kind::kEq:
                eq_.configure(cmd.eqBands, cmd.eqCount, sr);
                break;
        }
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* /*stream*/, void* audioData,
                                                   int32_t numFrames) {
    float* out = static_cast<float*>(audioData);
    applyParams();

    if (!playing_.load(std::memory_order_acquire) || paused_.load(std::memory_order_acquire)) {
        std::memset(out, 0, static_cast<size_t>(numFrames) * kChannels * sizeof(float));
        return oboe::DataCallbackResult::Continue;
    }

    loop_.read(out, static_cast<size_t>(numFrames));
    eq_.process(out, numFrames);

    for (int32_t i = 0; i < numFrames; ++i) {
        const float g = ramp_.next();
        out[i * kChannels] *= g;
        out[i * kChannels + 1] *= g;
    }

    compressor_.process(out, numFrames);
    return oboe::DataCallbackResult::Continue;
}

}  // namespace futura
