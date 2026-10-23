// Host test for DsdDecoder with synthetic DSD made by a 2nd-order sigma-delta modulator.
// Build: g++ -std=c++20 -O2 -I../../main/cpp/decoders dsd_decoder_test.cpp ../../main/cpp/decoders/DsdDecoder.cpp
#include <cmath>
#include <complex>
#include <cstdio>
#include <cstring>
#include <vector>
#include "DsdDecoder.hpp"

using namespace Audiophile::Decoders;

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FAIL line %d: %s\n", __LINE__, #cond); ++failures; } } while (0)

using Bytes = std::vector<uint8_t>;
constexpr double kPi = 3.14159265358979323846;

// One bit per sample, packed most-significant-bit first (time order).
static Bytes Modulate(double freqHz, double amp, double dsdRate, size_t bytes) {
    Bytes out(bytes);
    double v1 = 0, v2 = 0, y = 0;
    for (size_t i = 0; i < bytes * 8; ++i) {
        const double x = amp * std::sin(2 * kPi * freqHz * static_cast<double>(i) / dsdRate);
        v1 += 0.5 * (x - y);
        v2 += 0.5 * (v1 - y);
        y = v2 >= 0 ? 1.0 : -1.0;
        if (y > 0) out[i / 8] |= static_cast<uint8_t>(0x80 >> (i % 8)); else out[i / 8] &= static_cast<uint8_t>(~(0x80 >> (i % 8)));
    }
    return out;
}

static uint8_t Rev(uint8_t b) { uint8_t r = 0; for (int i = 0; i < 8; ++i) if (b & (1 << i)) r |= 0x80 >> i; return r; }
static void Le32(Bytes& b, uint32_t v) { for (int i = 0; i < 4; ++i) b.push_back((v >> (8 * i)) & 0xff); }
static void Le64(Bytes& b, uint64_t v) { Le32(b, static_cast<uint32_t>(v)); Le32(b, static_cast<uint32_t>(v >> 32)); }
static void Be16(Bytes& b, uint16_t v) { b.push_back(v >> 8); b.push_back(v & 0xff); }
static void Be32(Bytes& b, uint32_t v) { for (int i = 3; i >= 0; --i) b.push_back((v >> (8 * i)) & 0xff); }
static void Be64(Bytes& b, uint64_t v) { Be32(b, static_cast<uint32_t>(v >> 32)); Be32(b, static_cast<uint32_t>(v)); }
static void Tag(Bytes& b, const char* t) { b.insert(b.end(), t, t + 4); }

static Bytes MakeDsf(const std::vector<Bytes>& ch, uint32_t rate, bool bits8 = false) {
    const uint32_t block = 4096;
    const size_t perCh = ch[0].size();
    const size_t blocks = (perCh + block - 1) / block;
    Bytes data;
    for (size_t bl = 0; bl < blocks; ++bl)
        for (const Bytes& c : ch)
            for (size_t i = 0; i < block; ++i) {
                const size_t idx = bl * block + i;
                const uint8_t v = idx < perCh ? c[idx] : 0;
                data.push_back(bits8 ? v : Rev(v));  // 1-bit DSF is least-significant-bit first
            }
    Bytes b;
    Tag(b, "DSD "); Le64(b, 28); Le64(b, 28 + 52 + 12 + data.size()); Le64(b, 0);
    Tag(b, "fmt "); Le64(b, 52); Le32(b, 1); Le32(b, 0); Le32(b, 2); Le32(b, static_cast<uint32_t>(ch.size()));
    Le32(b, rate); Le32(b, bits8 ? 8 : 1); Le64(b, perCh * 8); Le32(b, block); Le32(b, 0);
    Tag(b, "data"); Le64(b, 12 + data.size());
    b.insert(b.end(), data.begin(), data.end());
    return b;
}

static Bytes MakeDff(const std::vector<Bytes>& ch, uint32_t rate, const char* cmpr = "DSD ") {
    Bytes prop;
    Tag(prop, "SND ");
    Tag(prop, "FS  "); Be64(prop, 4); Be32(prop, rate);
    Tag(prop, "CHNL"); Be64(prop, 2 + 4 * ch.size()); Be16(prop, static_cast<uint16_t>(ch.size()));
    for (size_t i = 0; i < ch.size(); ++i) Tag(prop, i == 0 ? "SLFT" : "SRGT");
    Tag(prop, "CMPR"); Be64(prop, 4 + 1 + 14 + 1); Tag(prop, cmpr); prop.push_back(14);
    const char* name = "not compressed";
    prop.insert(prop.end(), name, name + 14); prop.push_back(0);
    Bytes data;
    for (size_t i = 0; i < ch[0].size(); ++i) for (const Bytes& c : ch) data.push_back(c[i]);
    Bytes b;
    Tag(b, "FRM8"); Be64(b, 4 + 16 + 12 + prop.size() + 12 + data.size()); Tag(b, "DSD ");
    Tag(b, "FVER"); Be64(b, 4); Be32(b, 0x01050000);
    Tag(b, "PROP"); Be64(b, prop.size()); b.insert(b.end(), prop.begin(), prop.end());
    Tag(b, "DSD "); Be64(b, data.size()); b.insert(b.end(), data.begin(), data.end());
    return b;
}

static void Fft(std::vector<std::complex<double>>& a) {
    const size_t n = a.size();
    for (size_t i = 1, j = 0; i < n; ++i) {
        size_t bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(a[i], a[j]);
    }
    for (size_t len = 2; len <= n; len <<= 1) {
        const std::complex<double> w(std::cos(-2 * kPi / len), std::sin(-2 * kPi / len));
        for (size_t i = 0; i < n; i += len) {
            std::complex<double> wn(1);
            for (size_t k = 0; k < len / 2; ++k) {
                const auto u = a[i + k], v = a[i + k + len / 2] * wn;
                a[i + k] = u + v; a[i + k + len / 2] = u - v; wn *= w;
            }
        }
    }
}

// Amplitude of the tone at `bin`, and the in-band (to 20 kHz) noise-to-tone ratio in dB.
static void Analyse(const std::vector<float>& ch, size_t start, size_t n, size_t bin, double rate, double* amp, double* snrDb) {
    std::vector<std::complex<double>> a(n);
    for (size_t i = 0; i < n; ++i) a[i] = ch[start + i];
    Fft(a);
    *amp = 2.0 * std::abs(a[bin]) / static_cast<double>(n);
    double sig = 0, noise = 0;
    const size_t top = static_cast<size_t>(20000.0 * n / rate);
    for (size_t k = 1; k <= top; ++k) {
        const double p = std::norm(a[k]);
        if (k + 2 >= bin && k <= bin + 2) sig += p; else noise += p;
    }
    *snrDb = 10 * std::log10(sig / noise);
}

static std::vector<float> Decode(DsdDecoder& d, size_t frames, size_t channels) {
    std::vector<float> out(frames * channels);
    size_t got = 0;
    while (got < frames) {
        const size_t n = d.ReadFrames(out.data() + got * channels, std::min<size_t>(1000, frames - got));
        if (n == 0) break;
        got += n;
    }
    out.resize(got * channels);
    return out;
}

int main() {
    constexpr double kRate = 2822400;
    constexpr size_t kBytes = 352800;  // one second per channel
    const size_t N = 65536;
    const size_t binL = 372, binR = 5572;
    const double fL = binL * 176400.0 / N, fR = binR * 176400.0 / N;
    std::vector<Bytes> ch = {Modulate(fL, 0.5, kRate, kBytes), Modulate(fR, 0.5, kRate, kBytes)};

    const Bytes dsf = MakeDsf(ch, 2822400), dff = MakeDff(ch, 2822400);
    MemorySource sDsf(dsf.data(), dsf.size()), sDff(dff.data(), dff.size());
    DsdDecoder a, b;
    CHECK(a.Open(sDsf) == DecodeError::None);
    CHECK(b.Open(sDff) == DecodeError::None);
    const StreamFormat f = a.Format();
    CHECK(f.sampleRate == 176400 && f.channels == 2 && f.format == SampleFormat::Float32);
    CHECK(f.totalFrames == kBytes / 2);

    const auto pcm = Decode(a, f.totalFrames, 2);
    const auto pcm2 = Decode(b, f.totalFrames, 2);
    CHECK(pcm.size() == f.totalFrames * 2);
    CHECK(pcm == pcm2);  // DSF and DFF carry the same bits, so the audio is identical
    CHECK(a.ReadFrames(const_cast<float*>(pcm.data()), 10) == 0);

    std::vector<float> l(pcm.size() / 2), r(pcm.size() / 2);
    for (size_t i = 0; i < l.size(); ++i) { l[i] = pcm[2 * i]; r[i] = pcm[2 * i + 1]; }
    double amp, snr;
    Analyse(l, 20000, N, binL, 176400, &amp, &snr);
    std::printf("1 kHz: amplitude %.5f, in-band SNR %.1f dB\n", amp, snr);
    CHECK(std::fabs(amp - 0.5) < 0.0025);
    CHECK(snr > 65);
    Analyse(r, 20000, N, binR, 176400, &amp, &snr);
    std::printf("15 kHz: amplitude %.5f, in-band SNR %.1f dB\n", amp, snr);
    CHECK(std::fabs(amp - 0.5) < 0.0025);
    CHECK(snr > 65);

    // Seeks must give exactly what straight-through decoding gives.
    auto checkSeek = [&](uint64_t frame, size_t want) {
        CHECK(a.SeekToFrame(frame));
        CHECK(a.PositionFrames() == frame);
        std::vector<float> chunk(want * 2);
        const size_t n = a.ReadFrames(chunk.data(), want);
        const size_t expect = static_cast<size_t>(std::min<uint64_t>(want, f.totalFrames - frame));
        CHECK(n == expect);
        CHECK(std::memcmp(chunk.data(), pcm.data() + frame * 2, n * 2 * sizeof(float)) == 0);
    };
    checkSeek(100000, 5000);
    checkSeek(0, 3000);
    checkSeek(300, 3000);
    checkSeek(4095, 9000);
    checkSeek(f.totalFrames - 10, 100);
    checkSeek(77777, 20000);
    CHECK(a.SeekToFrame(f.totalFrames));
    CHECK(a.ReadFrames(const_cast<float*>(pcm.data()), 10) == 0);

    // Level references: constant ones are +1, the idle pattern is silence.
    {
        std::vector<Bytes> ones = {Bytes(40000, 0xFF)}, idle = {Bytes(40000, 0x69)};
        for (auto* set : {&ones, &idle}) {
            const Bytes file = MakeDff(*set, 2822400);
            MemorySource src(file.data(), file.size());
            DsdDecoder d;
            CHECK(d.Open(src) == DecodeError::None);
            const auto v = Decode(d, d.Format().totalFrames, 1);
            double worst = 0;
            const double expect = set == &ones ? 1.0 : 0.0;
            for (size_t i = 1000; i + 200 < v.size(); ++i) worst = std::max(worst, std::fabs(v[i] - expect));
            std::printf("%s: worst deviation %.2e\n", set == &ones ? "all ones" : "idle", worst);
            CHECK(worst < 1e-3);
        }
    }

    // DSD128: rate is halved again.
    {
        std::vector<Bytes> hi = {Modulate(1000, 0.5, 5644800, 705600 / 4)};
        const Bytes file = MakeDsf(hi, 5644800);
        MemorySource src(file.data(), file.size());
        DsdDecoder d;
        CHECK(d.Open(src) == DecodeError::None);
        CHECK(d.Format().sampleRate == 352800);
        CHECK(d.Format().totalFrames == hi[0].size() / 2);
        CHECK(Decode(d, d.Format().totalFrames, 1).size() == d.Format().totalFrames);
    }
    // DSD256 ends up at 352.8 kHz too, with two extra halvings.
    {
        std::vector<Bytes> hi = {Bytes(64000, 0x69)};
        const Bytes file = MakeDff(hi, 11289600);
        MemorySource src(file.data(), file.size());
        DsdDecoder d;
        CHECK(d.Open(src) == DecodeError::None);
        CHECK(d.Format().sampleRate == 705600 / 2);
    }

    // Not ours / not supported / damaged.
    {
        const uint8_t riff[20] = {'R','I','F','F',0,0,0,0,'W','A','V','E',0,0,0,0,0,0,0,0};
        MemorySource src(riff, sizeof(riff));
        DsdDecoder d;
        CHECK(d.Open(src) == DecodeError::NotThisFormat);
        const Bytes dst = MakeDff(ch, 2822400, "DST ");
        MemorySource s2(dst.data(), dst.size());
        CHECK(d.Open(s2) == DecodeError::Unsupported);
        MemorySource s3(dsf.data(), dsf.size() / 3);  // truncated: must end cleanly
        if (d.Open(s3) == DecodeError::None) {
            size_t n, sum = 0; float tmp[2000];
            while ((n = d.ReadFrames(tmp, 1000)) > 0) sum += n;
            CHECK(sum < f.totalFrames);
        }
    }

    if (failures == 0) std::printf("all passed\n");
    return failures ? 1 : 0;
}
