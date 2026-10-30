// Single-producer, single-consumer ring of interleaved stereo float frames.
//
// Producer: the decode thread (decoded PCM) or the audio callback (the audio tap).
// Consumer: the audio callback (playback) or a future meter reader (the tap).
//
// Neither side takes a lock, allocates or blocks. Memory is allocated only by allocate(), which runs on the
// controller thread while the ring is idle. Positions are monotonically increasing counters, so full and empty
// are told apart without wasting a slot.
//
// Rules: write() and read() never wait. write() stores as many frames as fit and returns that count, so the
// audio tap can drop frames when it is full without ever blocking the callback.
#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>

namespace futura::player {

class PcmRing {
public:
    // Controller thread only, and only while neither the producer nor the consumer is active.
    void allocate(size_t capacityFrames) {
        buffer_.reset(new float[capacityFrames * 2]());  // zero-filled
        capacity_ = capacityFrames;
        resetIndices();
    }

    // Controller thread only, and only while neither side is active. Drops everything in the ring.
    void resetIndices() {
        write_.store(0, std::memory_order_relaxed);
        read_.store(0, std::memory_order_relaxed);
    }

    size_t capacity() const { return capacity_; }

    // Producer only. Frames the producer can add without overwriting unread data.
    size_t writableFrames() const {
        const size_t w = write_.load(std::memory_order_relaxed);
        const size_t r = read_.load(std::memory_order_acquire);
        return capacity_ - (w - r);
    }

    // Consumer only. Frames waiting to be read.
    size_t readableFrames() const {
        const size_t w = write_.load(std::memory_order_acquire);
        const size_t r = read_.load(std::memory_order_relaxed);
        return w - r;
    }

    // Producer only. Copies up to `frames` frames from `src` (2 floats per frame). Returns frames stored.
    size_t write(const float* src, size_t frames) {
        if (capacity_ == 0) return 0;
        const size_t w = write_.load(std::memory_order_relaxed);
        const size_t r = read_.load(std::memory_order_acquire);
        const size_t freeFrames = capacity_ - (w - r);
        const size_t n = frames < freeFrames ? frames : freeFrames;
        copyIn(w, src, n);
        write_.store(w + n, std::memory_order_release);
        return n;
    }

    // Consumer only. Copies up to `frames` frames into `dst`. Returns frames copied. Never blocks.
    size_t read(float* dst, size_t frames) {
        if (capacity_ == 0) return 0;
        const size_t r = read_.load(std::memory_order_relaxed);
        const size_t w = write_.load(std::memory_order_acquire);
        const size_t avail = w - r;
        const size_t n = frames < avail ? frames : avail;
        copyOut(r, dst, n);
        read_.store(r + n, std::memory_order_release);
        return n;
    }

private:
    void copyIn(size_t position, const float* src, size_t frames) {
        size_t index = position % capacity_;
        size_t first = capacity_ - index;
        if (first > frames) first = frames;
        std::memcpy(buffer_.get() + index * 2, src, first * 2 * sizeof(float));
        if (frames > first) {
            std::memcpy(buffer_.get(), src + first * 2, (frames - first) * 2 * sizeof(float));
        }
    }

    void copyOut(size_t position, float* dst, size_t frames) {
        size_t index = position % capacity_;
        size_t first = capacity_ - index;
        if (first > frames) first = frames;
        std::memcpy(dst, buffer_.get() + index * 2, first * 2 * sizeof(float));
        if (frames > first) {
            std::memcpy(dst + first * 2, buffer_.get(), (frames - first) * 2 * sizeof(float));
        }
    }

    std::unique_ptr<float[]> buffer_;
    size_t capacity_ = 0;
    alignas(64) std::atomic<size_t> write_{0};
    alignas(64) std::atomic<size_t> read_{0};
};

}  // namespace futura::player
