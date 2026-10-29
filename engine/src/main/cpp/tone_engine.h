// Stage 0 skeleton: one Oboe output stream that plays a 440 Hz sine from the audio callback.
//
// Threading:
//   Control methods (open, close, setPlaying, setFrequency, setGain, info, xRunCount) run on one
//   controller thread. The Kotlin side serialises them.
//   onAudioReady runs on the Oboe callback thread. It takes no locks, allocates nothing and logs nothing.
//   Commands travel through a lock-free SPSC queue that the callback drains.
#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>

#include <oboe/Oboe.h>

#include "audio_types.h"
#include "effect.h"
#include "spsc_queue.h"

namespace futura {

enum class ToneCommandKind : int32_t { kSetPlaying, kSetFrequency, kSetGain };

// Trivially copyable. `value` is 1 or 0 for kSetPlaying, Hz for kSetFrequency, and linear gain for kSetGain.
struct ToneCommand {
    ToneCommandKind kind = ToneCommandKind::kSetPlaying;
    float value = 0.0f;
};

constexpr size_t kToneQueueCapacity = 64;

// Facts about the open stream. Audio API codes: 0 unspecified, 1 AAudio, 2 OpenSL ES.
struct ToneStreamInfo {
    int32_t sampleRate = 0;
    int32_t framesPerBurst = 0;
    int32_t audioApi = 0;
    int32_t deviceId = 0;
};

class ToneEngine : public oboe::AudioStreamDataCallback {
public:
    ToneEngine();
    ~ToneEngine() override;

    ToneEngine(const ToneEngine&) = delete;
    ToneEngine& operator=(const ToneEngine&) = delete;

    // Opens a shared, low-latency, float stream and starts it. The stream stays silent until setPlaying(true).
    // Returns kOk or an ErrorCode. Calling it again while open is a no-op.
    ErrorCode open();
    void close();

    void setPlaying(bool playing);
    void setFrequency(float hz);
    void setGain(float linear);  // [0, 1]

    // Returns zeros when the stream is closed.
    ToneStreamInfo info() const;

    // Underruns reported by the platform since the stream opened. Zero when the stream is closed.
    int32_t xRunCount();

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;

private:
    void enqueue(const ToneCommand& cmd);  // controller thread
    void applyCommands();                  // audio thread

    mutable std::mutex controlMutex_;  // stream and info (controller thread)
    std::mutex producerMutex_;         // single writer into commands_
    std::shared_ptr<oboe::AudioStream> stream_;
    ToneStreamInfo info_{};            // controlMutex_

    // Written before the stream starts. Read by the audio thread.
    std::atomic<int32_t> streamRate_{0};

    SpscQueue<ToneCommand, kToneQueueCapacity> commands_;

    // Audio thread only.
    bool playing_ = false;
    float frequency_ = 440.0f;
    float gain_ = 0.2f;     // about -14 dBFS
    float amplitude_ = 0.0f;
    double phase_ = 0.0;
};

}  // namespace futura
