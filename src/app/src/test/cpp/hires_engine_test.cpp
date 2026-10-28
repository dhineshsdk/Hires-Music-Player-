// End-to-end test of the engine through the C API, on a desktop host with ManualOutput standing
// in for the device. Checks bit-exact output, gapless joins, a reopen at a format change, seeks,
// the receipt, events and source ownership. Build: see tests/host or run_engine_host_test.sh.
#include <fcntl.h>
#include <unistd.h>
#include <chrono>
#include <cstdint>
#include <cstdio>
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
    int* releases;
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

hrc_source SourceFor(const Wav& w, uint64_t trackId, int* releases) {
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
    for (int idle = 0; idle < 4000; ++idle) {
        if (out->Opens() != opens) {
            opens = out->Opens();
            segments.emplace_back();
            Sleep(30);  // let the decoder fill the fresh ring before the "device" pulls
        }
        if (out->Running()) {
            const auto& req = out->LastRequest();
            const size_t frameBytes = (req.format == HRC_SAMPLE_S16 ? 2u : req.format == HRC_SAMPLE_S24 ? 3u : 4u) * 2u;
            if (out->Pull(buffer, 512)) segments.back().insert(segments.back().end(), buffer, buffer + 512 * frameBytes);
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
    int releases = 0;
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
    int releases = 0;
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
    int releases = 0;
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
    int releases = 0;
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
    int releases = 0;
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
    int releases = 0;
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

// The legacy JNI path on the shared mixer: ENCODING_DEFAULT makes the output follow the source
// (integer for integer files, not float), and a mixer that refuses the integer format gets float.
void TestLegacySharedFollowsSource() {
    using Audiophile::Core::PlaybackEngine;
    const Wav a = MakeWav(48000, 2, 24, 30000, 9);
    char path[] = "/tmp/hrc_legacy_XXXXXX";
    const int fd = mkstemp(path);
    Check(fd >= 0 && write(fd, a.file.data(), a.file.size()) == static_cast<ssize_t>(a.file.size()), "temp file");
    unlink(path);
    auto output = std::make_unique<ManualOutput>();
    ManualOutput* out = output.get();
    {
        PlaybackEngine engine(std::move(output));
        engine.SetOutputFormat(1 /* AudioFormat.ENCODING_DEFAULT */, false);
        Check(engine.Open(fd), "legacy open");
        Check(out->LastRequest().format == HRC_SAMPLE_S24 && !out->LastRequest().bitPerfect, "shared output opens in the source's 24-bit format");
        Check(engine.GetOutputEncoding() == 21, "reports ENCODING_PCM_24BIT_PACKED");
        Sleep(50);
        engine.Play();
        std::vector<uint8_t> got;
        uint8_t buffer[512 * 2 * 4];
        for (int i = 0; i < 4000 && got.size() < a.pcm.size(); ++i) {
            if (out->Pull(buffer, 512)) got.insert(got.end(), buffer, buffer + 512 * 6);
            else Sleep(1);
        }
        size_t at = 0;
        Check(StartsWith(got, a.pcm, at), "shared integer output is the file's own samples");

        const int opens = out->Opens();
        engine.SetOutputFormat(1, false);
        Check(out->Opens() == opens, "the same output config again keeps the stream");
        out->RefuseFormat(HRC_SAMPLE_S24);
        engine.SetOutputFormat(2, false);
        engine.SetOutputFormat(1, false);
        Check(out->LastRequest().format == HRC_SAMPLE_F32 && engine.GetOutputEncoding() == 4, "falls back to float when the mixer refuses 24-bit");
        engine.SetOutputFormat(21, true);
        Check(out->LastRequest().format == HRC_SAMPLE_S24 && out->LastRequest().bitPerfect, "no float fallback on a bit-perfect request");
    }
}
}  // namespace

int main(int argc, char** argv) {
    // Optional file cases: <file> <reference raw PCM> <decoder name prefix>, repeated.
    for (int i = 1; i + 2 < argc; i += 3) TestFile(argv[i], argv[i + 1], argv[i + 2]);
    TestBitExactAndReceipt();
    TestGaplessAndFloatOutput();
    TestReopenAtFormatChange();
    TestSeekAndFixedFormatStop();
    TestDspChangesReceipt();
    TestErrorsAndOwnership();
    TestLegacySharedFollowsSource();
    if (g_failures == 0) std::printf("hires_engine_test: all checks passed\n");
    return g_failures == 0 ? 0 : 1;
}
