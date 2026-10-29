#include <cassert>
#include <cmath>
#include <cstdio>
#include <thread>
#include <vector>

#include "audio_engine.h"
#include "spsc_queue.h"
#include "tone_engine.h"

using namespace futura;
static int failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { std::printf("FAIL: %s\n", msg); ++failures; } else { std::printf("ok:   %s\n", msg); } } while (0)

static void testQueue() {
    SpscQueue<int, 4> q;
    int unused = 0;
    CHECK(!q.pop(unused), "empty queue pop returns false");
    for (int i = 0; i < 4; ++i) CHECK(q.push(i), "push into non-full queue");
    CHECK(!q.push(99), "push into full queue is refused");
    int v = -1;
    CHECK(q.pop(v) && v == 0, "FIFO: first out is first in");
    CHECK(q.push(4) && !q.push(5), "room reappears after pop; capacity is exact");
}

static void testQueueThreads() {
    SpscQueue<uint32_t, 1024> q;
    const uint32_t N = 2000000;
    bool ordered = true;
    std::thread producer([&] {
        for (uint32_t i = 0; i < N; ++i) {
            while (!q.push(i)) std::this_thread::yield();
        }
    });
    uint32_t expected = 0;
    while (expected < N) {
        uint32_t v;
        if (q.pop(v)) {
            if (v != expected) { ordered = false; break; }
            ++expected;
        } else {
            std::this_thread::yield();
        }
    }
    producer.join();
    CHECK(ordered && expected == N, "two-thread SPSC: 2M items arrive in order with none lost");
}

static void testTone() {
    ToneEngine t;
    CHECK(t.open() == kOk, "tone: open succeeds");
    ToneStreamInfo info = t.info();
    CHECK(info.sampleRate == 48000 && info.framesPerBurst == 96 && info.audioApi == 1 && info.deviceId == 7,
          "tone: info reports rate, burst, API code and device id");

    // Simulate the Oboe callback. Stream is open, but nothing has been requested yet.
    float buf[96 * 2];
    auto run = [&](int frames) { t.onAudioReady(nullptr, buf, frames); };

    run(96);
    bool silent = true;
    for (float s : buf) if (s != 0.0f) silent = false;
    CHECK(silent, "tone: silent until playing is requested");

    t.setPlaying(true);
    // Ramp is 10 ms at 48 kHz = 480 frames, then the output should sit at gain 0.2.
    std::vector<float> out;
    const int total = 48000;  // one second
    for (int done = 0; done < total; done += 96) {
        run(96);
        for (int i = 0; i < 96; ++i) out.push_back(buf[2 * i]);
    }
    float peakAfterRamp = 0.0f;
    for (int i = 1000; i < total; ++i) peakAfterRamp = std::max(peakAfterRamp, std::fabs(out[i]));
    CHECK(std::fabs(peakAfterRamp - 0.2f) < 0.001f, "tone: steady amplitude equals gain 0.2 after the ramp");
    // The first 10 ms fade in linearly from 0. In the first 10 frames the envelope is at most 10 * (0.2 / 480).
    float earlyPeak = 0.0f;
    for (int i = 0; i < 10; ++i) earlyPeak = std::max(earlyPeak, std::fabs(out[i]));
    CHECK(earlyPeak <= 10.0f * (0.2f / 480.0f) + 1e-6f, "tone: fade-in starts from silence (no click)");

    int crossings = 0;
    for (int i = 1000; i + 1 < total; ++i) {
        if ((out[i] < 0.0f) != (out[i + 1] < 0.0f)) ++crossings;
    }
    // Window is 47000 frames at 48 kHz. A 440 Hz sine gives 2 * 440 * (47000 / 48000) = 861.7 crossings.
    CHECK(crossings >= 859 && crossings <= 864, "tone: frequency is 440 Hz over the measured window");

    t.setPlaying(false);
    for (int i = 0; i < 5; ++i) run(96);   // 480 frames: ramp finishes
    run(96);
    bool quiet = true;
    for (float s : buf) if (std::fabs(s) > 0.0f) quiet = false;
    CHECK(quiet, "tone: silent again after stop once the fade-out completes");

    t.setGain(0.5f);
    t.setPlaying(true);
    for (int i = 0; i < 10; ++i) run(96);
    float peak = 0.0f;
    for (int i = 0; i < 96; ++i) peak = std::max(peak, std::fabs(buf[2 * i]));
    CHECK(peak > 0.3f && peak <= 0.5f + 0.001f, "tone: gain change applies (peak moves towards 0.5)");

    t.close();
    CHECK(t.info().sampleRate == 0, "tone: info is cleared after close");
}

static void testAlarmPath() {
    AudioEngine e;
    CHECK(e.loadFile(-1, 0, -1) == kOk, "alarm: loadFile decodes via decoder stub");
    e.setVolume(1.0f, 0, VolumeCurve::kLinear);
    CHECK(e.start() == kOk, "alarm: start succeeds");
    CHECK(e.isPlaying(), "alarm: reports playing");

    // Drive the callback directly. Parameter changes are drained by the callback, without locks.
    std::vector<float> buf(480 * 2);
    e.onAudioReady(nullptr, buf.data(), 480);
    float peak = 0.0f;
    for (float s : buf) peak = std::max(peak, std::fabs(s));
    CHECK(peak > 0.1f, "alarm: queued volume 1.0 applies and output is audible");

    e.setVolume(0.0f, 0, VolumeCurve::kLinear);
    e.setCompressor(CompressorParams{});
    EqBand band{EqType::kPeaking, 1000.0f, 1.0f, 3.0f};
    e.setEq(&band, 1);
    e.onAudioReady(nullptr, buf.data(), 480);
    float tail = 0.0f;
    for (size_t i = buf.size() - 20; i < buf.size(); ++i) tail = std::max(tail, std::fabs(buf[i]));
    CHECK(tail < 1e-6f, "alarm: queued volume 0.0 silences output; compressor and EQ commands apply cleanly");

    e.stop();
    CHECK(!e.isPlaying(), "alarm: stop clears playing state");
}

int main() {
    testQueue();
    testQueueThreads();
    testTone();
    testAlarmPath();
    std::printf("%s (%d failure(s))\n", failures ? "FAILED" : "ALL PASSED", failures);
    return failures ? 1 : 0;
}
