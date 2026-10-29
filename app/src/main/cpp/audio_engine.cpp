#include "audio_engine.h"

#include <unistd.h>

#include <algorithm>
#include <cstring>
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
    // Publish initial defaults so the first audio callback applies a defined state:
    // volume 0 (silent until set), compressor at its defaults, no EQ bands.
    staged_.version = 1;
    staged_.volumeSeq = 1;
    staged_.compressorSeq = 1;
    staged_.eqSeq = 1;
    stagedVersion_.store(1, std::memory_order_release);
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

void AudioEngine::setVolume(float target, int32_t rampMs, VolumeCurve curve) {
    std::lock_guard<std::mutex> lock(stagedMutex_);
    staged_.volumeTarget = std::clamp(target, 0.0f, 1.0f);
    staged_.volumeRampMs = std::max(rampMs, 0);
    staged_.volumeCurve = curve;
    staged_.volumeSeq = ++staged_.version;
    stagedVersion_.store(staged_.version, std::memory_order_release);
}

void AudioEngine::setCompressor(const CompressorParams& params) {
    std::lock_guard<std::mutex> lock(stagedMutex_);
    staged_.compressor = params;
    staged_.compressorSeq = ++staged_.version;
    stagedVersion_.store(staged_.version, std::memory_order_release);
}

void AudioEngine::setEq(const EqBand* bands, int32_t count) {
    std::lock_guard<std::mutex> lock(stagedMutex_);
    staged_.eqCount = std::clamp(count, 0, static_cast<int32_t>(kMaxEqBands));
    for (int32_t i = 0; i < staged_.eqCount; ++i) {
        staged_.eqBands[i] = bands[i];
    }
    staged_.eqSeq = ++staged_.version;
    stagedVersion_.store(staged_.version, std::memory_order_release);
}

void AudioEngine::applyStaged() {
    if (stagedVersion_.load(std::memory_order_acquire) == appliedVersion_) return;

    // Never block the audio thread. If the controller holds the lock, pick the change up on the next callback.
    std::unique_lock<std::mutex> lock(stagedMutex_, std::try_to_lock);
    if (!lock.owns_lock()) return;

    const float sr = static_cast<float>(sampleRate_);

    if (staged_.volumeSeq != appliedVolumeSeq_) {
        const int32_t rampFrames = static_cast<int32_t>(static_cast<float>(staged_.volumeRampMs) * sr / 1000.0f);
        if (appliedVolumeSeq_ == 0) {
            // First configuration: set the level without a ramp from the default.
            ramp_.reset(staged_.volumeTarget);
        } else {
            ramp_.setTarget(staged_.volumeTarget, rampFrames, staged_.volumeCurve);
        }
        appliedVolumeSeq_ = staged_.volumeSeq;
    }
    if (staged_.compressorSeq != appliedCompressorSeq_) {
        compressor_.configure(staged_.compressor, sr);
        appliedCompressorSeq_ = staged_.compressorSeq;
    }
    if (staged_.eqSeq != appliedEqSeq_) {
        eq_.configure(staged_.eqBands, staged_.eqCount, sr);
        appliedEqSeq_ = staged_.eqSeq;
    }
    appliedVersion_ = staged_.version;
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* /*stream*/, void* audioData,
                                                   int32_t numFrames) {
    float* out = static_cast<float*>(audioData);
    applyStaged();

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
