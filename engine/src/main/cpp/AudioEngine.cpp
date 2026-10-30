#include "AudioEngine.h"
#include <iostream>

AudioEngine::AudioEngine() = default;

AudioEngine::~AudioEngine() {
    stop();
}

bool AudioEngine::start() {
    oboe::AudioStreamBuilder builder;
    builder.setFormat(oboe::AudioFormat::Float);
    builder.setChannelCount(oboe::ChannelCount::Stereo);
    builder.setSampleRate(48000);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Exclusive);
    builder.setCallback(this);

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK) {
        return false;
    }

    result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        return false;
    }

    mIsRunning = true;
    return true;
}

void AudioEngine::stop() {
    if (mStream) {
        mStream->requestStop();
        mStream->close();
        mStream.reset();
    }
    mIsRunning = false;
}

bool AudioEngine::isRunning() const {
    return mIsRunning.load();
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
        oboe::AudioStream *oboeStream,
        void *audioData,
        int32_t numFrames) {
    auto *floatData = reinterpret_cast<float *>(audioData);
    for (int i = 0; i < numFrames * mChannelCount.load(); i++) {
        floatData[i] = 0.0f;
    }
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(
        oboe::AudioStream *oboeStream,
        oboe::Result error) {
    mIsRunning = false;
}

int32_t AudioEngine::getChannelCount() const {
    return mChannelCount.load();
}