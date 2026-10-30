// Minimal Ogg page and packet reader. See ogg_demux.h.
#include "ogg_demux.h"

#include <algorithm>
#include <cstring>

namespace futura::player {
namespace {

constexpr int64_t kPageHeaderBytes = 27;
constexpr int64_t kMaxPageBytes = kPageHeaderBytes + 255 + 255 * 255;  // header, segment table, payload
constexpr int64_t kTailScanBytes = 2 * kMaxPageBytes;

uint64_t readLe64(const uint8_t* p) {
    return static_cast<uint64_t>(readLe32(p)) | (static_cast<uint64_t>(readLe32(p + 4)) << 32);
}

}  // namespace

OggPacketReader::OggPacketReader(const FdReader& reader) : reader_(reader) {}

void OggPacketReader::rewind() {
    pos_ = 0;
    haveSerial_ = false;
    segments_.clear();
    payload_.clear();
    segIndex_ = 0;
    payloadPos_ = 0;
    pageGranule_ = -1;
    partial_.clear();
}

int OggPacketReader::loadPage() {
    for (;;) {
        if (pos_ + kPageHeaderBytes > reader_.size()) return 0;

        uint8_t hdr[kPageHeaderBytes];
        if (reader_.readAt(pos_, hdr, kPageHeaderBytes) != kPageHeaderBytes) return -1;
        if (std::memcmp(hdr, "OggS", 4) != 0) return -1;

        const size_t segCount = hdr[26];
        std::vector<uint8_t> table(segCount);
        if (segCount > 0 && reader_.readAt(pos_ + kPageHeaderBytes, table.data(), static_cast<int64_t>(segCount)) !=
                                static_cast<int64_t>(segCount)) {
            return -1;
        }
        size_t payloadLen = 0;
        for (uint8_t s : table) payloadLen += s;

        const int64_t pageEnd = pos_ + kPageHeaderBytes + static_cast<int64_t>(segCount + payloadLen);
        if (pageEnd > reader_.size()) return -1;  // truncated

        const uint32_t serial = readLe32(hdr + 14);
        const uint64_t granule = readLe64(hdr + 6);
        const int64_t pageStart = pos_;
        pos_ = pageEnd;

        if (!haveSerial_) {
            serial_ = serial;
            haveSerial_ = true;
        }
        if (serial != serial_) continue;  // another logical stream in a multiplexed file: skip it

        segments_ = std::move(table);
        payload_.resize(payloadLen);
        if (payloadLen > 0 && reader_.readAt(pageStart + kPageHeaderBytes + static_cast<int64_t>(segCount),
                                             payload_.data(), static_cast<int64_t>(payloadLen)) !=
                                  static_cast<int64_t>(payloadLen)) {
            return -1;
        }
        pageGranule_ = granule == ~uint64_t{0} ? -1 : static_cast<int64_t>(granule);
        segIndex_ = 0;
        payloadPos_ = 0;
        return 1;
    }
}

int OggPacketReader::nextPacket(std::vector<uint8_t>& packet, int64_t& granule) {
    for (;;) {
        if (segIndex_ >= segments_.size()) {
            const int r = loadPage();
            if (r == 0) {
                // A packet cut off by the end of the file is an error, not a silent end.
                return partial_.empty() ? 0 : static_cast<int>(kDecodeError);
            }
            if (r < 0) return static_cast<int>(kDecodeError);
            continue;
        }

        const uint8_t seg = segments_[segIndex_++];
        if (payloadPos_ + seg > payload_.size()) return static_cast<int>(kDecodeError);
        partial_.insert(partial_.end(), payload_.begin() + static_cast<std::ptrdiff_t>(payloadPos_),
                        payload_.begin() + static_cast<std::ptrdiff_t>(payloadPos_ + seg));
        payloadPos_ += seg;

        if (seg < 255) {  // a lacing value below 255 ends the packet
            packet = partial_;
            partial_.clear();
            granule = pageGranule_;
            return 1;
        }
    }
}

int64_t OggPacketReader::lastGranule() const {
    const int64_t size = reader_.size();
    if (size < kPageHeaderBytes) return -1;

    const int64_t window = std::min(size, kTailScanBytes);
    const int64_t start = size - window;
    std::vector<uint8_t> tail(static_cast<size_t>(window));
    if (reader_.readAt(start, tail.data(), window) != window) return -1;

    // Scan backwards for a complete page header whose granule is set. The bounds checks reject most false matches.
    for (int64_t i = window - kPageHeaderBytes; i >= 0; --i) {
        const uint8_t* h = tail.data() + i;
        if (std::memcmp(h, "OggS", 4) != 0) continue;

        const int64_t segCount = h[26];
        if (i + kPageHeaderBytes + segCount > window) continue;
        int64_t payloadLen = 0;
        for (int64_t s = 0; s < segCount; ++s) payloadLen += h[kPageHeaderBytes + s];
        if (i + kPageHeaderBytes + segCount + payloadLen > window) continue;

        // Only the end-of-stream page carries the stream's final granule. A truncated file has none, so its
        // length is unknown rather than the granule of some earlier page.
        if ((h[5] & 0x04) == 0) continue;
        const uint64_t granule = readLe64(h + 6);
        if (granule == ~uint64_t{0}) continue;
        if (haveSerial_ && readLe32(h + 14) != serial_) continue;
        return static_cast<int64_t>(granule);
    }
    return -1;
}

}  // namespace futura::player
