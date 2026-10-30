// Minimal Ogg page and packet reader (RFC 3533) for the Opus decoder.
// Reads one logical stream: the serial number of the first page seen. Other streams are skipped.
// CRCs are not verified. Corrupt pages are reported as decode errors by the caller.
#pragma once

#include <cstdint>
#include <vector>

#include "decoder_common.h"

namespace futura::player {

class OggPacketReader {
public:
    explicit OggPacketReader(const FdReader& reader);

    // Next complete packet of the stream. Returns 1 when `packet` was filled, 0 at end of stream,
    // or a negative ErrorCode for a truncated or malformed page.
    // `granule` receives the granule position of the page that completed the packet (-1 if none).
    int nextPacket(std::vector<uint8_t>& packet, int64_t& granule);

    // Granule position of the last page of the stream that has one, or -1 when there is none.
    // Searches the tail of the file only.
    int64_t lastGranule() const;

    // Restarts from the beginning of the window.
    void rewind();

private:
    // Loads the next page of our stream. Returns 1 when loaded, 0 at end of file, -1 for a malformed page.
    int loadPage();

    const FdReader& reader_;
    int64_t pos_ = 0;           // window offset of the next page header
    bool haveSerial_ = false;
    uint32_t serial_ = 0;

    // Current page.
    std::vector<uint8_t> segments_;
    std::vector<uint8_t> payload_;
    size_t segIndex_ = 0;
    size_t payloadPos_ = 0;
    int64_t pageGranule_ = -1;

    // Packet being assembled across segments and pages.
    std::vector<uint8_t> partial_;
};

}  // namespace futura::player
