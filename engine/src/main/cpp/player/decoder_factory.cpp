// Format sniffing, codec names, and the FdReader implementation shared by every decoder.
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>

#include "decoder_common.h"
#include "ogg_demux.h"

namespace futura::player {

// ---------------------------------------------------------------------------------------------
// FdReader
// ---------------------------------------------------------------------------------------------

FdReader::~FdReader() {
    if (fd_ >= 0) ::close(fd_);
}

ErrorCode FdReader::open(int fd, int64_t offset, int64_t length) {
    if (fd_ >= 0) ::close(fd_);
    fd_ = -1;
    if (fd < 0 || offset < 0) return kIoError;

    const int dupFd = ::dup(fd);
    if (dupFd < 0) return kIoError;

    struct stat st {};
    if (::fstat(dupFd, &st) != 0) {
        ::close(dupFd);
        return kIoError;
    }
    const int64_t fileSize = static_cast<int64_t>(st.st_size);
    if (offset > fileSize) {
        ::close(dupFd);
        return kEmptyTrack;
    }
    base_ = offset;
    const int64_t available = fileSize - offset;
    size_ = (length < 0 || length > available) ? available : length;
    fd_ = dupFd;
    return kOk;
}

int64_t FdReader::readAt(int64_t pos, void* dst, int64_t n) const {
    if (fd_ < 0 || pos < 0 || n < 0) return -1;
    if (pos >= size_) return 0;
    int64_t want = n;
    if (pos + want > size_) want = size_ - pos;

    uint8_t* out = static_cast<uint8_t*>(dst);
    int64_t done = 0;
    while (done < want) {
        const ssize_t got = ::pread(fd_, out + done, static_cast<size_t>(want - done),
                                    static_cast<off_t>(base_ + pos + done));
        if (got < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (got == 0) break;  // the file shrank under us; return what we have
        done += got;
    }
    return done;
}

// ---------------------------------------------------------------------------------------------
// Codec names
// ---------------------------------------------------------------------------------------------

const char* codecName(Codec codec) {
    switch (codec) {
        case Codec::kWav:
            return "WAV";
        case Codec::kFlac:
            return "FLAC";
        case Codec::kMp3:
            return "MP3";
        case Codec::kAac:
            return "AAC (MediaCodec)";
        case Codec::kAlac:
            return "ALAC (Apple decoder)";
        case Codec::kVorbis:
            return "Ogg Vorbis";
        case Codec::kOpus:
            return "Opus";
        case Codec::kUnknown:
        default:
            return "Unknown";
    }
}

// ---------------------------------------------------------------------------------------------
// Sniffing
// ---------------------------------------------------------------------------------------------

namespace {

constexpr int64_t kSniffBytes = 64;

// MPEG audio frame sync with layer III: 11 bits of 1, version not reserved, layer bits 01.
bool isMpegLayer3Sync(const uint8_t* h) {
    return h[0] == 0xFF && (h[1] & 0xE0) == 0xE0 && ((h[1] >> 3) & 0x3) != 1 && ((h[1] >> 1) & 0x3) == 1;
}

// Returns the codec family of the first Ogg packet ("OpusHead" or "\x01vorbis"), or kUnknown.
Codec sniffOggCodec(const FdReader& reader) {
    OggPacketReader ogg(reader);
    std::vector<uint8_t> packet;
    int64_t granule = -1;
    if (ogg.nextPacket(packet, granule) != 1) return Codec::kUnknown;
    if (packet.size() >= 8 && std::memcmp(packet.data(), "OpusHead", 8) == 0) return Codec::kOpus;
    if (packet.size() >= 7 && packet[0] == 0x01 && std::memcmp(packet.data() + 1, "vorbis", 6) == 0) {
        return Codec::kVorbis;
    }
    return Codec::kUnknown;
}

}  // namespace

ErrorCode createDecoder(int fd, int64_t offset, int64_t length, std::unique_ptr<Decoder>& out, TrackInfo& info) {
    out.reset();
    info = TrackInfo{};

    FdReader reader;
    ErrorCode ec = reader.open(fd, offset, length);
    if (ec != kOk) return ec;
    if (reader.size() == 0) return kEmptyTrack;

    uint8_t head[kSniffBytes] = {};
    const int64_t n = reader.readAt(0, head, kSniffBytes);
    if (n < 4) return kUnsupportedFormat;

    if (n >= 12 && std::memcmp(head, "RIFF", 4) == 0 && std::memcmp(head + 8, "WAVE", 4) == 0) {
        out = makeWavDecoder();
    } else if (std::memcmp(head, "fLaC", 4) == 0) {
        out = makeFlacDecoder();
    } else if (std::memcmp(head, "OggS", 4) == 0) {
        const Codec codec = sniffOggCodec(reader);
        if (codec == Codec::kOpus) {
            out = makeOpusDecoder();
        } else if (codec == Codec::kVorbis) {
            out = makeVorbisDecoder();
        } else {
            return kUnsupportedFormat;
        }
    } else if (n >= 3 && std::memcmp(head, "ID3", 3) == 0) {
        out = makeMp3Decoder();  // ID3v2 is skipped inside the MP3 decoder
    } else if (n >= 4 && isMpegLayer3Sync(head)) {
        out = makeMp3Decoder();
    } else if (n >= 8 && std::memcmp(head + 4, "ftyp", 4) == 0) {
#if defined(__ANDROID__)
        return makeMp4Decoder(fd, offset, length, out, info);  // AAC or ALAC, chosen from the track MIME
#else
        return kUnsupportedFormat;  // MP4 needs the Android NDK media stack; not available on the host
#endif
    } else if (n >= 4 && (std::memcmp(head, "DSD ", 4) == 0 || std::memcmp(head, "FRM8", 4) == 0)) {
        // DSD (DSF / DFF) is deferred to Stage 3. See STATUS.md.
        return kUnsupportedFormat;
    } else {
        return kUnsupportedFormat;
    }

    if (!out) return kUnsupportedFormat;
    ec = out->open(fd, offset, length, info);
    if (ec != kOk) {
        out.reset();
        return ec;
    }
    return kOk;
}

}  // namespace futura::player
