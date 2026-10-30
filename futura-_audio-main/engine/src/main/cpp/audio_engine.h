// Low-latency output engine: one Oboe stream, one pre-loaded track, and a DSP chain.
//
// Signal path (audio thread):
//   GaplessLoop -> EqChain -> VolumeRamp -> Compressor (with ceiling) -> output
//
// Threading:
//   Control methods are called from one controller thread. The Kotlin side serialises them.
//   onAudioReady runs on the Oboe callback thread. It takes no locks and does not allocate.
//   Parameter changes travel through a lock-free SPSC queue that the callback drains.
#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>

#include <oboe/Oboe.h>

#include "audio_types.h"
#include "dsp.h"
#include "gapless_loop.h"
#include "spsc_queue.h"

namespace futura {

// The audio thread drains this queue on every callback, so it only fills while the stream is stopped.
constexpr size_t kParamQueueCapacity = 256;

// One parameter change. Trivially copyable, so it can sit in the lock-free queue.
struct ParamCommand {
    enum class Kind : int32_t { kVolume, kCompressor, kEq };

    Kind kind = Kind::kVolume;

    // kVolume
    float volumeTarget = 0.0f;
    int32_t volumeRampMs = 0;
    VolumeCurve volumeCurve = VolumeCurve::kLinear;

    // kCompressor
    CompressorParams compressor{};

    // kEq
    EqBand eqBands[kMaxEqBands]{};
    int32_t eqCount = 0;
};

class AudioEngine : public oboe::AudioStreamDataCallback {
public:
    AudioEngine();
    ~AudioEngine() override;

    AudioEngine(const AudioEngine&) = delete;
    AudioEngine& operator=(const AudioEngine&) = delete;

    // Decodes the file and makes it the current track. Takes ownership of `fd`: it is always closed.
    // Stops playback first. Opens the output stream if needed, because the track is resampled
    // to the stream's native rate.
    ErrorCode loadFile(int fd, int64_t offset, int64_t length);

    // Starts playback of the current track from its beginning.
    ErrorCode start();
    void pause();   // holds position; output is silent
    void resume();
    void stop();    // stops the stream and rewinds

    // Target gain in [0, 1]. rampMs = 0 applies it immediately.
    void setVolume(float target, int32_t rampMs, VolumeCurve curve);
    void setCompressor(const CompressorParams& params);
    void setEq(const EqBand* bands, int32_t count);

    bool isPlaying() const { return playing_.load(std::memory_order_acquire); }
    uint32_t sampleRate() const;

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;

private:
    void enqueue(const ParamCommand& cmd);  // controller thread
    void applyParams();                     // audio thread

    ErrorCode ensureStreamLocked();
    void stopLocked();

    mutable std::mutex controlMutex_;   // stream, track and loop changes (controller thread)
    std::mutex producerMutex_;          // single writer into params_ (controller thread only)
    std::shared_ptr<oboe::AudioStream> stream_;
    uint32_t sampleRate_ = 0;           // controlMutex_
    PcmBuffer track_;
    GaplessLoop loop_;

    std::atomic<bool> playing_{false};
    std::atomic<bool> paused_{false};

    // Written before the stream starts. Read by the audio thread.
    std::atomic<uint32_t> streamRate_{0};

    SpscQueue<ParamCommand, kParamQueueCapacity> params_;

    // Audio thread only.
    bool volumeConfigured_ = false;
    VolumeRamp ramp_;
    Compressor compressor_;
    EqChain eq_;
};

}  // namespace futura
