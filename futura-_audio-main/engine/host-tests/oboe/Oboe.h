// Host-only stand-in for the subset of the Oboe API that the engine uses. NOT the real Oboe.
#pragma once
#include <cstdint>
#include <memory>
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
class AudioStream {
public:
    virtual ~AudioStream() = default;
    virtual Result requestStart() { return Result::OK; }
    virtual Result requestStop() { return Result::OK; }
    virtual Result stop() { return Result::OK; }
    virtual Result close() { return Result::OK; }
    virtual int32_t getSampleRate() const { return 48000; }
    virtual int32_t getFramesPerBurst() const { return 96; }
    virtual int32_t getDeviceId() const { return 7; }
    virtual AudioApi getAudioApi() const { return AudioApi::AAudio; }
    virtual ResultWithValue<int32_t> getXRunCount() { return ResultWithValue<int32_t>(Result::ErrorUnimplemented); }
    virtual Result setBufferSizeInFrames(int32_t) { return Result::OK; }
    virtual AudioStreamDataCallback* dataCallback() const { return nullptr; }
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
    Result openStream(std::shared_ptr<AudioStream>& out);
private:
    AudioStreamDataCallback* callback_ = nullptr;
};
}  // namespace oboe
