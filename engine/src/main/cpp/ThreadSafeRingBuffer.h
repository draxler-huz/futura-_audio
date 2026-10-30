#pragma once

#include <atomic>
#include <vector>
#include <algorithm>
#include <cmath>

class ThreadSafeRingBuffer {
public:
    explicit ThreadSafeRingBuffer(size_t capacity);

    size_t write(const float *data, size_t count);
    size_t read(float *data, size_t count);
    size_t availableRead() const;
    size_t availableWrite() const;
    void clear();

private:
    std::vector<float> mBuffer;
    size_t mCapacity;
    std::atomic<size_t> mReadIndex{0};
    std::atomic<size_t> mWriteIndex{0};
};