// End-to-end test of the engine through the C API, on a desktop host with ManualOutput standing
// in for the device. Checks bit-exact output, gapless joins, a reopen at a format change, seeks,
// the receipt, events and source ownership. Build: see tests/host or run_engine_host_test.sh.
#include <fcntl.h>
#include <unistd.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <thread>
#include <vector>
#include "HiresCoreInternal.hpp"
#include "PlaybackEngine.hpp"
#include "hires_core.h"

using Audiophile::Core::ManualOutput;

namespace {
int g_failures = 0;

void Check(bool ok, const char* what) {
    if (!ok) {
        std::printf("FAIL: %s\n", what);
        ++g_failures;
    }
}

void Sleep(int ms) { std::this_thread::sleep_for(std::chrono::milliseconds(ms)); }

// A WAV file in memory with pseudo-random, never-zero samples.
struct Wav {
    std::vector<uint8_t> file;
    std::vector<uint8_t> pcm;
    uint32_t rate;
    uint16_t channels;
    uint16_t bits;
    size_t frames;
};

void Put16(std::vector<uint8_t>& v, uint32_t x) { v.push_back(uint8_t(x)); v.push_back(uint8_t(x >> 8)); }
void Put32(std::vector<uint8_t>& v, uint32_t x) { Put16(v, x & 0xFFFF); Put16(v, x >> 16); }

Wav MakeWav(uint32_t rate, uint16_t channels, uint16_t bits, size_t frames, uint32_t seed) {
    Wav w{{}, {}, rate, channels, bits, frames};
    const size_t bytesPerSample = bits / 8u;
    uint32_t x = seed;
    for (size_t i = 0; i < frames * channels; ++i) {
        x = x * 1664525u + 1013904223u;
        uint32_t s = x | 0x00010101u;  // no all-zero bytes, so silence padding is easy to tell apart
        for (size_t b = 0; b < bytesPerSample; ++b) w.pcm.push_back(uint8_t(s >> (8 * b)));
    }
    std::vector<uint8_t>& f = w.file;
    const uint32_t dataBytes = static_cast<uint32_t>(w.pcm.size());
    f.insert(f.end(), {'R', 'I', 'F', 'F'});
    Put32(f, 36 + dataBytes);
    f.insert(f.end(), {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
    Put32(f, 16);
    Put16(f, 1);  // PCM
    Put16(f, channels);
    Put32(f, rate);
    Put32(f, rate * channels * bytesPerSample);
    Put16(f, static_cast<uint16_t>(channels * bytesPerSample));
    Put16(f, bits);
    f.insert(f.end(), {'d', 'a', 't', 'a'});
    Put32(f, dataBytes);
    f.insert(f.end(), w.pcm.begin(), w.pcm.end());
    return w;
}

// hrc_byte_source_callbacks over a byte vector, counting release() calls.
struct MemFile {
    const std::vector<uint8_t>* bytes;
    std::atomic<int>* releases;
};
int64_t MemSize(void* user) { return static_cast<int64_t>(static_cast<MemFile*>(user)->bytes->size()); }
int64_t MemRead(void* user, uint64_t offset, void* dst, size_t n) {
    const auto& b = *static_cast<MemFile*>(user)->bytes;
    if (offset >= b.size()) return 0;
    const size_t k = std::min(n, b.size() - static_cast<size_t>(offset));
    std::memcpy(dst, b.data() + offset, k);
    return static_cast<int64_t>(k);
}
void MemRelease(void* user) {
    auto* m = static_cast<MemFile*>(user);
    ++*m->releases;
    delete m;
}
const hrc_byte_source_callbacks kMemCallbacks{MemSize, MemRead, MemRelease};

hrc_source SourceFor(const Wav& w, uint64_t trackId, std::atomic<int>* releases) {
    hrc_source s{};
    s.struct_size = sizeof(s);
    s.kind = HRC_SOURCE_CALLBACKS;
    s.track_id = trackId;
    s.fd = -1;
    s.callbacks = &kMemCallbacks;
    s.user = new MemFile{&w.file, releases};
    return s;
}

hrc_telemetry Read(hrc_engine* e) {
    hrc_telemetry t{};
    t.struct_size = sizeof(t);
    hrc_telemetry_read(e, &t);
    return t;
}

std::vector<hrc_event> Events(hrc_engine* e) {
    std::vector<hrc_event> v;
    hrc_event ev{};
    ev.struct_size = sizeof(ev);
    while (hrc_event_poll(e, &ev) == HRC_OK) v.push_back(ev);
    return v;
}

size_t Count(const std::vector<hrc_event>& v, hrc_event_type type, uint64_t track = UINT64_MAX) {
    size_t n = 0;
    for (const auto& e : v) n += (e.type == type && (track == UINT64_MAX || e.track_id == track)) ? 1 : 0;
    return n;
}

// Plays to the end, collecting output bytes per output stream (a reopen starts a new one).
std::vector<std::vector<uint8_t>> PlayToEnd(hrc_engine* e, ManualOutput* out, std::vector<hrc_event>& events) {
    std::vector<std::vector<uint8_t>> segments(1);
    int opens = out->Opens();
    uint8_t buffer[512 * 2 * 8];
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(60);   // slow under TSan
    while (std::chrono::steady_clock::now() < deadline) {
        if (out->Opens() != opens) {
            opens = out->Opens();
            segments.emplace_back();
            Sleep(30);  // let the decoder fill the fresh ring before the "device" pulls
        }
        if (out->Running()) {
            const auto& req = out->LastRequest();
            const size_t frameBytes = (req.format == HRC_SAMPLE_S16 ? 2u : req.format == HRC_SAMPLE_S24 ? 3u : 4u) * 2u;
            if (out->Pull(buffer, 512)) segments.back().insert(segments.back().end(), buffer, buffer + 512 * frameBytes);
            else Sleep(1);   // the decoder has not caught up yet; do not spin the clock away
        } else {
            Sleep(1);
        }
        const hrc_telemetry t = Read(e);
        auto more = Events(e);
        events.insert(events.end(), more.begin(), more.end());
        if (t.state == HRC_STATE_ENDED) break;
    }
    return segments;
}

bool StartsWith(const std::vector<uint8_t>& out, const std::vector<uint8_t>& expected, size_t& at) {
    if (out.size() < at + expected.size()) return false;
    const bool same = std::memcmp(out.data() + at, expected.data(), expected.size()) == 0;
    at += expected.size();
    return same;
}

bool AllZero(const std::vector<uint8_t>& v, size_t from) {
    for (size_t i = from; i < v.size(); ++i) if (v[i] != 0) return false;
    return true;
}

hrc_engine* NewEngine(ManualOutput*& out) {
    auto output = std::make_unique<ManualOutput>();
    out = output.get();
    hrc_engine* e = nullptr;
    hrc_engine_create_with_output(nullptr, std::move(output), &e);
    return e;
}

void SetBitPerfect(hrc_engine* e, hrc_sample_format format) {
    hrc_output_config c{};
    c.struct_size = sizeof(c);
    c.kind = HRC_OUTPUT_A;
    c.format = format;
    c.flags = HRC_OUTPUT_FLAG_BIT_PERFECT_MIXER;
    c.usb_fd = -1;
    c.usb_interface = -1;
    Check(hrc_set_output(e, &c) == HRC_OK, "set_output");
}

void TestBitExactAndReceipt() {
    ManualOutput* out = nullptr;
    hrc_engine* e = NewEngine(out);
    const Wav w = MakeWav(44100, 2, 16, 70000, 1);
    std::atomic<int> releases{0};
    hrc_source s = SourceFor(w, 11, &releases);
    Check(hrc_open(e, &s) == HRC_OK, "open WAV through callbacks");
    SetBitPerfect(e, HRC_SAMPLE_UNKNOWN);
    Check(out->LastRequest().format == HRC_SAMPLE_S16 && out->LastRequest().sampleRate == 44100,
          "output follows the source format and rate");
    Sleep(50);
    Check(hrc_play(e) == HRC_OK, "play");
    std::vector<hrc_event> events;
    auto segments = PlayToEnd(e, out, events);
    size_t at = 0;
    Check(segments.size() == 1 && StartsWith(segments[0], w.pcm, at), "output equals the decoded PCM byte for byte");
    Check(AllZero(segments[0], at), "only silence after the end");

    const hrc_telemetry t = Read(e);
    Check((t.has & HRC_TELEMETRY_HAS_SOURCE) && t.source.track_id == 11, "source telemetry names the track");
    Check(t.source.sample_rate == 44100 && t.source.channels == 2 && t.source.valid_bits == 16 &&
          t.source.format == HRC_SAMPLE_S16 && t.source.container == HRC_CONTAINER_WAV, "source format");
    Check(std::strcmp(t.source.decoder_name, "own WAV") == 0, "decoder name");
    Check(t.receipt.frames_compared == 70000, "receipt covers the whole track");
    Check(t.receipt.first_mismatch_frame == -1 && t.receipt.decoded_hash == t.receipt.output_hash, "hashes match");
    Check(t.receipt.grade == HRC_GRADE_OS_CONFIRMED, "bit-perfect mixer + matching hashes = OS-confirmed");
    Check(t.counters.underruns == 0, "no underruns");
    Check((t.stages[HRC_STAGE_PEQ].flags & (HRC_STAGE_BUILT | HRC_STAGE_BYPASSED)) == (HRC_STAGE_BUILT | HRC_STAGE_BYPASSED),
          "PEQ built and bypassed");
    Check(t.stages[HRC_STAGE_CONVOLUTION].flags == 0, "unbuilt stages say so");
    Check(!(t.has & HRC_TELEMETRY_HAS_LEVELS) && !(t.has & HRC_TELEMETRY_HAS_CLOCK), "levels and clock not built");
    Check(t.position_frames == 70000, "position at the end");
    Check(Count(events, HRC_EVENT_TRACK_STARTED, 11) == 1, "TRACK_STARTED once");
    Check(Count(events, HRC_EVENT_TRACK_ENDED, 11) == 1 && Count(events, HRC_EVENT_QUEUE_EMPTY) == 1, "end events");
    Check(Count(events, HRC_EVENT_RECEIPT_MISMATCH) == 0, "no mismatch event");
    Check(Read(e).sequence == t.sequence, "sequence stays when nothing changed");
    hrc_engine_destroy(e);
    Check(releases == 1, "release() called exactly once");
}

void TestGaplessAndFloatOutput() {
    ManualOutput* out = nullptr;
    hrc_engine* e = NewEngine(out);
    const Wav a = MakeWav(48000, 2, 24, 40000, 2);
    const Wav b = MakeWav(48000, 2, 24, 30000, 3);
    std::atomic<int> releases{0};
    hrc_source sa = SourceFor(a, 1, &releases);
    hrc_source sb = SourceFor(b, 2, &releases);
    Check(hrc_open(e, &sa) == HRC_OK && hrc_queue_next(e, &sb) == HRC_OK, "open + queue");
    Check(out->LastRequest().format == HRC_SAMPLE_S24, "24-bit source opens a 24-bit output");
    Sleep(50);
    hrc_play(e);
    std::vector<hrc_event> events;
    auto segments = PlayToEnd(e, out, events);
    size_t at = 0;
    Check(segments.size() == 1, "same format: one stream, no reopen");
    Check(StartsWith(segments[0], a.pcm, at) && StartsWith(segments[0], b.pcm, at), "gapless: B follows A with no gap");
    Check(Count(events, HRC_EVENT_TRACK_STARTED, 2) == 1 && Count(events, HRC_EVENT_TRACK_ENDED, 1) == 1, "boundary events");
    const hrc_telemetry t = Read(e);
    Check(t.source.track_id == 2 && t.receipt.track_id == 2 && t.receipt.frames_compared == 30000, "receipt follows B");
    Check(t.receipt.grade == HRC_GRADE_BEST_EFFORT, "no bit-perfect mixer claimed: best effort");
    hrc_engine_destroy(e);
    Check(releases == 2, "both sources released once");

    // A float output carries 24-bit samples exactly, so the receipt still matches.
    e = NewEngine(out);
    releases = 0;
    hrc_source sc = SourceFor(a, 3, &releases);
    hrc_open(e, &sc);
    SetBitPerfect(e, HRC_SAMPLE_F32);
    Check(out->LastRequest().format == HRC_SAMPLE_F32, "fixed float output");
    Sleep(50);
    hrc_play(e);
    events.clear();
    PlayToEnd(e, out, events);
    const hrc_telemetry f = Read(e);
    Check(f.receipt.frames_compared == 40000 && f.receipt.first_mismatch_frame == -1, "24-bit in float: hashes match");
    hrc_engine_destroy(e);
}

void TestReopenAtFormatChange() {
    ManualOutput* out = nullptr;
    hrc_engine* e = NewEngine(out);
    const Wav a = MakeWav(44100, 2, 16, 20000, 4);
    const Wav b = MakeWav(96000, 2, 24, 25000, 5);
    std::atomic<int> releases{0};
    hrc_source sa = SourceFor(a, 1, &releases);
    hrc_source sb = SourceFor(b, 2, &releases);
    hrc_open(e, &sa);
    hrc_queue_next(e, &sb);
    SetBitPerfect(e, HRC_SAMPLE_UNKNOWN);
    Sleep(50);
    hrc_play(e);
    std::vector<hrc_event> events;
    auto segments = PlayToEnd(e, out, events);
    Check(segments.size() >= 2, "a different format reopens the output");
    size_t at = 0;
    Check(StartsWith(segments.front(), a.pcm, at) && AllZero(segments.front(), at), "A plays whole, then the stream stops");
    at = 0;
    Check(StartsWith(segments.back(), b.pcm, at), "B plays bit-exact in the new stream");
    Check(out->LastRequest().sampleRate == 96000 && out->LastRequest().format == HRC_SAMPLE_S24, "reopened at 96 kHz/24-bit");
    Check(!out->LastRequest().bitPerfect, "the bit-perfect claim is dropped until the caller sets it again");
    Check(Count(events, HRC_EVENT_OUTPUT_REOPENED, 2) == 1 && Count(events, HRC_EVENT_TRACK_STARTED, 2) == 1, "reopen events");
    const hrc_telemetry t = Read(e);
    Check(t.output.sample_rate == 96000 && t.source.sample_rate == 96000, "telemetry follows the new format");
    Check(t.receipt.grade == HRC_GRADE_BEST_EFFORT, "no mixer claim after the reopen");
    hrc_engine_destroy(e);
    Check(releases == 2, "both released");
}

void TestSeekAndFixedFormatStop() {
    ManualOutput* out = nullptr;
    hrc_engine* e = NewEngine(out);
    const Wav a = MakeWav(44100, 2, 16, 50000, 6);
    std::atomic<int> releases{0};
    hrc_source sa = SourceFor(a, 7, &releases);
    hrc_open(e, &sa);
    Check(hrc_seek_frames(e, 12345) == HRC_OK, "seek");
    Sleep(50);
    hrc_play(e);
    std::vector<hrc_event> events;
    auto segments = PlayToEnd(e, out, events);
    const std::vector<uint8_t> tail(a.pcm.begin() + 12345 * 4, a.pcm.end());
    size_t at = 0;
    Check(StartsWith(segments[0], tail, at), "playback starts at the exact seek frame");
    const hrc_telemetry t = Read(e);
    Check(t.receipt.window_start_frame == 12345 && t.receipt.frames_compared == 50000 - 12345, "receipt window starts at the seek");
    Check(t.receipt.first_mismatch_frame == -1, "seeked window matches");
    hrc_engine_destroy(e);
}

void TestDspChangesReceipt() {
    using Audiophile::Core::PlaybackEngine;
    using Audiophile::Core::TrackSource;
    auto output = std::make_unique<ManualOutput>();
    ManualOutput* out = output.get();
    PlaybackEngine engine(std::move(output));
    const Wav a = MakeWav(44100, 2, 16, 40000, 8);
    std::atomic<int> releases{0};
    hrc_result err = HRC_OK;
    auto track = TrackSource::FromCallbacks(kMemCallbacks, new MemFile{&a.file, &releases}, 9, err);
    Check(err == HRC_OK && engine.Open(std::move(track)) == HRC_OK, "open (C++)");
    Audiophile::DSP::ParametricEq::Band band;
    band.frequencyHz = 1000;
    band.gainDb = 3;
    band.q = 1;
    band.enabled = true;
    engine.SetEq(&band, 1, -3, true);
    Sleep(50);
    engine.Play();
    uint8_t buf[512 * 2 * 8];  // room for any output format
    for (int i = 0; i < 200 && engine.GetState() != PlaybackEngine::State::Ended; ++i) out->Pull(buf, 512);
    hrc_telemetry t{};
    t.struct_size = sizeof(t);
    engine.ReadTelemetry(t);
    Check((t.stages[HRC_STAGE_PEQ].flags & HRC_STAGE_ENABLED) && !(t.stages[HRC_STAGE_PEQ].flags & HRC_STAGE_BYPASSED),
          "PEQ on and changing samples");
    Check(t.receipt.first_mismatch_frame >= 0 && t.receipt.grade == HRC_GRADE_BEST_EFFORT, "DSP breaks the receipt: best effort");
    hrc_event ev{};
    ev.struct_size = sizeof(ev);
    bool mismatchEvent = false;
    while (engine.PollEvent(ev)) mismatchEvent |= ev.type == HRC_EVENT_RECEIPT_MISMATCH;
    Check(mismatchEvent, "RECEIPT_MISMATCH event");
}

void TestErrorsAndOwnership() {
    ManualOutput* out = nullptr;
    hrc_engine* e = NewEngine(out);
    const std::vector<uint8_t> junk(1000, 0x5A);
    std::atomic<int> releases{0};
    hrc_source s{};
    s.struct_size = sizeof(s);
    s.kind = HRC_SOURCE_CALLBACKS;
    s.callbacks = &kMemCallbacks;
    s.user = new MemFile{&junk, &releases};
    Check(hrc_open(e, &s) == HRC_E_UNSUPPORTED, "junk is refused");
    Check(releases == 1, "a refused source is still released once");
    Check(hrc_play(e) == HRC_E_BAD_STATE, "play with nothing open");

    hrc_output_config c{};
    c.struct_size = sizeof(c);
    c.kind = HRC_OUTPUT_B;
    Check(hrc_set_output(e, &c) == HRC_E_NOT_BUILT, "Output B is not built");
    c.kind = HRC_OUTPUT_A;
    c.sample_rate = 48000;
    Check(hrc_set_output(e, &c) == HRC_E_UNSUPPORTED, "a forced rate needs the resampler, not built");
    c.struct_size = 4;
    Check(hrc_set_output(e, &c) == HRC_E_STRUCT_SIZE, "struct_size is checked");
    hrc_event ev{};
    ev.struct_size = sizeof(ev);
    Check(hrc_event_poll(e, &ev) == HRC_NO_EVENT, "no events yet");

    // Output lost: the engine reopens and says so.
    const Wav a = MakeWav(44100, 2, 16, 30000, 9);
    hrc_source sa = SourceFor(a, 5, &releases);
    hrc_open(e, &sa);
    hrc_play(e);
    const int opens = out->Opens();
    out->Lose();
    Check(out->Opens() == opens + 1 && out->Running(), "reopened and restarted after a lost output");
    bool lost = false;
    while (hrc_event_poll(e, &ev) == HRC_OK) lost |= ev.type == HRC_EVENT_OUTPUT_LOST;
    Check(lost, "OUTPUT_LOST event");
    hrc_close(e);
    Check(Read(e).state == HRC_STATE_IDLE && !(Read(e).has & HRC_TELEMETRY_HAS_SOURCE), "closed: idle, no source shown");
    hrc_engine_destroy(e);
    Check(releases == 2, "every source released once");
}

// A real file opened by fd, played to the end, compared with reference PCM made by the ffmpeg CLI.
void TestFile(const char* path, const char* rawPath, const char* expectDecoder) {
    std::FILE* f = std::fopen(rawPath, "rb");
    std::vector<uint8_t> raw;
    if (f) {
        uint8_t chunk[65536];
        size_t n;
        while ((n = std::fread(chunk, 1, sizeof(chunk), f)) > 0) raw.insert(raw.end(), chunk, chunk + n);
        std::fclose(f);
    }
    Check(!raw.empty(), "reference PCM readable");
    ManualOutput* out = nullptr;
    hrc_engine* e = NewEngine(out);
    hrc_source s{};
    s.struct_size = sizeof(s);
    s.kind = HRC_SOURCE_FD;
    s.track_id = 42;
    s.fd = ::open(path, O_RDONLY);
    Check(hrc_open(e, &s) == HRC_OK, path);
    SetBitPerfect(e, HRC_SAMPLE_UNKNOWN);
    Sleep(200);
    hrc_play(e);
    std::vector<hrc_event> events;
    auto segments = PlayToEnd(e, out, events);
    size_t at = 0;
    Check(StartsWith(segments[0], raw, at) && AllZero(segments[0], at), "file plays bit-exact against the ffmpeg CLI");
    const hrc_telemetry t = Read(e);
    Check(std::strncmp(t.source.decoder_name, expectDecoder, std::strlen(expectDecoder)) == 0, "decoder named in telemetry");
    Check(t.receipt.grade == HRC_GRADE_OS_CONFIRMED && t.counters.underruns == 0, "file: receipt OS-confirmed");
    std::printf("  %s: %s, %u Hz, %u-bit, grade %d\n", path, t.source.decoder_name, t.source.sample_rate,
                static_cast<unsigned>(t.source.valid_bits), t.receipt.grade);
    hrc_engine_destroy(e);
}

// ---- Crossfade ---------------------------------------------------------------------------------

void SetTransition(hrc_engine* e, bool on, uint32_t ms, int32_t curve) {
    hrc_transition_config c{};
    c.struct_size = sizeof(c);
    c.crossfade_enabled = on ? 1u : 0u;
    c.crossfade_ms = ms;
    c.curve = curve;
    Check(hrc_set_transition(e, &c) == HRC_OK, "set_transition");
}

int16_t S16At(const std::vector<uint8_t>& v, size_t frame, int channel) {
    const size_t i = (frame * 2 + static_cast<size_t>(channel)) * 2;
    return static_cast<int16_t>(static_cast<uint16_t>(v[i]) | static_cast<uint16_t>(v[i + 1]) << 8);
}

void TestFadeCurves() {
    using namespace Audiophile::Core;
    for (const FadeCurve c : {FadeCurve::Linear, FadeCurve::EqualPower, FadeCurve::Logarithmic}) {
        const FadeGains start = FadeGainsAt(c, 0.0), end = FadeGainsAt(c, 1.0);
        Check(std::fabs(start.out - 1.0) < 1e-12 && std::fabs(start.in) < 1e-12, "fade starts on the outgoing track");
        Check(std::fabs(end.out) < 1e-12 && std::fabs(end.in - 1.0) < 1e-12, "fade ends on the incoming track");
        double lastOut = 2.0, lastIn = -1.0;
        bool monotonic = true;
        for (int i = 0; i <= 100; ++i) {
            const FadeGains g = FadeGainsAt(c, i / 100.0);
            monotonic = monotonic && g.out <= lastOut + 1e-12 && g.in >= lastIn - 1e-12;
            lastOut = g.out;
            lastIn = g.in;
        }
        Check(monotonic, "outgoing only falls, incoming only rises");
        // Symmetric: the two tracks swap roles around the middle.
        const FadeGains m = FadeGainsAt(c, 0.5);
        Check(std::fabs(m.out - m.in) < 1e-12, "equal at the middle");
    }
    Check(std::fabs(FadeGainsAt(FadeCurve::Linear, 0.5).out - 0.5) < 1e-12, "linear: -6 dB each at the middle");
    const FadeGains p = FadeGainsAt(FadeCurve::EqualPower, 0.3);
    Check(std::fabs(p.out * p.out + p.in * p.in - 1.0) < 1e-12, "equal power: squares add to one");
    const FadeGains l = FadeGainsAt(FadeCurve::Logarithmic, 0.5);
    Check(l.out > 0.7 && l.out < 0.8, "log: the middle sits above the linear curve");
    Check(FadeGainsAt(FadeCurve::Logarithmic, 0.1).out > FadeGainsAt(FadeCurve::Linear, 0.1).out, "log: the outgoing track holds longer");
    Check(FadeGainsAt(FadeCurve::Linear, -3).out == 1.0 && FadeGainsAt(FadeCurve::Linear, 7).in == 1.0, "progress is clamped");
}

// Everything the output produced for A then B with a crossfade of `fade` frames between them.
void CheckCrossfadeOutput(const std::vector<uint8_t>& out, const Wav& a, const Wav& b, size_t fade, int32_t curve,
                          const char* label) {
    using namespace Audiophile::Core;
    char what[160];
    const size_t expectFrames = a.frames + b.frames - fade;
    std::snprintf(what, sizeof(what), "%s: the tracks overlap by exactly the fade (%zu frames)", label, expectFrames);
    Check(out.size() >= expectFrames * 4 && AllZero(out, expectFrames * 4), what);
    if (out.size() < expectFrames * 4) return;
    std::snprintf(what, sizeof(what), "%s: before the fade the output is A's own samples", label);
    Check(std::memcmp(out.data(), a.pcm.data(), (a.frames - fade) * 4) == 0, what);
    std::snprintf(what, sizeof(what), "%s: after the fade the output is B's own samples", label);
    Check(std::memcmp(out.data() + a.frames * 4, b.pcm.data() + fade * 4, (b.frames - fade) * 4) == 0, what);
    int worst = 0;
    for (size_t i = 0; i < fade; ++i) {
        const FadeGains g = FadeGainsAt(static_cast<FadeCurve>(curve), (static_cast<double>(i) + 0.5) / static_cast<double>(fade));
        for (int c = 0; c < 2; ++c) {
            const double x = S16At(a.pcm, a.frames - fade + i, c) * g.out + S16At(b.pcm, i, c) * g.in;
            const long want = std::lround(std::clamp(x, -32768.0, 32767.0));
            worst = std::max(worst, static_cast<int>(std::labs(S16At(out, a.frames - fade + i, c) - want)));
        }
    }
    std::snprintf(what, sizeof(what), "%s: the mix is the curve applied to both tracks (worst %d LSB)", label, worst);
    Check(worst <= 2, what);   // rounding plus +-1 LSB of dither
    bool changed = false;
    for (size_t i = 0; i < fade && !changed; ++i) changed = S16At(out, a.frames - fade + i, 0) != S16At(a.pcm, a.frames - fade + i, 0);
    std::snprintf(what, sizeof(what), "%s: the fade really changed the samples", label);
    Check(changed, what);
}

void TestCrossfade() {
    for (const int32_t curve : {HRC_FADE_LINEAR, HRC_FADE_EQUAL_POWER, HRC_FADE_LOGARITHMIC}) {
        ManualOutput* out = nullptr;
        hrc_engine* e = NewEngine(out);
        SetTransition(e, true, 1000, curve);
        const Wav a = MakeWav(48000, 2, 16, 100000, 21);
        const Wav b = MakeWav(48000, 2, 16, 100000, 22);
        std::atomic<int> releases{0};
        hrc_source sa = SourceFor(a, 1, &releases);
        hrc_source sb = SourceFor(b, 2, &releases);
        Check(hrc_open(e, &sa) == HRC_OK && hrc_queue_next(e, &sb) == HRC_OK, "open + queue for a crossfade");
        SetBitPerfect(e, HRC_SAMPLE_UNKNOWN);
        Sleep(80);
        hrc_play(e);
        std::vector<hrc_event> events;
        auto segments = PlayToEnd(e, out, events);
        Check(segments.size() == 1, "a crossfade keeps one stream open");
        char label[32];
        std::snprintf(label, sizeof(label), "curve %d", curve);
        CheckCrossfadeOutput(segments[0], a, b, 48000, curve, label);
        Check(Count(events, HRC_EVENT_CROSSFADE, 2) == 1, "CROSSFADE event names the incoming track");
        for (const auto& ev : events) {
            if (ev.type == HRC_EVENT_CROSSFADE) Check(ev.value == 48000, "CROSSFADE event carries the fade length");
        }
        Check(Count(events, HRC_EVENT_TRACK_STARTED, 2) == 1 && Count(events, HRC_EVENT_TRACK_STARTED, 1) == 1, "each track starts once");
        Check(Count(events, HRC_EVENT_TRACK_ENDED, 1) == 1 && Count(events, HRC_EVENT_TRACK_ENDED, 2) == 1, "each track ends once");
        Check(Count(events, HRC_EVENT_QUEUE_EMPTY) == 1 && Count(events, HRC_EVENT_UNDERRUN) == 0, "one end, no underrun");
        const hrc_telemetry t = Read(e);
        Check(t.receipt.track_id == 2 && t.receipt.window_start_frame == 48000 && t.receipt.frames_compared == 100000 - 48000,
              "the receipt after the fade covers B's untouched audio alone");
        Check(t.receipt.first_mismatch_frame == -1 && t.receipt.grade == HRC_GRADE_OS_CONFIRMED,
              "and it is bit-exact again once the fade is over");
        Check(t.position_frames == 100000, "position is B's, counted from its start");
        hrc_engine_destroy(e);
        Check(releases == 2, "both sources released once");
    }
}

void TestCrossfadeOffAndPerTrackOverride() {
    const Wav a = MakeWav(44100, 2, 16, 60000, 31);
    const Wav b = MakeWav(44100, 2, 16, 60000, 32);
    for (int variant = 0; variant < 3; ++variant) {
        ManualOutput* out = nullptr;
        hrc_engine* e = NewEngine(out);
        SetTransition(e, variant != 0, 2000, HRC_FADE_EQUAL_POWER);   // 0: off
        if (variant == 2) SetTransition(e, false, 2000, HRC_FADE_EQUAL_POWER);   // switched off again before playing
        std::atomic<int> releases{0};
        hrc_source sa = SourceFor(a, 1, &releases);
        hrc_source sb = SourceFor(b, 2, &releases);
        if (variant == 1) sb.flags = HRC_SOURCE_FLAG_NO_CROSSFADE;   // on, but this join stays gapless
        hrc_open(e, &sa);
        hrc_queue_next(e, &sb);
        SetBitPerfect(e, HRC_SAMPLE_UNKNOWN);
        Sleep(60);
        hrc_play(e);
        std::vector<hrc_event> events;
        auto segments = PlayToEnd(e, out, events);
        size_t at = 0;
        const char* what = variant == 0 ? "crossfade off" : variant == 1 ? "NO_CROSSFADE flag" : "crossfade switched off";
        char text[96];
        std::snprintf(text, sizeof(text), "%s: gapless and bit-exact, A then B", what);
        Check(StartsWith(segments[0], a.pcm, at) && StartsWith(segments[0], b.pcm, at), text);
        std::snprintf(text, sizeof(text), "%s: no crossfade event", what);
        Check(Count(events, HRC_EVENT_CROSSFADE) == 0, text);
        std::snprintf(text, sizeof(text), "%s: receipt matches over B", what);
        hrc_telemetry t = Read(e);
        for (int i = 0; i < 200 && t.receipt.frames_compared != 60000; ++i) {   // the merger runs on its own cadence
            Sleep(10);
            t = Read(e);
        }
        Check(t.receipt.frames_compared == 60000 && t.receipt.first_mismatch_frame == -1 && t.receipt.grade == HRC_GRADE_OS_CONFIRMED, text);
        hrc_engine_destroy(e);
    }
}

void TestCrossfadeLimits() {
    using Audiophile::Core::PlaybackEngine;
    ManualOutput* out = nullptr;
    hrc_engine* e = NewEngine(out);
    hrc_transition_config c{};
    c.struct_size = sizeof(c);
    c.crossfade_enabled = 1;
    c.crossfade_ms = 4000;
    c.curve = 9;
    Check(hrc_set_transition(e, &c) == HRC_E_INVALID_ARG, "unknown curve rejected");
    c.curve = HRC_FADE_LINEAR;
    c.struct_size = 4;
    Check(hrc_set_transition(e, &c) == HRC_E_STRUCT_SIZE, "short struct rejected");
    Check(hrc_set_transition(nullptr, &c) == HRC_E_INVALID_ARG && hrc_set_transition(e, nullptr) == HRC_E_INVALID_ARG, "null arguments");
    hrc_engine_destroy(e);

    // A short incoming track gets a shorter fade (half its length), a long setting is clamped.
    e = NewEngine(out);
    SetTransition(e, true, 60000, HRC_FADE_LINEAR);   // clamped to 12 s: more than half of either track
    const Wav a = MakeWav(48000, 2, 16, 120000, 41);
    const Wav b = MakeWav(48000, 2, 16, 30000, 42);
    std::atomic<int> releases{0};
    hrc_source sa = SourceFor(a, 1, &releases);
    hrc_source sb = SourceFor(b, 2, &releases);
    hrc_open(e, &sa);
    hrc_queue_next(e, &sb);
    SetBitPerfect(e, HRC_SAMPLE_UNKNOWN);
    Sleep(60);
    hrc_play(e);
    std::vector<hrc_event> events;
    auto segments = PlayToEnd(e, out, events);
    CheckCrossfadeOutput(segments[0], a, b, 15000, HRC_FADE_LINEAR, "short incoming track");
    hrc_engine_destroy(e);
}

void TestCrossfadeNeedsMatchingFormat() {
    ManualOutput* out = nullptr;
    hrc_engine* e = NewEngine(out);
    SetTransition(e, true, 1000, HRC_FADE_EQUAL_POWER);
    const Wav a = MakeWav(44100, 2, 16, 60000, 51);
    const Wav b = MakeWav(96000, 2, 24, 40000, 52);
    std::atomic<int> releases{0};
    hrc_source sa = SourceFor(a, 1, &releases);
    hrc_source sb = SourceFor(b, 2, &releases);
    hrc_open(e, &sa);
    hrc_queue_next(e, &sb);
    SetBitPerfect(e, HRC_SAMPLE_UNKNOWN);
    Sleep(60);
    hrc_play(e);
    std::vector<hrc_event> events;
    auto segments = PlayToEnd(e, out, events);
    size_t at = 0;
    Check(segments.size() >= 2 && StartsWith(segments.front(), a.pcm, at), "a different rate is not mixed: A plays whole and bit-exact");
    at = 0;
    Check(StartsWith(segments.back(), b.pcm, at), "B follows in its own stream, bit-exact");
    Check(Count(events, HRC_EVENT_CROSSFADE) == 0 && Count(events, HRC_EVENT_OUTPUT_REOPENED, 2) == 1, "reopen, no crossfade");
    hrc_engine_destroy(e);
}

// A seek while the decoder is already past the end of A (tail held, fade written) puts everything back.
void TestCrossfadeSeek() {
    ManualOutput* out = nullptr;
    hrc_engine* e = NewEngine(out);
    SetTransition(e, true, 1000, HRC_FADE_EQUAL_POWER);
    const Wav a = MakeWav(48000, 2, 16, 100000, 61);
    const Wav b = MakeWav(48000, 2, 16, 100000, 62);
    std::atomic<int> releases{0};
    hrc_source sa = SourceFor(a, 1, &releases);
    hrc_source sb = SourceFor(b, 2, &releases);
    hrc_open(e, &sa);
    hrc_queue_next(e, &sb);
    SetBitPerfect(e, HRC_SAMPLE_UNKNOWN);
    Sleep(150);   // the decoder runs ahead: A is decoded, the fade into B is written
    hrc_play(e);
    uint8_t buffer[512 * 2 * 8];
    for (int i = 0; i < 20; ++i) out->Pull(buffer, 512);   // still well inside A
    Check(hrc_seek_frames(e, 30000) == HRC_OK, "seek back into A while the fade is already queued");
    Sleep(150);
    std::vector<hrc_event> events;
    auto segments = PlayToEnd(e, out, events);
    // Everything pulled after the seek: A from 30000, the fade, B's remainder.
    std::vector<uint8_t> after = segments[0];
    const size_t expectFrames = (a.frames - 30000) + b.frames - 48000;
    Check(after.size() >= expectFrames * 4 && AllZero(after, expectFrames * 4), "after the seek: A's rest, then B, overlapping by the fade");
    Check(std::memcmp(after.data(), a.pcm.data() + 30000 * 4, (a.frames - 30000 - 48000) * 4) == 0, "playback resumed at the seek frame");
    Check(std::memcmp(after.data() + (a.frames - 30000) * 4, b.pcm.data() + 48000 * 4, (b.frames - 48000) * 4) == 0,
          "and B still arrives intact after the fade");
    Check(Count(events, HRC_EVENT_CROSSFADE, 2) == 1, "one crossfade into B");
    hrc_engine_destroy(e);
    Check(releases == 2, "no source leaked or released twice");
}

// The queued track is decoded ahead of the boundary: replacing or clearing it meanwhile is safe.
void TestQueueChangesWhilePrimed() {
    ManualOutput* out = nullptr;
    hrc_engine* e = NewEngine(out);
    // A is longer than the ring (1,048,576 frames), so the decoder is parked on a full ring with B pre-decoded.
    const Wav a = MakeWav(44100, 2, 16, 1300000, 71);
    const Wav b = MakeWav(44100, 2, 16, 30000, 72);
    const Wav c = MakeWav(44100, 2, 16, 30000, 73);
    std::atomic<int> releases{0};
    hrc_source sa = SourceFor(a, 1, &releases);
    hrc_source sb = SourceFor(b, 2, &releases);
    hrc_source sc = SourceFor(c, 3, &releases);
    hrc_open(e, &sa);
    hrc_queue_next(e, &sb);
    SetBitPerfect(e, HRC_SAMPLE_UNKNOWN);
    Sleep(100);                       // B is pre-decoded by now
    hrc_queue_next(e, &sc);           // replaced by C
    Sleep(60);
    Check(releases == 1, "the replaced, pre-decoded track is released");
    hrc_play(e);
    std::vector<hrc_event> events;
    auto segments = PlayToEnd(e, out, events);
    size_t at = 0;
    Check(StartsWith(segments[0], a.pcm, at) && StartsWith(segments[0], c.pcm, at), "C follows A, not the stale B");
    Check(Count(events, HRC_EVENT_TRACK_STARTED, 3) == 1 && Count(events, HRC_EVENT_TRACK_STARTED, 2) == 0, "events name C");
    hrc_engine_destroy(e);
    Check(releases == 3, "all three released once");

    // Cleared while pre-decoded: the track after A does not exist, playback ends.
    e = NewEngine(out);
    releases = 0;
    hrc_source sa2 = SourceFor(a, 1, &releases);
    hrc_source sb2 = SourceFor(b, 2, &releases);
    hrc_open(e, &sa2);
    hrc_queue_next(e, &sb2);
    SetBitPerfect(e, HRC_SAMPLE_UNKNOWN);
    Sleep(100);
    hrc_clear_next(e);
    Sleep(60);
    Check(releases == 1, "the cleared, pre-decoded track is released");
    hrc_play(e);
    events.clear();
    segments = PlayToEnd(e, out, events);
    at = 0;
    Check(StartsWith(segments[0], a.pcm, at) && AllZero(segments[0], at), "cleared: only A plays");
    Check(Count(events, HRC_EVENT_TRACK_STARTED, 2) == 0, "the cleared track never starts");
    hrc_engine_destroy(e);
    Check(releases == 2, "cleared track released once");
}


// Real lossy files through the whole engine: the join between two copies has no encoder delay or
// padding in it (gapless), and a crossfade overlaps them by exactly the fade.
void TestGaplessFiles(const char* path, size_t frames) {
    for (const bool crossfade : {false, true}) {
        FILE* f = std::fopen(path, "rb");
        Check(f != nullptr, "gapless fixture opens");
        if (!f) return;
        std::fclose(f);
        ManualOutput* out = nullptr;
        hrc_engine* e = NewEngine(out);
        SetTransition(e, crossfade, 1000, HRC_FADE_LINEAR);
        auto source = [&](uint64_t id) {
            hrc_source s{};
            s.struct_size = sizeof(s);
            s.kind = HRC_SOURCE_FD;
            s.track_id = id;
            s.fd = ::open(path, O_RDONLY);
            return s;
        };
        hrc_source a = source(1), b = source(2);
        Check(hrc_open(e, &a) == HRC_OK && hrc_queue_next(e, &b) == HRC_OK, "open the same lossy file twice");
        SetBitPerfect(e, HRC_SAMPLE_UNKNOWN);
        Sleep(80);
        hrc_play(e);
        std::vector<hrc_event> events;
        auto segments = PlayToEnd(e, out, events);
        const auto& pcm = segments[0];
        Check(Read(e).source.total_frames == frames, "the engine reports the music length, not the encoded length");
        size_t last = pcm.size() / 8;   // float stereo: 8 bytes per frame
        while (last > 0 && AllZero(std::vector<uint8_t>(pcm.begin() + static_cast<long>((last - 1) * 8), pcm.begin() + static_cast<long>(last * 8)), 0)) --last;
        const size_t fade = crossfade ? 44100 : 0;   // the 1 s asked for (the track is longer than 2 s)
        char text[120];
        std::snprintf(text, sizeof(text), "%s: two copies play for exactly 2 x %zu%s frames, nothing in between (got %zu)", path,
                      frames, crossfade ? " - fade" : "", last);
        Check(last == 2 * frames - fade, text);
        Check(Count(events, HRC_EVENT_CROSSFADE) == (crossfade ? 1u : 0u), "crossfade event only when asked");
        Check(Count(events, HRC_EVENT_UNDERRUN) == 0, "no underrun");
        hrc_engine_destroy(e);
    }
}

}  // namespace

int main(int argc, char** argv) {
    // Optional file cases: <file> <reference raw PCM> <decoder name prefix>, repeated.
    // "--gapless <file> <frames>" runs the lossy-file gapless/crossfade check instead.
    for (int i = 1; i + 2 < argc;) {
        if (std::strcmp(argv[i], "--gapless") == 0) {
            TestGaplessFiles(argv[i + 1], static_cast<size_t>(std::strtoull(argv[i + 2], nullptr, 10)));
        } else {
            TestFile(argv[i], argv[i + 1], argv[i + 2]);
        }
        i += 3;
    }
    TestBitExactAndReceipt();
    TestGaplessAndFloatOutput();
    TestReopenAtFormatChange();
    TestSeekAndFixedFormatStop();
    TestDspChangesReceipt();
    TestErrorsAndOwnership();
    TestFadeCurves();
    TestCrossfade();
    TestCrossfadeOffAndPerTrackOverride();
    TestCrossfadeLimits();
    TestCrossfadeNeedsMatchingFormat();
    TestCrossfadeSeek();
    TestQueueChangesWhilePrimed();
    if (g_failures == 0) std::printf("hires_engine_test: all checks passed\n");
    return g_failures == 0 ? 0 : 1;
}
