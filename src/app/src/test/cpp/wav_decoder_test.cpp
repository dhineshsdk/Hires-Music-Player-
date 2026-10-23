// Host test for WavDecoder. Build: g++ -std=c++20 -I../../main/cpp/decoders wav_decoder_test.cpp ../../main/cpp/decoders/WavDecoder.cpp
#include <cstdio>
#include <cstring>
#include <vector>
#include "WavDecoder.hpp"

using namespace Audiophile::Decoders;

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FAIL line %d: %s\n", __LINE__, #cond); ++failures; } } while (0)

using Bytes = std::vector<uint8_t>;
static void Put16(Bytes& b, uint16_t v) { b.push_back(v & 0xff); b.push_back(v >> 8); }
static void Put32(Bytes& b, uint32_t v) { for (int i = 0; i < 4; ++i) b.push_back((v >> (8 * i)) & 0xff); }
static void Put64(Bytes& b, uint64_t v) { Put32(b, static_cast<uint32_t>(v)); Put32(b, static_cast<uint32_t>(v >> 32)); }
static void PutTag(Bytes& b, const char* t) { b.insert(b.end(), t, t + 4); }

struct Spec {
    uint16_t tag = 1, channels = 2, bits = 16, validBits = 0;
    uint32_t rate = 44100;
    bool rf64 = false;
    bool streamedSize = false;   // data chunk size 0
    bool oddChunkFirst = false;  // a 3-byte LIST chunk (padded) before fmt
};

// Samples are a counting byte pattern so every byte position is distinct.
static Bytes MakeWav(const Spec& s, size_t frames, Bytes* pcmOut = nullptr) {
    const size_t sampleBytes = s.bits / 8;
    const size_t frameBytes = sampleBytes * s.channels;
    Bytes pcm(frames * frameBytes);
    for (size_t i = 0; i < pcm.size(); ++i) pcm[i] = static_cast<uint8_t>((i * 7 + 3) & 0xff);
    if (pcmOut) *pcmOut = pcm;

    Bytes b;
    PutTag(b, s.rf64 ? "RF64" : "RIFF");
    Put32(b, s.rf64 ? 0xFFFFFFFFu : 0);  // patched below for RIFF
    PutTag(b, "WAVE");
    if (s.rf64) {
        PutTag(b, "ds64");
        Put32(b, 28);
        Put64(b, 0);
        Put64(b, pcm.size());
        Put64(b, frames);
        Put32(b, 0);
    }
    if (s.oddChunkFirst) {
        PutTag(b, "LIST");
        Put32(b, 3);
        b.push_back(1); b.push_back(2); b.push_back(3); b.push_back(0);  // 3 bytes + pad
    }
    const bool ext = s.validBits != 0;
    PutTag(b, "fmt ");
    Put32(b, ext ? 40 : 16);
    Put16(b, ext ? 0xFFFE : s.tag);
    Put16(b, s.channels);
    Put32(b, s.rate);
    Put32(b, static_cast<uint32_t>(s.rate * frameBytes));
    Put16(b, static_cast<uint16_t>(frameBytes));
    Put16(b, s.bits);
    if (ext) {
        Put16(b, 22);
        Put16(b, s.validBits);
        Put32(b, 3);
        Put16(b, s.tag);  // sub-format GUID starts with the format tag
        for (int i = 0; i < 14; ++i) b.push_back(i == 0 ? 0x00 : 0x10);
    }
    PutTag(b, "data");
    Put32(b, s.streamedSize ? 0 : (s.rf64 ? 0xFFFFFFFFu : static_cast<uint32_t>(pcm.size())));
    b.insert(b.end(), pcm.begin(), pcm.end());
    if (!s.rf64) {
        const uint32_t riffSize = static_cast<uint32_t>(b.size() - 8);
        for (int i = 0; i < 4; ++i) b[4 + i] = (riffSize >> (8 * i)) & 0xff;
    }
    return b;
}

static DecodeError Open(WavDecoder& d, const Bytes& b, MemorySource& src) { (void)b; return d.Open(src); }

static void CheckBitExact(const Spec& s, SampleFormat expect, uint16_t expectValid) {
    Bytes pcm;
    Bytes file = MakeWav(s, 1000, &pcm);
    MemorySource src(file.data(), file.size());
    WavDecoder d;
    CHECK(Open(d, file, src) == DecodeError::None);
    const StreamFormat& f = d.Format();
    CHECK(f.format == expect);
    CHECK(f.sampleRate == s.rate);
    CHECK(f.channels == s.channels);
    CHECK(f.validBits == expectValid);
    CHECK(f.totalFrames == 1000);
    Bytes out(pcm.size());
    size_t total = 0;
    while (true) {  // read in odd-sized chunks
        const size_t n = d.ReadFrames(out.data() + total * f.BytesPerFrame(), 333);
        if (n == 0) break;
        total += n;
    }
    CHECK(total == 1000);
    CHECK(out == pcm);  // bit-exact
    CHECK(d.ReadFrames(out.data(), 10) == 0);
}

int main() {
    { Spec s; s.bits = 16; CheckBitExact(s, SampleFormat::Int16, 16); }
    { Spec s; s.bits = 24; s.rate = 96000; CheckBitExact(s, SampleFormat::Int24, 24); }
    { Spec s; s.bits = 24; s.rate = 192000; s.channels = 1; CheckBitExact(s, SampleFormat::Int24, 24); }
    { Spec s; s.bits = 32; CheckBitExact(s, SampleFormat::Int32, 32); }
    { Spec s; s.tag = 3; s.bits = 32; CheckBitExact(s, SampleFormat::Float32, 32); }
    { Spec s; s.tag = 3; s.bits = 64; CheckBitExact(s, SampleFormat::Float64, 64); }
    { Spec s; s.bits = 24; s.validBits = 20; CheckBitExact(s, SampleFormat::Int24, 20); }   // extensible
    { Spec s; s.bits = 24; s.rf64 = true; CheckBitExact(s, SampleFormat::Int24, 24); }
    { Spec s; s.bits = 16; s.oddChunkFirst = true; CheckBitExact(s, SampleFormat::Int16, 16); }
    { Spec s; s.bits = 16; s.streamedSize = true; CheckBitExact(s, SampleFormat::Int16, 16); }

    // Seeking
    {
        Spec s; s.bits = 24;
        Bytes pcm; Bytes file = MakeWav(s, 500, &pcm);
        MemorySource src(file.data(), file.size());
        WavDecoder d;
        CHECK(d.Open(src) == DecodeError::None);
        CHECK(d.SeekToFrame(200));
        CHECK(d.PositionFrames() == 200);
        Bytes out(10 * d.Format().BytesPerFrame());
        CHECK(d.ReadFrames(out.data(), 10) == 10);
        CHECK(std::memcmp(out.data(), pcm.data() + 200 * 6, out.size()) == 0);
        CHECK(d.SeekToFrame(500));
        CHECK(d.ReadFrames(out.data(), 10) == 0);
        CHECK(!d.SeekToFrame(501));
    }

    // A torn trailing frame is dropped, never played as noise
    {
        Spec s; s.bits = 24;
        Bytes file = MakeWav(s, 100);
        file.resize(file.size() - 2);  // cut the last frame short
        MemorySource src(file.data(), file.size());
        WavDecoder d;
        CHECK(d.Open(src) == DecodeError::None);
        CHECK(d.Format().totalFrames == 99);
    }

    // Rejections
    {
        Spec s; s.bits = 8;
        Bytes file = MakeWav(s, 10);
        MemorySource src(file.data(), file.size());
        WavDecoder d;
        CHECK(d.Open(src) == DecodeError::Unsupported);
    }
    {
        Spec s; s.tag = 2; s.bits = 16;  // ADPCM tag
        Bytes file = MakeWav(s, 10);
        MemorySource src(file.data(), file.size());
        WavDecoder d;
        CHECK(d.Open(src) == DecodeError::Unsupported);
    }
    {
        Bytes notWav = {'f', 'L', 'a', 'C', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        MemorySource src(notWav.data(), notWav.size());
        WavDecoder d;
        CHECK(d.Open(src) == DecodeError::NotThisFormat);
    }
    {
        Bytes tiny = {'R', 'I'};
        MemorySource src(tiny.data(), tiny.size());
        WavDecoder d;
        CHECK(d.Open(src) == DecodeError::NotThisFormat);
    }
    {
        Spec s; s.bits = 16;
        Bytes file = MakeWav(s, 10);
        file.resize(30);  // cut inside the fmt chunk
        MemorySource src(file.data(), file.size());
        WavDecoder d;
        CHECK(d.Open(src) != DecodeError::None);
    }

    std::printf(failures ? "FAILED\n" : "all passed\n");
    return failures ? 1 : 0;
}
