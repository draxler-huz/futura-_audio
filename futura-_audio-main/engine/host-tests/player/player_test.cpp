// Host tests for the Stage 1 playback engine. Driven by run.sh, which makes the fixtures with ffmpeg.
//
// Usage: player_test <a48.wav> <a48.f32> <b48.wav> <b48.f32> <c44.wav> <c44.f32>
//   *.wav : fixtures (A and B at 48 kHz, C at 44.1 kHz)
//   *.f32 : ffmpeg's own decode of the same file, float32 stereo, interleaved (the reference)
//
// The Oboe stand-in (oboe/Oboe.h) really runs the callback in real time, so these are end-to-end checks of the
// decode thread, the rings, the gapless switch and the seek, not just the functions in isolation.
#include <fcntl.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <functional>
#include <string>
#include <thread>
#include <vector>

#include "oboe/Oboe.h"
#include "player/gapless.h"
#include "player/pcm_ring.h"
#include "player/player_engine.h"
#include "player/resampler.h"

using namespace futura;
using namespace futura::player;

namespace {

int failures = 0;
int passes = 0;

#define CHECK(cond, msg)                                              \
    do {                                                              \
        if (!(cond)) {                                                \
            std::printf("  FAIL %s:%d %s\n", __FILE__, __LINE__, msg); \
            ++failures;                                               \
        } else {                                                      \
            ++passes;                                                 \
        }                                                             \
    } while (0)

std::vector<float> readFloats(const char* path) {
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    if (!f) return {};
    const std::streamsize bytes = f.tellg();
    f.seekg(0);
    std::vector<float> v(static_cast<size_t>(bytes) / sizeof(float));
    f.read(reinterpret_cast<char*>(v.data()), bytes);
    return v;
}

int openFd(const char* path) { return ::open(path, O_RDONLY); }

std::vector<float> capture() {
    std::lock_guard<std::mutex> lock(oboe::capturedMutex());
    return oboe::capturedFrames();
}

void clearCapture() {
    std::lock_guard<std::mutex> lock(oboe::capturedMutex());
    oboe::capturedFrames().clear();
}

bool waitFor(const std::function<bool()>& pred, int timeoutMs) {
    for (int waited = 0; waited < timeoutMs; waited += 5) {
        if (pred()) return true;
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    return pred();
}

double maxAbsDiff(const std::vector<float>& a, const std::vector<float>& b, size_t n) {
    double m = 0.0;
    for (size_t i = 0; i < n; ++i) m = std::max(m, static_cast<double>(std::fabs(a[i] - b[i])));
    return m;
}

double rms(const std::vector<float>& a, size_t from, size_t to) {
    double acc = 0.0;
    for (size_t i = from; i < to; ++i) acc += static_cast<double>(a[i]) * a[i];
    return to > from ? std::sqrt(acc / static_cast<double>(to - from)) : 0.0;
}

// ---------------------------------------------------------------------------------------------------------------

void testRing() {
    std::printf("ring\n");
    PcmRing r;
    r.allocate(10);
    float a[7 * 2];
    for (int i = 0; i < 14; ++i) a[i] = static_cast<float>(i);
    CHECK(r.write(a, 7) == 7, "write 7 of 10");
    float b[5 * 2];
    CHECK(r.read(b, 5) == 5, "read 5");
    CHECK(std::memcmp(b, a, sizeof(b)) == 0, "first five frames intact");
    float c[8 * 2];
    for (int i = 0; i < 16; ++i) c[i] = 100.0f + i;
    CHECK(r.writableFrames() == 8, "eight frames free after the read");
    CHECK(r.write(c, 20) == 8, "write clamps to free space (wraps the ring)");
    float d[10 * 2];
    CHECK(r.read(d, 10) == 10, "read everything back across the wrap");
    CHECK(d[0] == 10.0f && d[1] == 11.0f, "frame 5 comes back in order");
    CHECK(d[4] == 100.0f && d[5] == 101.0f, "frame 7 is the first written after the wrap");
    CHECK(r.readableFrames() == 0, "ring empty again");
}

void testResampler() {
    std::printf("resampler\n");
    Resampler rs;
    rs.configure(44100, 48000, 2048);
    CHECK(rs.active(), "44.1 kHz to 48 kHz is active");
    std::vector<float> in(4096 * 2, 0.5f);
    std::vector<float> out(static_cast<size_t>(rs.maxOutputFor(4096) * 2 + 64));
    int64_t total = 0;
    std::vector<float> produced;
    for (int chunk = 0; chunk < 2; ++chunk) {
        const int64_t n = rs.process(in.data() + chunk * 2048 * 2, 2048, out.data(), rs.maxOutputFor(2048));
        produced.insert(produced.end(), out.begin(), out.begin() + n * 2);
        total += n;
    }
    const int64_t tail = rs.flush(out.data(), rs.maxOutputFor(0) + 4);
    produced.insert(produced.end(), out.begin(), out.begin() + tail * 2);
    total += tail;
    const double expected = 4096.0 * 48000.0 / 44100.0;
    CHECK(std::fabs(static_cast<double>(total) - expected) < 4.0, "output length matches the rate ratio");
    bool flat = true;
    for (size_t i = 32; i + 1 < produced.size(); i += 2) {
        if (std::fabs(produced[i] - 0.5f) > 1e-3f) flat = false;
    }
    CHECK(flat, "constant input stays constant once the filter has history");
}

void testWindow() {
    std::printf("gapless window\n");
    TrackInfo i;
    i.totalFrames = 1000;
    i.skipFrames = 100;
    i.trimEndFrames = 50;
    const TrimWindow w = trimWindowFor(i);
    CHECK(w.begin == 100 && w.end == 950, "skip and trim bound the window");
    CHECK(audibleRawFrames(w) == 850, "audible length");
    i.totalFrames = -1;
    const TrimWindow open = trimWindowFor(i);
    CHECK(!open.endKnown() && audibleRawFrames(open) == -1, "unknown length keeps the end open");
    i.totalFrames = 10;
    i.skipFrames = 100;
    CHECK(audibleRawFrames(trimWindowFor(i)) == 0, "degenerate file gives zero, never negative");
}

// Plays A then B as one gapless sequence and checks the output sample by sample against both references.
void testGapless(const char* a, const char* aRef, const char* b, const char* bRef) {
    std::printf("engine: gapless A to B\n");
    const std::vector<float> refA = readFloats(aRef);
    const std::vector<float> refB = readFloats(bRef);
    PlayerEngine e;
    CHECK(e.open() == kOk, "open");
    CHECK(e.loadCurrent(openFd(a), 0, -1, 0) == kOk, "load A");
    CHECK(e.loadNext(openFd(b), 0, -1) == kOk, "queue B");
    CHECK(e.loadNext(openFd(b), 0, -1) == kInvalidState, "second queued next is refused");
    clearCapture();
    CHECK(e.start() == kOk, "start");
    const bool ended = waitFor([&] { return e.isEnded(); }, 10000);
    e.stop();
    CHECK(ended, "playlist ends");
    CHECK(e.switchCount() == 1, "exactly one gapless switch");

    std::vector<float> expected = refA;
    expected.insert(expected.end(), refB.begin(), refB.end());
    const std::vector<float> got = capture();
    CHECK(got.size() >= expected.size(), "captured at least the whole playlist");
    const double diff = maxAbsDiff(got, expected, expected.size());
    std::printf("  max sample difference across the switch: %.3g\n", diff);
    CHECK(diff < 1e-5, "output equals A followed by B, sample for sample");
    const PlayerStats s = e.stats();
    CHECK(s.underrunFrames == 0, "no underruns while decoding ahead");
    CHECK(s.callbackCount > 0, "callback ran");
    e.close();
}

void testSeek(const char* a, const char* aRef) {
    std::printf("engine: seek\n");
    const std::vector<float> refA = readFloats(aRef);
    const int64_t skipOutFrames = 12000;  // 250 ms at 48 kHz
    PlayerEngine e;
    CHECK(e.open() == kOk, "open");
    CHECK(e.loadCurrent(openFd(a), 0, -1, 0) == kOk, "load A");
    CHECK(e.seekMs(250) == kOk, "seek while stopped");
    CHECK(!e.isPlaying(), "seeking while stopped does not start playback");
    clearCapture();
    CHECK(e.start() == kOk, "start after seek");
    waitFor([&] { return e.isEnded(); }, 10000);
    e.stop();
    const std::vector<float> got = capture();
    const size_t n = refA.size() - static_cast<size_t>(skipOutFrames) * 2;
    CHECK(got.size() >= n, "captured the rest of the track");
    const double diff = maxAbsDiff(got, std::vector<float>(refA.begin() + skipOutFrames * 2, refA.end()), n);
    std::printf("  max sample difference after seek: %.3g\n", diff);
    CHECK(diff < 1e-5, "seeked output equals the reference from 250 ms");
    e.close();
}

void testResampledPlayback(const char* c, const char* /*cRef*/) {
    std::printf("engine: 44.1 kHz source at a 48 kHz output\n");
    // The fixture is sin(2*pi*500*t) on the left and 0.5*sin(2*pi*700*t) on the right, 1.37 s at 44.1 kHz.
    // The expected output is the same signal sampled at 48 kHz, so each frame can be checked on its own.
    const double inSeconds = 1.37;
    const size_t expectedFrames = static_cast<size_t>(inSeconds * 48000.0);  // 65760
    PlayerEngine e;
    CHECK(e.open() == kOk, "open");
    CHECK(e.loadCurrent(openFd(c), 0, -1, 0) == kOk, "load C");
    CHECK(e.durationMs() > 1300 && e.durationMs() < 1400, "duration reported from the source length");
    clearCapture();
    CHECK(e.start() == kOk, "start");
    waitFor([&] { return e.isEnded(); }, 10000);
    e.stop();
    const std::vector<float> got = capture();
    const PlayerStats st = e.stats();
    std::printf("  underrun frames %lld, callbacks %lld\n", static_cast<long long>(st.underrunFrames),
                static_cast<long long>(st.callbackCount));
    CHECK(got.size() / 2 >= expectedFrames, "captured at least the whole track");
    double worst = 0.0;
    size_t worstFrame = 0;
    const double kTau = 6.28318530717958647692;
    for (size_t f = 0; f + 4 < expectedFrames; ++f) {  // the last frames interpolate against padding, see below
        const double t = static_cast<double>(f) / 48000.0;
        const double left = std::sin(kTau * 500.0 * t);
        const double right = 0.5 * std::sin(kTau * 700.0 * t);
        const double err = std::max(std::fabs(got[2 * f] - left), std::fabs(got[2 * f + 1] - right));
        if (err > worst) {
            worst = err;
            worstFrame = f;
        }
    }
    std::printf("  worst error against the analytic 48 kHz signal: %.3g at frame %zu\n", worst, worstFrame);
    CHECK(worst < 2e-3, "resampled output follows the source signal across the whole track");
    e.close();
}

void testProbe(const char* a) {
    std::printf("probe\n");
    const std::string report = probeToKeyValues(openFd(a), 0, -1, 10);
    std::printf("  %s\n", report.c_str());
    CHECK(report.find("ok=1") != std::string::npos, "probe succeeds");
    CHECK(report.find("rate=48000") != std::string::npos, "probe reports the sample rate");
    CHECK(report.find("decodeErrors=0") != std::string::npos, "probe reports no decode errors");
    const std::string bad = probeToKeyValues(openFd(a), 0, 8, 10);  // 8 bytes is not a file
    CHECK(bad.find("ok=0") != std::string::npos, "probe rejects a truncated file");
}

}  // namespace

int main(int argc, char** argv) {
    if (argc != 7) {
        std::printf("usage: %s a48.wav a48.f32 b48.wav b48.f32 c44.wav c44.f32\n", argv[0]);
        return 2;
    }
    testRing();
    testResampler();
    testWindow();
    testGapless(argv[1], argv[2], argv[3], argv[4]);
    testSeek(argv[1], argv[2]);
    testResampledPlayback(argv[5], argv[6]);
    testProbe(argv[1]);
    std::printf("\n%d passed, %d failed\n", passes, failures);
    return failures == 0 ? 0 : 1;
}
