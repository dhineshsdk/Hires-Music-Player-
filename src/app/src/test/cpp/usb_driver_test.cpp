// Host tests for Output B: descriptor parsing, format selection, packet pacing from feedback, UAC control
// requests, the pump against a simulated DAC (bit-exact payload, no FIFO underrun/overrun), start/stop
// races, and device loss.
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <memory>
#include <thread>
#include "UacControl.hpp"
#include "UacStreamSelector.hpp"
#include "UsbExclusiveOutput.hpp"
#include "UsbIsoPump.hpp"
#include "UsbPacer.hpp"
#include "fake_usb_dac.hpp"

using namespace Audiophile;
using namespace Audiophile::Drivers;
using Audiophile::Test::FakeDacConfig;
using Audiophile::Test::FakeUsbDac;

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { std::printf("FAIL line %d: %s  ", __LINE__, #cond); std::printf(__VA_ARGS__); std::printf("\n"); ++failures; } } while (0)

static void Sleep(int ms) { std::this_thread::sleep_for(std::chrono::milliseconds(ms)); }

// A source whose bytes depend only on the frame index, so any loss, repeat or reorder shows.
class CounterSource final : public Core::OutputCallback {
public:
    explicit CounterSource(uint32_t bytesPerFrame) : bpf_(bytesPerFrame) {}
    void Render(void* out, int32_t frames, int64_t) noexcept override {
        auto* p = static_cast<uint8_t*>(out);
        for (int32_t f = 0; f < frames; ++f) {
            for (uint32_t b = 0; b < bpf_; ++b) *p++ = Byte(next_, b);
            ++next_;
        }
        rendered_ += static_cast<uint64_t>(frames);
    }
    void OnOutputLost() noexcept override { lost_ = true; }
    static uint8_t Byte(uint64_t frame, uint32_t b) { return static_cast<uint8_t>((frame * 2654435761u >> (8 * (b & 3))) + b * 17 + 1); }
    bool Matches(const std::vector<uint8_t>& captured) const {
        if (captured.size() % bpf_ != 0) return false;
        uint64_t f = 0;
        for (size_t i = 0; i < captured.size(); i += bpf_, ++f)
            for (uint32_t b = 0; b < bpf_; ++b) if (captured[i + b] != Byte(f, b)) return false;
        return true;
    }
    uint64_t rendered() const { return rendered_; }
    std::atomic<bool> lost_{false};
private:
    uint32_t bpf_;
    uint64_t next_{0};
    uint64_t rendered_{0};
};

static PumpConfig PumpFor(FakeUsbDac& dac, const FakeDacConfig& c, uint32_t subslot) {
    PumpConfig p;
    p.io = &dac;
    p.dataEndpoint = 0x01;
    p.feedbackEndpoint = !c.feedback ? 0 : c.implicitFeedback ? 0x82 : 0x81;
    p.implicitFeedback = c.implicitFeedback;
    const uint32_t perSlot = c.rate / (c.highSpeed ? 8000u : 1000u) + 2;
    p.maxPacketBytes = perSlot * 2 * subslot;
    p.feedbackPacketBytes = c.highSpeed ? 4 : 3;
    p.bytesPerFrame = 2 * subslot;
    p.sampleRate = c.rate;
    p.highSpeed = c.highSpeed;
    p.packetsPerUrb = c.highSpeed ? 16 : 2;
    p.urbCount = 8;
    return p;
}

// ---------------------------------------------------------------------------------------------

static void TestParserUac2() {
    FakeDacConfig c; c.withInputStream = true;
    FakeUsbDac dac(c);
    const auto all = AudioDescriptorParser::Parse(dac.Descriptors());
    const auto play = UacStreamSelector::PlaybackStreams(all);
    CHECK(play.size() == 3, "three playback alternates, got %zu (microphone stream excluded)", play.size());
    if (play.size() != 3) return;
    CHECK(play[0].subslot_size == 2 && play[0].bit_resolution == 16 && play[1].subslot_size == 3 && play[2].subslot_size == 4, "subslots");
    CHECK(play[0].channels == 2 && play[0].protocol_version == 0x20, "UAC2 stereo");
    CHECK(play[0].data_endpoint_address == 0x01 && play[0].feedback_endpoint_address == 0x81 && play[0].is_asynchronous, "async with feedback endpoint");
    CHECK(!play[0].is_implicit_feedback && play[0].clock_source_id == 5 && play[0].control_interface == 0, "clock 5 on control interface 0");
    CHECK(play[0].interface_number == 1 && play[0].alternate_setting == 1 && play[2].alternate_setting == 3, "alt numbers");
    CHECK(play[0].max_packet_size > 0, "packet size");
}

static void TestParserUac1AndImplicit() {
    FakeDacConfig c; c.uac2 = false; c.highSpeed = false; c.rate = 44100;
    FakeUsbDac dac(c);
    const auto play = UacStreamSelector::PlaybackStreams(AudioDescriptorParser::Parse(dac.Descriptors()));
    CHECK(play.size() == 3 && play[0].protocol_version == 0x00, "UAC1 playback alternates");
    if (play.empty()) return;
    CHECK(play[0].sample_rate_count == 3 && play[0].sample_rates[0] == 44100 && play[0].sample_rates[1] == 48000 && play[0].sample_rates[2] == 96000,
          "rate list");
    CHECK(UacStreamSelector::SupportsRate(play[0], 96000) && !UacStreamSelector::SupportsRate(play[0], 192000), "rate support");
    CHECK(play[0].feedback_endpoint_address == 0x81, "feedback endpoint");

    FakeDacConfig ci; ci.implicitFeedback = true;
    FakeUsbDac idac(ci);
    const auto ip = UacStreamSelector::PlaybackStreams(AudioDescriptorParser::Parse(idac.Descriptors()));
    CHECK(!ip.empty() && ip[0].is_implicit_feedback && ip[0].feedback_endpoint_address == 0x82, "implicit feedback endpoint");
}

static void TestSelector() {
    FakeDacConfig c; c.uac2 = false; c.highSpeed = false; c.rate = 44100;
    FakeUsbDac dac(c);
    const auto play = UacStreamSelector::PlaybackStreams(AudioDescriptorParser::Parse(dac.Descriptors()));
    auto s16 = UacStreamSelector::Choose(play, 44100, 2, HRC_SAMPLE_S16);
    CHECK(s16 && s16->stream.subslot_size == 2 && s16->format == HRC_SAMPLE_S16 && s16->exactWidth, "16-bit source -> 16-bit alternate");
    auto s24 = UacStreamSelector::Choose(play, 96000, 2, HRC_SAMPLE_S24);
    CHECK(s24 && s24->stream.subslot_size == 3 && s24->format == HRC_SAMPLE_S24, "24-bit source -> 24-bit alternate");
    auto s32 = UacStreamSelector::Choose(play, 48000, 2, HRC_SAMPLE_S32);
    CHECK(s32 && s32->stream.subslot_size == 4, "32-bit source -> 32-bit alternate");
    auto f32 = UacStreamSelector::Choose(play, 48000, 2, HRC_SAMPLE_F32);
    CHECK(f32 && f32->stream.bit_resolution >= 24, "float -> at least 24 bits");
    CHECK(!UacStreamSelector::Choose(play, 192000, 2, HRC_SAMPLE_S16), "unsupported rate: none");
    CHECK(!UacStreamSelector::Choose(play, 44100, 6, HRC_SAMPLE_S16), "unsupported channel count: none");
    // Only 16 and 24-bit alternates: a 32-bit request takes the widest available.
    std::vector<AudioStreamingEndpointConfiguration> some{play[0], play[1]};
    auto narrow = UacStreamSelector::Choose(some, 44100, 2, HRC_SAMPLE_S32);
    CHECK(narrow && narrow->stream.subslot_size == 3 && !narrow->exactWidth, "32-bit source on a 24-bit DAC rounds");
}

static void TestPacer() {
    // Nominal: 44.1 kHz on a high-speed bus is 5.5125 frames per microframe; one second adds up exactly.
    PacketPacer n(44100, true, PacketPacer::Mode::Nominal);
    uint64_t sum = 0;
    for (int i = 0; i < 8000; ++i) sum += n.NextPacketFrames();
    CHECK(sum == 44100, "nominal pacing carries the fraction: %llu", static_cast<unsigned long long>(sum));
    PacketPacer fs(44100, false, PacketPacer::Mode::Nominal);
    sum = 0;
    for (int i = 0; i < 1000; ++i) sum += fs.NextPacketFrames();
    CHECK(sum == 44100, "full-speed nominal");

    // High-speed feedback, both readings of the 16.16 value, +500 ppm.
    const double perMs = 192000 * 1.0005 / 1000.0;
    for (int variant = 0; variant < 2; ++variant) {
        PacketPacer p(192000, true, PacketPacer::Mode::ExplicitFeedback);
        const uint32_t v = static_cast<uint32_t>(variant == 0 ? perMs * 65536.0 : perMs / 8.0 * 65536.0);
        const uint8_t b[4] = {uint8_t(v), uint8_t(v >> 8), uint8_t(v >> 16), uint8_t(v >> 24)};
        for (int i = 0; i < 40; ++i) CHECK(p.IngestFeedback(b, 4), "feedback accepted (variant %d)", variant);
        sum = 0;
        for (int i = 0; i < 8000; ++i) sum += p.NextPacketFrames();
        CHECK(std::llabs(static_cast<long long>(sum) - 192096) <= 1, "variant %d: %llu frames in a second", variant, static_cast<unsigned long long>(sum));
    }
    // Full-speed 10.14, 3 bytes.
    PacketPacer f(48000, false, PacketPacer::Mode::ExplicitFeedback);
    const uint32_t q = static_cast<uint32_t>(48.0 * 0.9997 * 16384.0);
    const uint8_t b3[3] = {uint8_t(q), uint8_t(q >> 8), uint8_t(q >> 16)};
    for (int i = 0; i < 40; ++i) f.IngestFeedback(b3, 3);
    sum = 0;
    for (int i = 0; i < 1000; ++i) sum += f.NextPacketFrames();
    CHECK(std::llabs(static_cast<long long>(sum) - 47986) <= 1, "10.14 feedback: %llu", static_cast<unsigned long long>(sum));
    // Garbage is refused and does not move the rate.
    const uint8_t bad[4] = {0xFF, 0xFF, 0xFF, 0x7F};
    CHECK(!f.IngestFeedback(bad, 4) && !f.IngestFeedback(bad, 2) && f.feedbackRejected() == 2, "implausible feedback refused");
    // Implicit feedback follows the IN endpoint.
    PacketPacer imp(48000, true, PacketPacer::Mode::Implicit);
    imp.IngestImplicitFrames(7);
    imp.IngestImplicitFrames(5);
    CHECK(imp.NextPacketFrames() == 7 && imp.NextPacketFrames() == 5 && imp.NextPacketFrames() <= 7, "implicit counts then nominal");
}

static void TestControlRequests() {
    FakeDacConfig c;
    FakeUsbDac dac(c);
    CHECK(UacControl::SetRateUac1(dac, 0x01, 96000), "UAC1 SET_CUR");
    CHECK(UacControl::SetRateUac2(dac, 0, 5, 192000), "UAC2 SET_CUR");
    CHECK(UacControl::GetRateUac2(dac, 0, 5) == 192000, "UAC2 GET_CUR");
    UacControl::RateRange r[16];
    const int n = UacControl::QueryRatesUac2(dac, 0, 5, r, 16);
    CHECK(n == 6 && r[0].min == 44100 && r[5].max == 192000, "GET_RANGE parsed: %d ranges", n);
    const auto calls = dac.Controls();
    CHECK(calls.size() == 4, "four control transfers");
    if (calls.size() < 2) return;
    CHECK(calls[0].type == 0x22 && calls[0].request == 0x01 && calls[0].value == 0x0100 && calls[0].index == 0x01 &&
              calls[0].data == std::vector<uint8_t>({0x00, 0x77, 0x01}), "UAC1 request layout (96 kHz = 00 77 01)");
    CHECK(calls[1].type == 0x21 && calls[1].value == 0x0100 && calls[1].index == ((5 << 8) | 0) &&
              calls[1].data == std::vector<uint8_t>({0x00, 0xEE, 0x02, 0x00}), "UAC2 request layout (192 kHz)");
}

// ---------------------------------------------------------------------------------------------

static void TestPumpHighSpeedFeedback() {
    // 192 kHz / 24-bit, DAC clock 300 ppm fast: the pump must follow the feedback and never starve or flood the DAC.
    FakeDacConfig c; c.rate = 192000; c.deviceRatePpm = 300.0;
    FakeUsbDac dac(c);
    dac.SetAlternate(1, 2);
    CounterSource src(6);
    UsbIsoPump pump;
    CHECK(pump.Start(PumpFor(dac, c, 3), &src), "start");
    Sleep(400);
    pump.Stop();
    const auto bytes = dac.Captured();
    CHECK(src.Matches(bytes) && bytes.size() > 6 * 192000 / 4, "payload is the counter stream, in order, none lost (%zu bytes)", bytes.size());
    CHECK(dac.Underruns() == 0 && dac.Overruns() == 0, "no FIFO underrun/overrun with feedback: %llu / %llu",
          static_cast<unsigned long long>(dac.Underruns()), static_cast<unsigned long long>(dac.Overruns()));
    CHECK(pump.FeedbackAccepted() > 20, "feedback was used: %u", pump.FeedbackAccepted());
    CHECK(std::abs(pump.RateEstimate() - 192000 * 1.0003) < 192000 * 0.0001, "rate estimate %.1f Hz", pump.RateEstimate());
    CHECK(pump.Clamped() == 0, "no clamped packets");

    // Control experiment: without feedback the same DAC drifts out of its FIFO, which the model must notice.
    FakeDacConfig n = c; n.feedback = false;
    FakeUsbDac dac2(n);
    dac2.SetAlternate(1, 2);
    CounterSource src2(6);
    UsbIsoPump pump2;
    CHECK(pump2.Start(PumpFor(dac2, n, 3), &src2), "start without feedback");
    Sleep(400);
    pump2.Stop();
    CHECK(dac2.Underruns() + dac2.Overruns() > 0, "nominal pacing against a 300 ppm DAC must break the FIFO (model sanity)");
}

static void TestPumpFullSpeedUac1() {
    FakeDacConfig c; c.uac2 = false; c.highSpeed = false; c.rate = 44100; c.deviceRatePpm = -400.0; c.feedbackPeriodSlots = 8;
    FakeUsbDac dac(c);
    dac.SetAlternate(1, 1);
    CounterSource src(4);
    UsbIsoPump pump;
    CHECK(pump.Start(PumpFor(dac, c, 2), &src), "start");
    Sleep(500);
    pump.Stop();
    const auto bytes = dac.Captured();
    CHECK(src.Matches(bytes) && bytes.size() > 4 * 44100 / 4, "UAC1 full speed: payload intact");
    CHECK(dac.Underruns() == 0 && dac.Overruns() == 0, "UAC1 full speed: no underrun/overrun (%llu/%llu)",
          static_cast<unsigned long long>(dac.Underruns()), static_cast<unsigned long long>(dac.Overruns()));
}

static void TestPumpMicroframeFeedbackVariantAndImplicit() {
    FakeDacConfig c; c.rate = 96000; c.deviceRatePpm = 200.0; c.feedbackPerMicroframe = true;
    FakeUsbDac dac(c);
    dac.SetAlternate(1, 3);
    CounterSource src(8);
    UsbIsoPump pump;
    CHECK(pump.Start(PumpFor(dac, c, 4), &src), "start");
    Sleep(300);
    pump.Stop();
    CHECK(src.Matches(dac.Captured()) && dac.Underruns() == 0 && dac.Overruns() == 0, "per-microframe feedback reading works");

    FakeDacConfig im; im.rate = 96000; im.deviceRatePpm = 250.0; im.implicitFeedback = true;
    FakeUsbDac idac(im);
    idac.SetAlternate(1, 3);
    CounterSource isrc(8);
    UsbIsoPump ipump;
    CHECK(ipump.Start(PumpFor(idac, im, 4), &isrc), "start (implicit)");
    Sleep(300);
    ipump.Stop();
    CHECK(isrc.Matches(idac.Captured()) && idac.Captured().size() > 8 * 96000 / 8, "implicit feedback: payload intact");
}

static void TestStartStopRaces() {
    FakeDacConfig c; c.rate = 96000;
    for (int i = 0; i < 40; ++i) {
        FakeUsbDac dac(c);
        dac.SetAlternate(1, 2);
        CounterSource src(6);
        UsbIsoPump pump;
        CHECK(pump.Start(PumpFor(dac, c, 3), &src), "start %d", i);
        if (i % 3) Sleep(i % 5);
        pump.Stop();
        if (dac.OutstandingUrbs() != 0) { CHECK(false, "URBs left in flight at iteration %d", i); break; }
        CHECK(src.Matches(dac.Captured()), "prefix intact after early stop %d", i);
    }
}

static void TestDeviceLoss() {
    FakeDacConfig c; c.rate = 96000;
    FakeUsbDac dac(c);
    dac.SetAlternate(1, 2);
    CounterSource src(6);
    UsbIsoPump pump;
    std::atomic<int> notified{0};
    PumpConfig cfg = PumpFor(dac, c, 3);
    cfg.onLost = [](void* p) { ++*static_cast<std::atomic<int>*>(p); };
    cfg.onLostUser = &notified;
    CHECK(pump.Start(cfg, &src), "start");
    Sleep(50);
    dac.Unplug();
    for (int i = 0; i < 200 && pump.Running(); ++i) Sleep(5);
    CHECK(!pump.Running() && pump.Lost() && notified == 1, "pump stops itself and reports the loss once");
    pump.Stop();   // must not hang
}

static void TestOutputOpensTheRightAlternate() {
    FakeDacConfig c; c.rate = 192000; c.deviceRatePpm = 100.0;
    auto dacOwned = std::make_unique<FakeUsbDac>(c);
    FakeUsbDac* dac = dacOwned.get();
    Core::UsbExclusiveOutput out(std::move(dacOwned), -1, 0);
    CounterSource src(6);
    Core::OutputRequest req;
    req.sampleRate = 192000; req.channels = 2; req.format = HRC_SAMPLE_S24;
    Core::OutputInfo info;
    CHECK(out.Open(req, &src, info), "open 192 kHz / 24-bit");
    CHECK(info.backend == HRC_BACKEND_USB_EXCLUSIVE && info.format == HRC_SAMPLE_S24 && info.sampleRate == 192000 &&
              info.channels == 2 && (info.flags & HRC_OUTPUT_STATE_EXCLUSIVE), "info: %d %d", info.backend, info.format);
    CHECK(dac->Claimed() && dac->ActiveAlt() == 2, "interface claimed, 24-bit alternate active (alt %d)", dac->ActiveAlt());
    CHECK(dac->CurrentRate() == 192000, "UAC2 clock set and read back");
    CHECK(out.Start(), "start");
    Sleep(150);
    out.Pause();
    const auto afterPause = dac->Captured().size();
    Sleep(30);
    CHECK(dac->Captured().size() == afterPause, "pause stops the stream");
    CHECK(out.Start(), "restart after pause");
    Sleep(60);
    out.Close();
    CHECK(dac->ActiveAlt() == 0 && !dac->Claimed(), "close returns to the zero-bandwidth alternate and releases the interface");
    CHECK(dac->OutstandingUrbs() == 0, "no URBs left");

    // Wrong rate for the device.
    auto d2 = std::make_unique<FakeUsbDac>(c);
    Core::UsbExclusiveOutput bad(std::move(d2), -1, 0);
    req.sampleRate = 22050;
    CHECK(!bad.Open(req, &src, info), "22.05 kHz is not offered by the DAC");
    // A hint for an interface that does not exist.
    auto d3 = std::make_unique<FakeUsbDac>(c);
    Core::UsbExclusiveOutput wrongIface(std::move(d3), 7, 0);
    req.sampleRate = 192000;
    CHECK(!wrongIface.Open(req, &src, info), "no such interface");
    // UAC1 device with a 16-bit source.
    FakeDacConfig u1; u1.uac2 = false; u1.highSpeed = false; u1.rate = 44100;
    auto d4 = std::make_unique<FakeUsbDac>(u1);
    FakeUsbDac* dac4 = d4.get();
    Core::UsbExclusiveOutput uac1(std::move(d4), -1, 0);
    req.sampleRate = 44100; req.format = HRC_SAMPLE_S16;
    CHECK(uac1.Open(req, &src, info) && info.format == HRC_SAMPLE_S16 && dac4->ActiveAlt() == 1 && dac4->CurrentRate() == 44100, "UAC1 16-bit open");
}

int main() {
    TestParserUac2();
    TestParserUac1AndImplicit();
    TestSelector();
    TestPacer();
    TestControlRequests();
    TestPumpHighSpeedFeedback();
    TestPumpFullSpeedUac1();
    TestPumpMicroframeFeedbackVariantAndImplicit();
    TestStartStopRaces();
    TestDeviceLoss();
    TestOutputOpensTheRightAlternate();
    if (failures) { std::printf("%d failure(s)\n", failures); return 1; }
    std::printf("usb_driver_test: all passed\n");
    return 0;
}
