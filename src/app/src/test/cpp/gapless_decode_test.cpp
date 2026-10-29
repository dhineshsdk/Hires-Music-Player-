// Host test for gapless trimming: the tag parsers, and FfmpegDecoder's length on real encoded files.
// Usage: gapless_decode_test                          (parser checks only)
//        gapless_decode_test <file> <source.raw>      (decode check against the PCM the file was encoded from:
//        float32 stereo from ffmpeg's lavfi sine source)
// The decoder must return exactly the music: no encoder delay at the start, no padding at the end,
// and every frame must line up with the source (so the start was cut at the right place).
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>
#include "FfmpegDecoder.hpp"
#include "GaplessInfo.hpp"

using namespace Audiophile::Decoders;

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FAIL line %d: %s\n", __LINE__, #cond); ++failures; } } while (0)

static std::vector<uint8_t> Slurp(const char* path) {
    std::vector<uint8_t> v;
    FILE* f = std::fopen(path, "rb");
    if (!f) return v;
    uint8_t buf[65536];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof buf, f)) > 0) v.insert(v.end(), buf, buf + n);
    std::fclose(f);
    return v;
}

static void TestParsers() {
    // A hand-built MPEG-1 Layer III stereo frame: header, 32 bytes side info, "Xing" tag, LAME extension.
    std::vector<uint8_t> f(512, 0);
    const uint8_t header[4] = {0xFF, 0xFB, 0x90, 0x00};
    std::memcpy(f.data() + 7, header, 4);          // some junk before the first frame
    const size_t tag = 7 + 4 + 32;
    std::memcpy(f.data() + tag, "Info", 4);
    f[tag + 7] = 0x0F;                              // frames, bytes, toc, quality present
    f[tag + 11] = 100;                              // 100 frames
    const size_t lame = tag + 8 + 4 + 4 + 100 + 4;
    std::memcpy(f.data() + lame, "LAME3.100", 9);
    f[lame + 21] = 0x24;                            // delay 0x240 = 576
    f[lame + 22] = 0x05;                            // padding 0x57C = 1404 (high nibble 0, low nibble 5)
    f[lame + 23] = 0x7C;
    GaplessInfo g = ParseLameHeader(f.data(), f.size());
    CHECK(g.present && g.delay == 0x240 && g.padding == 0x57C);
    CHECK(g.valid == 100u * 1152 - 576 - 1404);
    // MPEG-2 uses 576 samples per frame and a shorter side info.
    f.assign(512, 0);
    const uint8_t header2[4] = {0xFF, 0xF3, 0x90, 0x00};
    std::memcpy(f.data(), header2, 4);
    const size_t tag2 = 4 + 17;
    std::memcpy(f.data() + tag2, "Xing", 4);
    f[tag2 + 7] = 0x01;
    f[tag2 + 11] = 10;
    const size_t lame2 = tag2 + 8 + 4;
    std::memcpy(f.data() + lame2, "LAME3.99r", 9);
    f[lame2 + 22] = 0x10; f[lame2 + 23] = 0x20; f[lame2 + 21] = 0x01;
    g = ParseLameHeader(f.data(), f.size());
    CHECK(g.present && g.delay == 0x011 && g.padding == 0x020);
    CHECK(g.valid == 10u * 576 - 0x011 - 0x020);
    // No tag, an all-zero tag, truncated input.
    std::vector<uint8_t> plain(512, 0);
    std::memcpy(plain.data(), header, 4);
    CHECK(!ParseLameHeader(plain.data(), plain.size()).present);
    std::memcpy(plain.data() + 4 + 32, "Info", 4);
    CHECK(!ParseLameHeader(plain.data(), plain.size()).present);
    CHECK(!ParseLameHeader(f.data(), 30).present);
    CHECK(!ParseLameHeader(nullptr, 0).present);

    const uint8_t id3[10] = {'I', 'D', '3', 4, 0, 0, 0, 0, 0x02, 0x01};
    CHECK(Id3v2Size(id3, 10) == 10 + 257);
    const uint8_t notId3[10] = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 0, 0};
    CHECK(Id3v2Size(notId3, 10) == 0);

    g = ParseItunSmpb(" 00000000 00000840 000001CA 000000000025A5E0 00000000 00000000 00000000 00000000");
    CHECK(g.present && g.delay == 0x840 && g.padding == 0x1CA && g.valid == 0x25A5E0);
    CHECK(!ParseItunSmpb("").present);
    CHECK(!ParseItunSmpb("garbage").present);
    CHECK(!ParseItunSmpb(" 00000000 00000840 000001CA 0000000000000000").present);
    CHECK(!ParseItunSmpb(nullptr).present);
}

static void TestDecode(const char* path, const char* sourcePath) {
    const auto file = Slurp(path);
    const auto raw = Slurp(sourcePath);
    CHECK(!file.empty() && !raw.empty());
    const uint64_t music = raw.size() / 8;
    const float* ref = reinterpret_cast<const float*>(raw.data());
    MemorySource source(file.data(), file.size());
    FfmpegDecoder dec;
    CHECK(dec.Open(source) == DecodeError::None);
    const StreamFormat& f = dec.Format();
    CHECK(f.format == SampleFormat::Float32 && f.channels == 2);
    CHECK(f.totalFrames == music);

    std::vector<float> pcm;
    std::vector<float> buf(4096 * 2);
    for (;;) {
        const size_t n = dec.ReadFrames(buf.data(), 4096);
        if (n == 0) break;
        pcm.insert(pcm.end(), buf.begin(), buf.begin() + static_cast<long>(n * 2));
    }
    CHECK(pcm.size() / 2 == music);
    CHECK(dec.PositionFrames() == music);

    // Start, middle and end all line up with the source: the correlation peaks at lag 0 (a shift
    // of one frame would be the encoder delay left in, or cut twice) and the error is codec noise.
    auto correlation = [&](size_t from, size_t count, int lag) {
        double sum = 0;
        for (size_t i = from; i < from + count && i < pcm.size() / 2; ++i) {
            const int64_t j = static_cast<int64_t>(i) + lag;
            if (j < 0 || static_cast<uint64_t>(j) >= music) continue;
            sum += static_cast<double>(pcm[i * 2]) * ref[static_cast<size_t>(j) * 2];
        }
        return sum;
    };
    auto meanErr = [&](size_t from, size_t count) {
        double sum = 0;
        for (size_t i = from; i < from + count; ++i) sum += std::fabs(pcm[i * 2] - ref[i * 2]);
        return sum / static_cast<double>(count);
    };
    for (const size_t from : {size_t{300}, static_cast<size_t>(music / 2), static_cast<size_t>(music - 2300)}) {
        CHECK(meanErr(from, 2000) < 0.03);
        const double at0 = correlation(from, 2000, 0);
        for (const int lag : {-2, -1, 1, 2}) CHECK(correlation(from, 2000, lag) < at0);
    }

    // Seeking is relative to the first music frame.
    CHECK(dec.SeekToFrame(music / 2));
    const size_t n = dec.ReadFrames(buf.data(), 1000);
    CHECK(n > 0);
    uint64_t rest = n;
    size_t m;
    while ((m = dec.ReadFrames(buf.data(), 4096)) > 0) rest += m;
    CHECK(rest + 2048 >= music - music / 2 && rest <= music - music / 2 + 2048);
    CHECK(dec.PositionFrames() <= music);
    CHECK(dec.SeekToFrame(0));
    CHECK(dec.ReadFrames(buf.data(), 1000) > 0);
    float worst = 0;   // a seek to the start gives the same samples as playing from the start
    for (size_t i = 0; i < 1000; ++i) worst = std::fmax(worst, std::fabs(buf[i * 2] - pcm[i * 2]));
    CHECK(worst < 0.01f);
}

int main(int argc, char** argv) {
    TestParsers();
    if (argc >= 3) TestDecode(argv[1], argv[2]);
    std::printf(failures ? "FAILED gapless_decode_test\n" : "gapless_decode_test OK %s\n", argc >= 2 ? argv[1] : "(parsers)");
    return failures ? 1 : 0;
}
