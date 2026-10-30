#pragma once

#include <oboe/Oboe.h>
#include <atomic>
#include <mutex>

class AudioEngine : public oboe::AudioStreamCallback {
public:
    AudioEngine();
    ~AudioEngine() override;

    bool start();
    void stop();
    bool isRunning() const;
    int32_t getChannelCount() const;

    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream *oboeStream,
            void *audioData,
            int32_t numFrames) override;

    void onErrorAfterClose(
            oboe::AudioStream *oboeStream,
            oboe::Result error) override;

private:
    std::unique_ptr<oboe::AudioStream> mStream;
    std::atomic<bool> mIsRunning{false};
    std::atomic<int32_t> mChannelCount{2};
};