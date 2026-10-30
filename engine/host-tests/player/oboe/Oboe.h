// Host-only stand-in for the subset of Oboe that PlayerEngine uses. NOT the real Oboe.
// Unlike engine/host-tests/oboe, this one really runs the data callback: requestStart() starts a thread that
// calls onAudioReady() every 10 ms with 480 frames (real-time pacing at 48 kHz) and keeps everything it
// produced in a capture buffer for the test to inspect.
#pragma once
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>
#include <chrono>

namespace oboe {
enum class Direction { Input, Output };
enum class PerformanceMode { None, PowerSaving, LowLatency };
enum class SharingMode { Exclusive, Shared };
enum class AudioFormat { Invalid, Unspecified, I16, Float };
enum class ChannelCount { Unspecified, Mono, Stereo };
enum class Usage { Media, Alarm, Notification };
enum class ContentType { Music, Speech };
enum class AudioApi { Unspecified, OpenSLES, AAudio };
enum class Result { OK, ErrorUnimplemented, ErrorInternal };
enum class DataCallbackResult { Continue, Stop };

template <typename T> class ResultWithValue {
public:
    ResultWithValue(Result e) : error_(e), value_() {}
    ResultWithValue(T v) : error_(Result::OK), value_(v) {}
    Result error() const { return error_; }
    T value() const { return value_; }
private:
    Result error_; T value_;
};

class AudioStream;
class AudioStreamDataCallback {
public:
    virtual ~AudioStreamDataCallback() = default;
    virtual DataCallbackResult onAudioReady(AudioStream* stream, void* audioData, int32_t numFrames) = 0;
};

// Test-only capture of everything the callback produced, in order, interleaved stereo float.
std::vector<float>& capturedFrames();
std::mutex& capturedMutex();

class AudioStream {
public:
    explicit AudioStream(AudioStreamDataCallback* cb) : cb_(cb) {}
    ~AudioStream() { stop(); }
    Result requestStart() {
        if (running_.exchange(true)) return Result::OK;
        thread_ = std::thread([this] {
            std::vector<float> buf(480 * 2);
            while (running_.load()) {
                cb_->onAudioReady(this, buf.data(), 480);
                {
                    std::lock_guard<std::mutex> lock(capturedMutex());
                    capturedFrames().insert(capturedFrames().end(), buf.begin(), buf.end());
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }
        });
        return Result::OK;
    }
    Result requestStop() { running_.store(false); return Result::OK; }
    Result stop() {
        running_.store(false);
        if (thread_.joinable()) thread_.join();
        return Result::OK;
    }
    Result close() { return stop(); }
    int32_t getSampleRate() const { return 48000; }
    int32_t getFramesPerBurst() const { return 480; }
    int32_t getBufferSizeInFrames() const { return 960; }
    int32_t getDeviceId() const { return 7; }
    AudioApi getAudioApi() const { return AudioApi::AAudio; }
    ResultWithValue<int32_t> getXRunCount() { return ResultWithValue<int32_t>(0); }
private:
    AudioStreamDataCallback* cb_;
    std::atomic<bool> running_{false};
    std::thread thread_;
};

class AudioStreamBuilder {
public:
    AudioStreamBuilder& setDirection(Direction) { return *this; }
    AudioStreamBuilder& setPerformanceMode(PerformanceMode) { return *this; }
    AudioStreamBuilder& setSharingMode(SharingMode) { return *this; }
    AudioStreamBuilder& setFormat(AudioFormat) { return *this; }
    AudioStreamBuilder& setChannelCount(ChannelCount) { return *this; }
    AudioStreamBuilder& setUsage(Usage) { return *this; }
    AudioStreamBuilder& setContentType(ContentType) { return *this; }
    AudioStreamBuilder& setDataCallback(AudioStreamDataCallback* cb) { callback_ = cb; return *this; }
    Result openStream(std::shared_ptr<AudioStream>& out) {
        out = std::make_shared<AudioStream>(callback_);
        return Result::OK;
    }
private:
    AudioStreamDataCallback* callback_ = nullptr;
};
}  // namespace oboe
