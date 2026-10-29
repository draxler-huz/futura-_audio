// Single-producer, single-consumer lock-free ring buffer.
//
// Producer: the controller thread (callers serialise their own pushes).
// Consumer: the audio thread only.
//
// Neither side takes a lock, allocates, or blocks. Capacity is fixed at compile time.
#pragma once

#include <atomic>
#include <cstddef>
#include <type_traits>

namespace futura {

template <typename T, size_t kCapacity>
class SpscQueue {
    static_assert(kCapacity >= 2 && (kCapacity & (kCapacity - 1)) == 0, "capacity must be a power of two");
    static_assert(std::is_trivially_copyable<T>::value, "queued items must be trivially copyable");
    static_assert(std::atomic<size_t>::is_always_lock_free, "size_t atomics must be lock-free");

public:
    // Producer only. Returns false when the queue is full; nothing is written in that case.
    bool push(const T& item) {
        const size_t tail = tail_.load(std::memory_order_relaxed);
        const size_t head = head_.load(std::memory_order_acquire);
        if (tail - head == kCapacity) return false;
        buffer_[tail & kMask] = item;
        tail_.store(tail + 1, std::memory_order_release);
        return true;
    }

    // Consumer only. Returns false when the queue is empty.
    bool pop(T& out) {
        const size_t head = head_.load(std::memory_order_relaxed);
        const size_t tail = tail_.load(std::memory_order_acquire);
        if (head == tail) return false;
        out = buffer_[head & kMask];
        head_.store(head + 1, std::memory_order_release);
        return true;
    }

private:
    static constexpr size_t kMask = kCapacity - 1;

    alignas(64) std::atomic<size_t> head_{0};
    alignas(64) std::atomic<size_t> tail_{0};
    T buffer_[kCapacity]{};
};

}  // namespace futura
