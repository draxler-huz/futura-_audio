#include "ThreadSafeRingBuffer.h"
#include <algorithm>
#include <cstring>

ThreadSafeRingBuffer::ThreadSafeRingBuffer(size_t capacity)
    : mBuffer(capacity), mCapacity(capacity) {}

size_t ThreadSafeRingBuffer::write(const float *data, size_t count) {
    size_t space = mCapacity - (mWriteIndex.load() - mReadIndex.load()) % mCapacity;
    size_t toWrite = std::min(count, space);

    for (size_t i = 0; i < toWrite; i++) {
        mBuffer[(mWriteIndex.load() + i) % mCapacity] = data[i];
    }
    mWriteIndex.fetch_add(toWrite);
    return toWrite;
}

size_t ThreadSafeRingBuffer::read(float *data, size_t count) {
    size_t readable = availableRead();
    size_t toRead = std::min(count, readable);

    for (size_t i = 0; i < toRead; i++) {
        data[i] = mBuffer[(mReadIndex.load() + i) % mCapacity];
    }
    mReadIndex.fetch_add(toRead);
    return toRead;
}

size_t ThreadSafeRingBuffer::availableRead() const {
    return (mWriteIndex.load() - mReadIndex.load()) % mCapacity;
}

size_t ThreadSafeRingBuffer::availableWrite() const {
    return mCapacity - availableRead();
}

void ThreadSafeRingBuffer::clear() {
    mReadIndex.store(0);
    mWriteIndex.store(0);
}