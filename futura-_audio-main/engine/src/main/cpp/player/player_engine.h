// Stage 1 playback engine (futura::player::PlayerEngine).
//
// Three threads:
//   Controller (the caller). Every public method. Serialised by the Kotlin side on one thread.
//   Decode. Reads each slot's decoder and fills its ring with resampled, trimmed stereo float at the output rate.
//   Audio callback (Oboe). Copies from the active ring, runs the effect chain, writes the audio tap.
//     It takes no locks, allocates nothing and logs nothing.
//
// Two slots, each with its own ring. The slot being played is "active". The other slot can hold the NEXT
// track, decoded ahead. When the active ring is finished, the callback switches to the next slot on the exact
// frame where the track ends. The rest of the same callback buffer is then filled from the next track, so
// nothing is lost at the boundary.
//
// Locks, in order: controlMutex_ then slotMutex_. The decode thread takes only slotMutex_, once per pass.
// The audio callback takes no lock at all. Control may touch a slot's ring or decoder only while the slot is
// kEmpty or kDone (no reader), or while the stream is stopped (no callback running).
#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <oboe/Oboe.h>

#include "../audio_types.h"
#include "../effect.h"
#include "../spsc_queue.h"
#include "decoder.h"
#include "gapless.h"
#include "pcm_ring.h"
#include "resampler.h"

namespace futura::player {

constexpr int32_t kSlotCount = 2;
constexpr double kRingSeconds = 3.0;        // per slot, at the output rate
constexpr int64_t kRawChunkFrames = 2048;   // one decoder call
constexpr double kPrefillSeconds = 0.25;    // start() waits for this much audio, or for the end of the track
constexpr size_t kCommandCapacity = 32;
constexpr int32_t kTapFrames = 8192;        // audio tap ring. Read by the Stage 4 meters, not yet.

enum class SlotState : int32_t {
    kEmpty = 0,    // no track; ring and decoder are free
    kLoaded = 1,   // track attached and decoding ahead; not being played yet
    kPlaying = 2,  // the callback is reading this slot
    kDone = 3,     // the callback has finished this slot; decoder is released by the decode thread
};

struct Slot {
    PcmRing ring;
    std::unique_ptr<Decoder> decoder;  // guarded by slotMutex_
    TrackInfo info{};                  // written by the controller only, while the slot is kEmpty or kDone
    TrimWindow window{};
    int64_t durationOutFrames = -1;    // controller
    int64_t maxOutPerChunk = 0;        // controller: upper bound on frames one decode chunk adds to the ring
    int64_t rawNext = 0;               // decode thread, under slotMutex_
    Resampler resampler;               // decode thread, under slotMutex_
    std::vector<float> rawBuf;         // decode thread, kRawChunkFrames stereo frames
    std::vector<float> resampleBuf;    // decode thread
    std::atomic<SlotState> state{SlotState::kEmpty};
    std::atomic<bool> eof{false};      // decode thread: every frame of the track is in the ring
};

// Trivially copyable command for the lock-free queue. Only volume for now.
struct PlayerCommand {
    float target = 1.0f;
    int64_t rampFrames = 0;
};

// Gain with a linear ramp, run in the effect chain. Driven only from the audio thread.
class PlayerGain : public Effect {
public:
    void setTarget(float target, int64_t rampFrames);
    void process(float* buffer, int32_t frames) override;

private:
    float current_ = 1.0f;
    float target_ = 1.0f;
    float step_ = 0.0f;
    int64_t remaining_ = 0;
};

// Engine statistics. Callback times are in nanoseconds here and in microseconds at the JNI boundary.
struct PlayerStats {
    int32_t xruns = 0;
    int32_t bufferSizeFrames = 0;
    int32_t framesPerBurst = 0;
    int32_t outputSampleRate = 0;
    int64_t callbackNsLast = 0;
    int64_t callbackNsAvg = 0;
    int64_t callbackNsMax = 0;
    int64_t underrunFrames = 0;
    int64_t callbackCount = 0;
    int32_t decodeErrors = 0;
    int32_t audioApi = 0;  // 0 unspecified, 1 AAudio, 2 OpenSL ES
};

class PlayerEngine : public oboe::AudioStreamDataCallback {
public:
    PlayerEngine();
    ~PlayerEngine() override;

    PlayerEngine(const PlayerEngine&) = delete;
    PlayerEngine& operator=(const PlayerEngine&) = delete;

    // Opens the output stream (shared, low-latency, float, stereo, usage Media / content Music) and starts the
    // decode thread. The stream stays stopped until start(). Returns kOk or an error code.
    ErrorCode open();
    void close();

    // Takes ownership of `fd` and always closes it. Stops playback, drops any queued next track, decodes the
    // range [offset, offset+length) (length < 0 means to end of file), seeks to `startMs`, and makes the track
    // current. Playback does not start until start(). Clears the ended flag.
    ErrorCode loadCurrent(int fd, int64_t offset, int64_t length, int64_t startMs);

    // Takes ownership of `fd` and always closes it. Prepares the track that plays after the current one.
    // Requires a current track and no queued next track (call clearNext() first). Returns kInvalidState otherwise.
    ErrorCode loadNext(int fd, int64_t offset, int64_t length);

    // Drops the queued next track, if the callback has not already switched to it.
    void clearNext();

    // Starts the current track from its current position. Waits briefly for audio to be buffered first.
    ErrorCode start();
    void pause();   // silence, position held, stream stays open
    void resume();
    void stop();    // stops the stream and rewinds to the start of the current track

    // Moves the current track to `ms`. Stops and restarts the stream briefly, and keeps the play/pause state.
    // Returns kInvalidState after the track has ended (load it again). Works paused or stopped without playing.
    ErrorCode seekMs(int64_t ms);

    // Target gain in [0, 1], reached over rampMs milliseconds.
    void setVolume(float target, int32_t rampMs);

    bool isPlaying() const;
    bool isEnded() const { return ended_.load(std::memory_order_acquire); }
    int64_t positionMs() const;
    int64_t durationMs() const;  // -1 when unknown
    int32_t switchCount() const { return switchCount_.load(std::memory_order_acquire); }

    TrackInfo currentTrackInfo();  // controller
    PlayerStats stats();           // controller

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;

private:
    // Controller helpers. Caller holds controlMutex_ unless noted.
    ErrorCode ensureStreamLocked();
    void stopStreamLocked();
    void waitForPrefill(const Slot& s);  // controller, may sleep briefly
    void enqueue(const PlayerCommand& cmd);
    void applyCommands();                 // audio thread
    void releaseSlotLocked(Slot& s);      // caller holds slotMutex_
    ErrorCode attachLocked(Slot& s, std::unique_ptr<Decoder> decoder, const TrackInfo& info);
    ErrorCode repositionLocked(Slot& s, int64_t outFrame);  // caller holds slotMutex_
    void startDecodeThread();
    void stopDecodeThread();

    // Decode thread.
    void decodeLoop();
    void fillSlot(Slot& s);     // caller holds slotMutex_
    void pushFrames(Slot& s, const float* src, int64_t frames);
    void finishSlot(Slot& s);

    std::mutex controlMutex_;   // stream, stream state, slot switching by the controller
    std::mutex producerMutex_;  // single writer into commands_
    std::mutex slotMutex_;      // slots' decoders and rings, between the controller and the decode thread
    std::shared_ptr<oboe::AudioStream> stream_;  // controlMutex_
    bool streamRunning_ = false;                  // controlMutex_

    std::thread decodeThread_;                    // controlMutex_
    std::atomic<bool> decodeRunning_{false};

    Slot slots_[kSlotCount];
    std::atomic<int32_t> active_{0};

    // Written by the controller while the stream is stopped. Read by the callback.
    std::atomic<int64_t> outRate_{0};
    std::atomic<bool> playing_{false};
    std::atomic<bool> paused_{false};
    std::atomic<bool> ended_{false};
    std::atomic<int64_t> positionOut_{0};
    std::atomic<int32_t> switchCount_{0};

    SpscQueue<PlayerCommand, kCommandCapacity> commands_;
    PlayerGain gain_;
    Effect* chain_[1] = {&gain_};  // the effect chain, in order. Stage 1: gain only.
    PcmRing tap_;                  // audio tap, written by the callback, never blocks it

    // Callback statistics. The callback is the only writer.
    std::atomic<int64_t> cbLastNs_{0};
    std::atomic<int64_t> cbAvgNs_{0};
    std::atomic<int64_t> cbMaxNs_{0};
    std::atomic<int64_t> cbCount_{0};
    std::atomic<int64_t> underrunFrames_{0};
    std::atomic<int32_t> decodeErrors_{0};
};

// Decodes the file (at most maxSeconds of audio) and reports the format, as "key=value;..." text.
// Owns and closes fd. Keys: ok, error, codec, rate, channels, bitDepth, lossless, durationMs, framesDecoded,
// decodeErrors. Runs on the caller's thread. Not used by playback.
std::string probeToKeyValues(int fd, int64_t offset, int64_t length, int32_t maxSeconds);

// Facts about an open track, as "codec=...;rate=...;channels=...;bitDepth=...;lossless=0|1".
std::string trackInfoToKeyValues(const TrackInfo& info);

}  // namespace futura::player
