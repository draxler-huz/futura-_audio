// Low-latency output engine: one Oboe stream, one pre-loaded track, and a DSP chain.
//
// Signal path (audio thread):
//   GaplessLoop -> EqChain -> VolumeRamp -> Compressor (with ceiling) -> output
//
// Threading:
//   Control methods are called from one controller thread. The Kotlin side serialises them.
//   onAudioReady runs on the Oboe callback thread. It never waits on the controller:
//   parameter changes are staged under a mutex that the callback only try-locks.
#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>

#include <oboe/Oboe.h>

#include "audio_types.h"
#include "dsp.h"
#include "gapless_loop.h"

namespace futura {

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
    // Control-side changes waiting for the audio thread. Each section has its own sequence number.
    struct Staged {
        uint32_t version = 0;

        uint32_t volumeSeq = 0;
        float volumeTarget = 0.0f;
        int32_t volumeRampMs = 0;
        VolumeCurve volumeCurve = VolumeCurve::kLinear;

        uint32_t compressorSeq = 0;
        CompressorParams compressor{};

        uint32_t eqSeq = 0;
        EqBand eqBands[kMaxEqBands];
        int32_t eqCount = 0;
    };

    ErrorCode ensureStreamLocked();
    void stopLocked();
    void applyStaged();  // audio thread

    mutable std::mutex controlMutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
    uint32_t sampleRate_ = 0;
    PcmBuffer track_;
    GaplessLoop loop_;

    std::atomic<bool> playing_{false};
    std::atomic<bool> paused_{false};

    std::mutex stagedMutex_;
    Staged staged_;
    std::atomic<uint32_t> stagedVersion_{0};

    // Audio thread only.
    uint32_t appliedVersion_ = 0;
    uint32_t appliedVolumeSeq_ = 0;
    uint32_t appliedCompressorSeq_ = 0;
    uint32_t appliedEqSeq_ = 0;
    VolumeRamp ramp_;
    Compressor compressor_;
    EqChain eq_;
};

}  // namespace futura
