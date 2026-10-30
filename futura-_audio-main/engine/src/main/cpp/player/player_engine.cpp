#include "player_engine.h"

#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <utility>

namespace futura::player {
namespace {

// Closes the descriptor on scope exit. Every public load call takes ownership of its fd.
struct FdCloser {
    int fd;
    ~FdCloser() {
        if (fd >= 0) ::close(fd);
    }
};

constexpr int kPollMillis = 2;
constexpr int kPrefillPollLimit = 500;  // about 1 second

}  // namespace

// ---------------------------------------------------------------------------------------------------------------
// PlayerGain (audio thread)
// ---------------------------------------------------------------------------------------------------------------

void PlayerGain::setTarget(float target, int64_t rampFrames) {
    target_ = target;
    if (rampFrames <= 0) {
        current_ = target;
        step_ = 0.0f;
        remaining_ = 0;
    } else {
        step_ = (target - current_) / static_cast<float>(rampFrames);
        remaining_ = rampFrames;
    }
}

void PlayerGain::process(float* buffer, int32_t frames) {
    for (int32_t i = 0; i < frames; ++i) {
        if (remaining_ > 0) {
            current_ += step_;
            if (--remaining_ == 0) current_ = target_;
        }
        buffer[i * 2] *= current_;
        buffer[i * 2 + 1] *= current_;
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Construction, open and close (controller)
// ---------------------------------------------------------------------------------------------------------------

PlayerEngine::PlayerEngine() {
    tap_.allocate(kTapFrames);
}

PlayerEngine::~PlayerEngine() { close(); }

ErrorCode PlayerEngine::open() {
    std::lock_guard<std::mutex> lock(controlMutex_);
    return ensureStreamLocked();
}

void PlayerEngine::close() {
    std::lock_guard<std::mutex> lock(controlMutex_);
    if (!stream_) return;
    stopStreamLocked();
    stopDecodeThread();
    {
        std::lock_guard<std::mutex> slotLock(slotMutex_);
        for (Slot& s : slots_) releaseSlotLocked(s);
    }
    stream_->close();
    stream_.reset();
    outRate_.store(0, std::memory_order_release);
}

ErrorCode PlayerEngine::ensureStreamLocked() {
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

    outRate_.store(rate, std::memory_order_release);
    stream_ = std::move(s);
    startDecodeThread();
    return kOk;
}

void PlayerEngine::startDecodeThread() {
    decodeRunning_.store(true, std::memory_order_release);
    decodeThread_ = std::thread([this] { decodeLoop(); });
}

void PlayerEngine::stopDecodeThread() {
    decodeRunning_.store(false, std::memory_order_release);
    if (decodeThread_.joinable()) decodeThread_.join();
}

// ---------------------------------------------------------------------------------------------------------------
// Loading (controller)
// ---------------------------------------------------------------------------------------------------------------

void PlayerEngine::releaseSlotLocked(Slot& s) {
    s.state.store(SlotState::kEmpty, std::memory_order_release);
    s.eof.store(false, std::memory_order_relaxed);
    s.decoder.reset();
    s.ring.resetIndices();
}

ErrorCode PlayerEngine::attachLocked(Slot& s, std::unique_ptr<Decoder> decoder, const TrackInfo& info) {
    const int64_t outRate = outRate_.load(std::memory_order_acquire);
    if (outRate <= 0) return kOutputError;

    const size_t capacity = static_cast<size_t>(kRingSeconds * static_cast<double>(outRate));
    if (s.ring.capacity() != capacity) {
        s.ring.allocate(capacity);
    } else {
        s.ring.resetIndices();
    }

    s.resampler.configure(info.sampleRate, static_cast<uint32_t>(outRate), kRawChunkFrames);
    s.maxOutPerChunk = s.resampler.active() ? s.resampler.maxOutputFor(kRawChunkFrames) : kRawChunkFrames;
    s.rawBuf.resize(static_cast<size_t>(kRawChunkFrames) * 2);
    s.resampleBuf.resize(static_cast<size_t>(s.maxOutPerChunk) * 2 + 16);

    s.info = info;
    s.window = trimWindowFor(info);
    const int64_t audible = audibleRawFrames(s.window);
    s.durationOutFrames = audible >= 0 ? audible * outRate / static_cast<int64_t>(info.sampleRate) : -1;
    s.rawNext = 0;
    s.eof.store(false, std::memory_order_relaxed);
    s.decoder = std::move(decoder);
    return kOk;
}

ErrorCode PlayerEngine::repositionLocked(Slot& s, int64_t outFrame) {
    if (!s.decoder) return kInvalidState;
    const int64_t outRate = outRate_.load(std::memory_order_acquire);
    // Output frame -> raw frame: scale by the source rate, then shift past the trimmed start.
    const double rawOffset = static_cast<double>(outFrame) * static_cast<double>(s.info.sampleRate) /
                             static_cast<double>(outRate);
    int64_t target = s.window.begin + static_cast<int64_t>(std::llround(rawOffset));
    if (s.window.endKnown() && target > s.window.end) target = s.window.end;
    const ErrorCode ec = s.decoder->seekFrame(target);
    if (ec != kOk) return ec;
    s.ring.resetIndices();
    s.resampler.reset();
    s.rawNext = target;
    s.eof.store(false, std::memory_order_relaxed);
    return kOk;
}

ErrorCode PlayerEngine::loadCurrent(int fd, int64_t offset, int64_t length, int64_t startMs) {
    FdCloser closer{fd};
    std::lock_guard<std::mutex> lock(controlMutex_);
    stopStreamLocked();
    ErrorCode ec = ensureStreamLocked();
    if (ec != kOk) return ec;

    std::unique_ptr<Decoder> decoder;
    TrackInfo info;
    ec = createDecoder(fd, offset, length, decoder, info);
    if (ec != kOk) return ec;

    playing_.store(false, std::memory_order_release);
    paused_.store(false, std::memory_order_release);
    ended_.store(false, std::memory_order_release);

    std::lock_guard<std::mutex> slotLock(slotMutex_);
    for (Slot& s : slots_) releaseSlotLocked(s);  // the stream is stopped, so no reader exists

    Slot& cur = slots_[0];
    ec = attachLocked(cur, std::move(decoder), info);
    if (ec != kOk) return ec;

    const int64_t outRate = outRate_.load(std::memory_order_acquire);
    const int64_t startOut = std::max<int64_t>(0, startMs) * outRate / 1000;
    if (startOut > 0) {
        ec = repositionLocked(cur, startOut);
        if (ec != kOk) {
            releaseSlotLocked(cur);
            return ec;
        }
    }
    active_.store(0, std::memory_order_release);
    positionOut_.store(startOut, std::memory_order_release);
    cur.state.store(SlotState::kLoaded, std::memory_order_release);
    return kOk;
}

ErrorCode PlayerEngine::loadNext(int fd, int64_t offset, int64_t length) {
    FdCloser closer{fd};
    std::lock_guard<std::mutex> lock(controlMutex_);
    if (!stream_) return kInvalidState;

    const int32_t cur = active_.load(std::memory_order_acquire);
    const SlotState curState = slots_[cur].state.load(std::memory_order_acquire);
    if (curState != SlotState::kLoaded && curState != SlotState::kPlaying) return kInvalidState;

    std::unique_ptr<Decoder> decoder;
    TrackInfo info;
    const ErrorCode ec = createDecoder(fd, offset, length, decoder, info);
    if (ec != kOk) return ec;

    std::lock_guard<std::mutex> slotLock(slotMutex_);
    Slot& target = slots_[1 - cur];
    const SlotState st = target.state.load(std::memory_order_acquire);
    if (st != SlotState::kEmpty && st != SlotState::kDone) return kInvalidState;  // a next track is already queued

    target.decoder.reset();
    const ErrorCode attach = attachLocked(target, std::move(decoder), info);
    if (attach != kOk) return attach;
    target.state.store(SlotState::kLoaded, std::memory_order_release);  // publish last
    return kOk;
}

void PlayerEngine::clearNext() {
    std::lock_guard<std::mutex> lock(controlMutex_);
    std::lock_guard<std::mutex> slotLock(slotMutex_);
    Slot& target = slots_[1 - active_.load(std::memory_order_acquire)];
    SlotState expected = SlotState::kLoaded;
    // The callback may claim the slot at the same moment. The compare-exchange settles who wins.
    if (target.state.compare_exchange_strong(expected, SlotState::kEmpty, std::memory_order_acq_rel)) {
        target.decoder.reset();
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Transport (controller)
// ---------------------------------------------------------------------------------------------------------------

void PlayerEngine::stopStreamLocked() {
    playing_.store(false, std::memory_order_release);
    if (!streamRunning_) return;
    // stop() blocks until the callback has returned, so the callback is idle afterwards.
    stream_->stop();
    streamRunning_ = false;
}

void PlayerEngine::waitForPrefill(const Slot& s) {
    const size_t needed = static_cast<size_t>(kPrefillSeconds * static_cast<double>(outRate_.load()));
    for (int i = 0; i < kPrefillPollLimit; ++i) {
        if (s.ring.readableFrames() >= needed || s.eof.load(std::memory_order_acquire)) return;
        std::this_thread::sleep_for(std::chrono::milliseconds(kPollMillis));
    }
}

ErrorCode PlayerEngine::start() {
    std::lock_guard<std::mutex> lock(controlMutex_);
    if (!stream_) return kInvalidState;
    Slot& cur = slots_[active_.load(std::memory_order_acquire)];
    if (cur.state.load(std::memory_order_acquire) != SlotState::kLoaded) return kInvalidState;

    waitForPrefill(cur);
    cur.state.store(SlotState::kPlaying, std::memory_order_release);
    paused_.store(false, std::memory_order_release);
    ended_.store(false, std::memory_order_release);
    playing_.store(true, std::memory_order_release);
    if (!streamRunning_) {
        if (stream_->requestStart() != oboe::Result::OK) {
            playing_.store(false, std::memory_order_release);
            return kOutputError;
        }
        streamRunning_ = true;
    }
    return kOk;
}

void PlayerEngine::pause() { paused_.store(true, std::memory_order_release); }

void PlayerEngine::resume() { paused_.store(false, std::memory_order_release); }

void PlayerEngine::stop() {
    std::lock_guard<std::mutex> lock(controlMutex_);
    stopStreamLocked();
    paused_.store(false, std::memory_order_release);
    ended_.store(false, std::memory_order_release);
    if (!stream_) return;
    std::lock_guard<std::mutex> slotLock(slotMutex_);
    Slot& cur = slots_[active_.load(std::memory_order_acquire)];
    const SlotState st = cur.state.load(std::memory_order_acquire);
    if (st == SlotState::kLoaded || st == SlotState::kPlaying) {
        if (repositionLocked(cur, 0) == kOk) {
            positionOut_.store(0, std::memory_order_release);
            cur.state.store(SlotState::kLoaded, std::memory_order_release);
        }
    }
}

ErrorCode PlayerEngine::seekMs(int64_t ms) {
    std::lock_guard<std::mutex> lock(controlMutex_);
    if (!stream_) return kInvalidState;
    Slot& cur = slots_[active_.load(std::memory_order_acquire)];
    const SlotState st = cur.state.load(std::memory_order_acquire);
    if (st != SlotState::kLoaded && st != SlotState::kPlaying) return kInvalidState;

    const bool wasPlaying = playing_.load(std::memory_order_acquire);
    const bool wasPaused = paused_.load(std::memory_order_acquire);
    const bool wasRunning = streamRunning_;

    // Stop the stream so the callback cannot read the ring while the decoder is repositioned.
    stopStreamLocked();

    const int64_t outRate = outRate_.load(std::memory_order_acquire);
    int64_t outFrame = std::max<int64_t>(0, ms) * outRate / 1000;
    if (cur.durationOutFrames >= 0) outFrame = std::min(outFrame, cur.durationOutFrames);

    ErrorCode ec;
    {
        std::lock_guard<std::mutex> slotLock(slotMutex_);
        ec = repositionLocked(cur, outFrame);
    }
    if (ec == kOk) positionOut_.store(outFrame, std::memory_order_release);

    if (wasPlaying) playing_.store(true, std::memory_order_release);
    paused_.store(wasPaused, std::memory_order_release);
    if (wasRunning && wasPlaying) {
        waitForPrefill(cur);
        if (stream_->requestStart() == oboe::Result::OK) {
            streamRunning_ = true;
        } else {
            playing_.store(false, std::memory_order_release);
            return kOutputError;
        }
    }
    return ec;
}

void PlayerEngine::setVolume(float target, int32_t rampMs) {
    PlayerCommand cmd;
    cmd.target = std::clamp(target, 0.0f, 1.0f);
    const int64_t outRate = outRate_.load(std::memory_order_acquire);
    cmd.rampFrames = std::max<int64_t>(0, rampMs) * outRate / 1000;
    enqueue(cmd);
}

void PlayerEngine::enqueue(const PlayerCommand& cmd) {
    std::lock_guard<std::mutex> lock(producerMutex_);
    // The callback drains the queue on every buffer, so a full queue only happens while the stream is stopped.
    for (int attempt = 0; attempt < 100; ++attempt) {
        if (commands_.push(cmd)) return;
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
}

bool PlayerEngine::isPlaying() const {
    return playing_.load(std::memory_order_acquire) && !paused_.load(std::memory_order_acquire);
}

int64_t PlayerEngine::positionMs() const {
    const int64_t outRate = outRate_.load(std::memory_order_acquire);
    if (outRate <= 0) return 0;
    return positionOut_.load(std::memory_order_acquire) * 1000 / outRate;
}

int64_t PlayerEngine::durationMs() const {
    const int64_t outRate = outRate_.load(std::memory_order_acquire);
    const Slot& cur = slots_[active_.load(std::memory_order_acquire)];
    if (outRate <= 0 || cur.durationOutFrames < 0) return -1;
    return cur.durationOutFrames * 1000 / outRate;
}

TrackInfo PlayerEngine::currentTrackInfo() {
    std::lock_guard<std::mutex> lock(controlMutex_);
    return slots_[active_.load(std::memory_order_acquire)].info;
}

PlayerStats PlayerEngine::stats() {
    std::lock_guard<std::mutex> lock(controlMutex_);
    PlayerStats out;
    if (stream_) {
        oboe::ResultWithValue<int32_t> xruns = stream_->getXRunCount();
        out.xruns = xruns.error() == oboe::Result::OK ? xruns.value() : 0;
        out.bufferSizeFrames = stream_->getBufferSizeInFrames();
        out.framesPerBurst = stream_->getFramesPerBurst();
        out.outputSampleRate = stream_->getSampleRate();
        switch (stream_->getAudioApi()) {
            case oboe::AudioApi::AAudio: out.audioApi = 1; break;
            case oboe::AudioApi::OpenSLES: out.audioApi = 2; break;
            default: out.audioApi = 0; break;
        }
    }
    out.callbackNsLast = cbLastNs_.load(std::memory_order_relaxed);
    out.callbackNsAvg = cbAvgNs_.load(std::memory_order_relaxed);
    out.callbackNsMax = cbMaxNs_.load(std::memory_order_relaxed);
    out.underrunFrames = underrunFrames_.load(std::memory_order_relaxed);
    out.callbackCount = cbCount_.load(std::memory_order_relaxed);
    out.decodeErrors = decodeErrors_.load(std::memory_order_relaxed);
    return out;
}

// ---------------------------------------------------------------------------------------------------------------
// Decode thread
// ---------------------------------------------------------------------------------------------------------------

void PlayerEngine::decodeLoop() {
    while (decodeRunning_.load(std::memory_order_acquire)) {
        {
            std::lock_guard<std::mutex> lock(slotMutex_);
            for (Slot& s : slots_) fillSlot(s);
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(kPollMillis));
    }
}

void PlayerEngine::fillSlot(Slot& s) {
    const SlotState st = s.state.load(std::memory_order_acquire);
    if (st == SlotState::kEmpty) return;
    if (st == SlotState::kDone) {
        s.decoder.reset();  // the callback has finished with this slot, so its decoder can go
        return;
    }
    if (!s.decoder || s.eof.load(std::memory_order_acquire)) return;

    // Only decode when the ring can take the largest possible output of one chunk, so a write never comes up short.
    while (s.ring.writableFrames() >= static_cast<size_t>(s.maxOutPerChunk)) {
        if (s.window.endKnown() && s.rawNext >= s.window.end) {
            finishSlot(s);
            return;
        }
        int64_t want = kRawChunkFrames;
        if (s.info.totalFrames >= 0) {
            const int64_t left = s.info.totalFrames - s.rawNext;
            if (left <= 0) {
                finishSlot(s);
                return;
            }
            want = std::min(want, left);
        }

        const int64_t got = s.decoder->decodeFrames(s.rawBuf.data(), want);
        if (got < 0) {
            decodeErrors_.fetch_add(1, std::memory_order_relaxed);
            finishSlot(s);  // end the track rather than stall the queue
            return;
        }
        if (got == 0) {
            finishSlot(s);
            return;
        }

        const int64_t chunkStart = s.rawNext;
        s.rawNext += got;
        const int64_t from = std::max(chunkStart, s.window.begin);
        const int64_t to = s.window.endKnown() ? std::min(s.rawNext, s.window.end) : s.rawNext;
        if (to > from) {
            pushFrames(s, s.rawBuf.data() + (from - chunkStart) * 2, to - from);
        }
    }
}

void PlayerEngine::pushFrames(Slot& s, const float* src, int64_t frames) {
    if (!s.resampler.active()) {
        s.ring.write(src, static_cast<size_t>(frames));
        return;
    }
    const int64_t produced = s.resampler.process(src, frames, s.resampleBuf.data(), s.maxOutPerChunk);
    s.ring.write(s.resampleBuf.data(), static_cast<size_t>(produced));
}

void PlayerEngine::finishSlot(Slot& s) {
    if (s.resampler.active()) {
        const int64_t tail = s.resampler.flush(s.resampleBuf.data(), s.maxOutPerChunk);
        s.ring.write(s.resampleBuf.data(), static_cast<size_t>(tail));
    }
    s.eof.store(true, std::memory_order_release);
}

// ---------------------------------------------------------------------------------------------------------------
// Audio callback. No locks, no allocation, no logging.
// ---------------------------------------------------------------------------------------------------------------

void PlayerEngine::applyCommands() {
    PlayerCommand cmd;
    while (commands_.pop(cmd)) {
        gain_.setTarget(cmd.target, cmd.rampFrames);
    }
}

oboe::DataCallbackResult PlayerEngine::onAudioReady(oboe::AudioStream* /*stream*/, void* audioData,
                                                    int32_t numFrames) {
    const auto t0 = std::chrono::steady_clock::now();
    float* out = static_cast<float*>(audioData);
    applyCommands();

    int32_t done = 0;
    if (playing_.load(std::memory_order_acquire) && !paused_.load(std::memory_order_acquire)) {
        while (done < numFrames) {
            const int32_t cur = active_.load(std::memory_order_relaxed);
            Slot& s = slots_[cur];
            const size_t got = s.ring.read(out + done * 2, static_cast<size_t>(numFrames - done));
            done += static_cast<int32_t>(got);
            positionOut_.store(positionOut_.load(std::memory_order_relaxed) + static_cast<int64_t>(got),
                               std::memory_order_relaxed);
            if (done == numFrames) break;

            // The ring is empty. Either the decoder is behind, or this track has finished.
            if (!s.eof.load(std::memory_order_acquire) || s.ring.readableFrames() != 0) {
                underrunFrames_.store(underrunFrames_.load(std::memory_order_relaxed) + (numFrames - done),
                                      std::memory_order_relaxed);
                break;
            }

            // Track finished on this exact frame. Hand over to the prepared next slot if it has audio.
            const int32_t nxt = 1 - cur;
            Slot& q = slots_[nxt];
            if (q.state.load(std::memory_order_acquire) == SlotState::kLoaded) {
                if (q.ring.readableFrames() == 0 && !q.eof.load(std::memory_order_acquire)) {
                    // The next track is still being decoded. That is an underrun, not the end.
                    underrunFrames_.store(underrunFrames_.load(std::memory_order_relaxed) + (numFrames - done),
                                          std::memory_order_relaxed);
                    break;
                }
                SlotState expected = SlotState::kLoaded;
                if (q.state.compare_exchange_strong(expected, SlotState::kPlaying,
                                                    std::memory_order_acq_rel)) {
                    s.state.store(SlotState::kDone, std::memory_order_release);
                    active_.store(nxt, std::memory_order_release);
                    positionOut_.store(0, std::memory_order_release);
                    switchCount_.store(switchCount_.load(std::memory_order_relaxed) + 1,
                                       std::memory_order_release);
                    continue;
                }
                // The controller cleared the next track at the same moment. Fall through to the end.
            }

            // Nothing queued: the playlist ends here.
            s.state.store(SlotState::kDone, std::memory_order_release);
            playing_.store(false, std::memory_order_release);
            ended_.store(true, std::memory_order_release);
            break;
        }
    }

    if (done < numFrames) {
        std::memset(out + done * 2, 0, static_cast<size_t>(numFrames - done) * 2 * sizeof(float));
    }

    for (Effect* effect : chain_) effect->process(out, numFrames);
    tap_.write(out, static_cast<size_t>(numFrames));

    const auto t1 = std::chrono::steady_clock::now();
    const int64_t ns = std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count();
    cbLastNs_.store(ns, std::memory_order_relaxed);
    const int64_t avg = cbAvgNs_.load(std::memory_order_relaxed);
    cbAvgNs_.store(avg + (ns - avg) / 16, std::memory_order_relaxed);
    if (ns > cbMaxNs_.load(std::memory_order_relaxed)) cbMaxNs_.store(ns, std::memory_order_relaxed);
    cbCount_.store(cbCount_.load(std::memory_order_relaxed) + 1, std::memory_order_relaxed);

    return oboe::DataCallbackResult::Continue;
}

// ---------------------------------------------------------------------------------------------------------------
// Reporting helpers (controller, not playback)
// ---------------------------------------------------------------------------------------------------------------

std::string trackInfoToKeyValues(const TrackInfo& info) {
    char buf[256];
    std::snprintf(buf, sizeof(buf), "codec=%s;rate=%u;channels=%d;bitDepth=%d;lossless=%d",
                  codecName(info.codec), info.sampleRate, info.channels, info.bitDepth, info.lossless ? 1 : 0);
    return buf;
}

std::string probeToKeyValues(int fd, int64_t offset, int64_t length, int32_t maxSeconds) {
    FdCloser closer{fd};
    std::unique_ptr<Decoder> decoder;
    TrackInfo info;
    const ErrorCode ec = createDecoder(fd, offset, length, decoder, info);
    char buf[512];
    if (ec != kOk) {
        std::snprintf(buf, sizeof(buf), "ok=0;error=%d;codec=%s", static_cast<int>(ec), codecName(Codec::kUnknown));
        return buf;
    }

    const int64_t limit = static_cast<int64_t>(maxSeconds) * static_cast<int64_t>(info.sampleRate);
    std::vector<float> scratch(static_cast<size_t>(kRawChunkFrames) * 2);
    int64_t decodedRaw = 0;
    int32_t errors = 0;
    while (decodedRaw < limit) {
        const int64_t want = std::min<int64_t>(kRawChunkFrames, limit - decodedRaw);
        const int64_t got = decoder->decodeFrames(scratch.data(), want);
        if (got < 0) {
            errors = 1;
            break;
        }
        if (got == 0) break;
        decodedRaw += got;
    }

    const TrimWindow w = trimWindowFor(info);
    const int64_t audible = audibleRawFrames(w);
    const int64_t durationMs =
        audible >= 0 && info.sampleRate > 0 ? audible * 1000 / static_cast<int64_t>(info.sampleRate) : -1;
    std::snprintf(buf, sizeof(buf),
                  "ok=1;error=0;codec=%s;rate=%u;channels=%d;bitDepth=%d;lossless=%d;durationMs=%lld;"
                  "framesDecoded=%lld;decodeErrors=%d",
                  codecName(info.codec), info.sampleRate, info.channels, info.bitDepth, info.lossless ? 1 : 0,
                  static_cast<long long>(durationMs), static_cast<long long>(decodedRaw), errors);
    return buf;
}

}  // namespace futura::player
