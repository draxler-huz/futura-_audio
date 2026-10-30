// Host test driver for the Stage 1 decoders. Driven by run.sh, which generates fixtures with ffmpeg.
//
// Modes:
//   info   <audio>                          print TrackInfo
//   decode <audio> <ref.f32> <tolerance>    decode everything, apply skip and trim, compare with the reference
//                                           (ffmpeg's own decode of the same file), then check seeks
//   error  <file>                           createDecoder must fail (garbage, unsupported layouts)
//   window <audio> <bytes>                  open a truncated window of the file, decode it all, must not crash
//   dump   <audio> <from> <count>           print raw decoded frames (debugging)
//
// The reference is ffmpeg's decode (`-f f32le -ac 2`), which applies gapless trimming itself. So a match means
// our skip and trim values are right, not merely self-consistent.
#include <fcntl.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "decoder.h"

using futura::ErrorCode;
using futura::kOk;
using futura::player::codecName;
using futura::player::createDecoder;
using futura::player::Decoder;
using futura::player::TrackInfo;

namespace {

constexpr int64_t kChunk = 4096;
constexpr int64_t kSafetyFrames = 50000000;  // bounds a runaway decoder

class Fd {
public:
    explicit Fd(const char* path) : fd_(::open(path, O_RDONLY)) {}
    ~Fd() {
        if (fd_ >= 0) ::close(fd_);
    }
    int get() const { return fd_; }
    bool ok() const { return fd_ >= 0; }

private:
    int fd_;
};

bool readFloats(const char* path, std::vector<float>& out) {
    FILE* f = std::fopen(path, "rb");
    if (!f) return false;
    std::fseek(f, 0, SEEK_END);
    const long bytes = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    out.resize(static_cast<size_t>(bytes) / sizeof(float));
    const size_t got = std::fread(out.data(), sizeof(float), out.size(), f);
    std::fclose(f);
    return got == out.size();
}

// Decodes from the current position to the end, into `raw` (stereo interleaved). Returns frames, or -1 on error.
int64_t decodeToEnd(Decoder& dec, std::vector<float>& raw) {
    std::vector<float> chunk(static_cast<size_t>(kChunk * 2));
    int64_t total = 0;
    for (;;) {
        const int64_t got = dec.decodeFrames(chunk.data(), kChunk);
        if (got < 0) return -1;
        if (got == 0) break;
        raw.insert(raw.end(), chunk.begin(), chunk.begin() + got * 2);
        total += got;
        if (total > kSafetyFrames) return -1;
    }
    return total;
}

void printInfo(const TrackInfo& info) {
    std::printf("info codec=%s rate=%u ch=%d bits=%d lossless=%d total=%lld skip=%lld trim=%lld\n",
                codecName(info.codec), info.sampleRate, info.channels, info.bitDepth, info.lossless ? 1 : 0,
                static_cast<long long>(info.totalFrames), static_cast<long long>(info.skipFrames),
                static_cast<long long>(info.trimEndFrames));
}

int cmdInfo(const char* path) {
    Fd f(path);
    if (!f.ok()) return 2;
    std::unique_ptr<Decoder> dec;
    TrackInfo info;
    const ErrorCode ec = createDecoder(f.get(), 0, -1, dec, info);
    if (ec != kOk) {
        std::printf("open failed ec=%d\n", ec);
        return 1;
    }
    printInfo(info);
    return 0;
}

int cmdDecode(const char* path, const char* refPath, double tol) {
    Fd f(path);
    if (!f.ok()) return 2;
    std::unique_ptr<Decoder> dec;
    TrackInfo info;
    if (createDecoder(f.get(), 0, -1, dec, info) != kOk) {
        std::printf("FAIL open\n");
        return 1;
    }
    printInfo(info);

    std::vector<float> raw;
    const int64_t rawFrames = decodeToEnd(*dec, raw);
    if (rawFrames < 0) {
        std::printf("FAIL decode error\n");
        return 1;
    }

    // Gapless: drop the leading skip, and the trailing trim when the total is known.
    const int64_t start = std::min(info.skipFrames, rawFrames);
    int64_t end = rawFrames;
    if (info.totalFrames >= 0) end = std::max(start, std::min(rawFrames, info.totalFrames) - info.trimEndFrames);
    const int64_t logical = end - start;
    std::printf("raw=%lld logical=%lld\n", static_cast<long long>(rawFrames), static_cast<long long>(logical));

    std::vector<float> ref;
    if (!readFloats(refPath, ref)) {
        std::printf("FAIL reference\n");
        return 1;
    }
    const int64_t refFrames = static_cast<int64_t>(ref.size() / 2);
    bool pass = true;
    if (logical != refFrames) {
        std::printf("FAIL length: logical=%lld reference=%lld\n", static_cast<long long>(logical),
                    static_cast<long long>(refFrames));
        pass = false;
    }

    double maxDiff = 0.0;
    int64_t worstFrame = -1;
    int64_t badSamples = 0;
    int64_t firstBad = -1;
    int64_t lastBad = -1;
    const int64_t cmp = std::min(logical, refFrames);
    for (int64_t i = 0; i < cmp * 2; ++i) {
        const double d = std::fabs(static_cast<double>(raw[static_cast<size_t>(start * 2 + i)]) -
                                   static_cast<double>(ref[static_cast<size_t>(i)]));
        if (d > maxDiff) {
            maxDiff = d;
            worstFrame = i / 2;
        }
        if (d > tol) {
            ++badSamples;
            if (firstBad < 0) firstBad = i / 2;
            lastBad = i / 2;
        }
    }
    std::printf("maxdiff=%.6g at frame %lld of %lld, tol=%.6g\n", maxDiff, static_cast<long long>(worstFrame),
                static_cast<long long>(cmp), tol);
    if (badSamples > 0) {
        std::printf("over tolerance: %lld samples, frames %lld..%lld\n", static_cast<long long>(badSamples),
                    static_cast<long long>(firstBad), static_cast<long long>(lastBad));
    }
    if (maxDiff > tol) pass = false;

    // Seek checks: a seek to logical position L must reproduce the reference from L onward.
    const int64_t points[3] = {0, logical / 2, (logical * 9) / 10};
    for (int64_t L : points) {
        if (L >= refFrames) continue;
        std::unique_ptr<Decoder> s;
        TrackInfo si;
        if (createDecoder(f.get(), 0, -1, s, si) != kOk) {
            pass = false;
            break;
        }
        if (s->seekFrame(start + L) != kOk) {
            std::printf("FAIL seek to %lld\n", static_cast<long long>(L));
            pass = false;
            break;
        }
        std::vector<float> chunk(static_cast<size_t>(4096 * 2));
        const int64_t got = s->decodeFrames(chunk.data(), 4096);
        double d = 0.0;
        const int64_t n = std::min(got, refFrames - L);
        for (int64_t i = 0; i < n * 2; ++i) {
            d = std::max(d, std::fabs(static_cast<double>(chunk[static_cast<size_t>(i)]) -
                                      static_cast<double>(ref[static_cast<size_t>(L * 2 + i)])));
        }
        std::printf("seek L=%lld frames=%lld maxdiff=%.6g\n", static_cast<long long>(L), static_cast<long long>(n), d);
        if (n <= 0 || d > tol) pass = false;
    }

    std::printf("%s\n", pass ? "PASS" : "FAIL");
    return pass ? 0 : 1;
}

int cmdError(const char* path) {
    Fd f(path);
    if (!f.ok()) return 2;
    std::unique_ptr<Decoder> dec;
    TrackInfo info;
    const ErrorCode ec = createDecoder(f.get(), 0, -1, dec, info);
    std::printf("error ec=%d\n", ec);
    return (ec != kOk && !dec) ? 0 : 1;
}

// Prints raw decoded frames [from, from + count) for inspection.
int cmdDump(const char* path, int64_t from, int64_t count) {
    Fd f(path);
    if (!f.ok()) return 2;
    std::unique_ptr<Decoder> dec;
    TrackInfo info;
    if (createDecoder(f.get(), 0, -1, dec, info) != kOk) return 1;
    std::vector<float> raw;
    if (decodeToEnd(*dec, raw) < 0) return 1;
    for (int64_t i = from; i < from + count && i * 2 + 1 < static_cast<int64_t>(raw.size()); ++i) {
        std::printf("%lld %.6f %.6f\n", static_cast<long long>(i), raw[static_cast<size_t>(i * 2)],
                    raw[static_cast<size_t>(i * 2 + 1)]);
    }
    return 0;
}

int cmdWindow(const char* path, int64_t bytes) {
    Fd f(path);
    if (!f.ok()) return 2;
    std::unique_ptr<Decoder> dec;
    TrackInfo info;
    const ErrorCode ec = createDecoder(f.get(), 0, bytes, dec, info);
    if (ec != kOk) {
        std::printf("window open ec=%d (acceptable)\n", ec);
        return 0;
    }
    std::vector<float> raw;
    const int64_t frames = decodeToEnd(*dec, raw);
    std::printf("window frames=%lld (no crash)\n", static_cast<long long>(frames));
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: decoder_test info|decode|error|window ...\n");
        return 2;
    }
    const std::string mode = argv[1];
    if (mode == "info") return cmdInfo(argv[2]);
    if (mode == "error") return cmdError(argv[2]);
    if (mode == "decode" && argc >= 5) return cmdDecode(argv[2], argv[3], std::atof(argv[4]));
    if (mode == "window" && argc >= 4) return cmdWindow(argv[2], std::atoll(argv[3]));
    if (mode == "dump" && argc >= 5) return cmdDump(argv[2], std::atoll(argv[3]), std::atoll(argv[4]));
    std::fprintf(stderr, "bad arguments\n");
    return 2;
}
