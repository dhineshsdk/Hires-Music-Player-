#pragma once
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <cstdint>
#include <array>
#include <span>
#include <numbers>
#include <cmath>
#include <algorithm>

namespace Audiophile::Drivers {

class UsbHidPeqController {
public:
    enum class FilterTopology { Peaking, LowShelf, HighShelf };

    struct EqualizerBandSpecification {
        FilterTopology topology;
        double center_frequency_hz;
        double gain_db;
        double quality_factor_q;
    };

    struct NormalizedBiquadQ30 {
        int32_t b0, b1, b2, a1, a2;
    };

    UsbHidPeqController(int usb_fd, uint8_t hid_interface)
        : fd_(usb_fd), interface_(hid_interface) {}

    bool WriteFeatureReport(uint8_t report_id, std::span<const uint8_t> payload) noexcept {
        usbdevfs_ctrltransfer transfer{};
        transfer.bRequestType = 0x21;
        transfer.bRequest = 0x09;
        transfer.wValue = static_cast<uint16_t>((0x03 << 8) | report_id);
        transfer.wIndex = interface_;
        transfer.wLength = static_cast<uint16_t>(payload.size());
        transfer.data = const_cast<uint8_t*>(payload.data());
        transfer.timeout = 1000;
        return ioctl(fd_, USBDEVFS_CONTROL, &transfer) >= 0;
    }

    static NormalizedBiquadQ30 SynthesizeBiquadQ30(const EqualizerBandSpecification& spec, double sample_rate) noexcept {
        const double A = std::pow(10.0, spec.gain_db / 40.0);
        const double w0 = 2.0 * std::numbers::pi * (spec.center_frequency_hz / sample_rate);
        const double cos_w0 = std::cos(w0);
        const double sin_w0 = std::sin(w0);
        const double alpha = sin_w0 / (2.0 * spec.quality_factor_q);

        double b0 = 0.0, b1 = 0.0, b2 = 0.0, a0 = 1.0, a1 = 0.0, a2 = 0.0;

        switch (spec.topology) {
            case FilterTopology::Peaking:
                b0 = 1.0 + (alpha * A);
                b1 = -2.0 * cos_w0;
                b2 = 1.0 - (alpha * A);
                a0 = 1.0 + (alpha / A);
                a1 = -2.0 * cos_w0;
                a2 = 1.0 - (alpha / A);
                break;
            case FilterTopology::LowShelf: {
                const double sqrt_A = std::sqrt(A);
                const double alpha_shelf = sin_w0 / 2.0 * std::sqrt((A + 1.0 / A) * (1.0 / spec.quality_factor_q - 1.0) + 2.0);
                b0 = A * ((A + 1.0) - (A - 1.0) * cos_w0 + 2.0 * sqrt_A * alpha_shelf);
                b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cos_w0);
                b2 = A * ((A + 1.0) - (A - 1.0) * cos_w0 - 2.0 * sqrt_A * alpha_shelf);
                a0 = (A + 1.0) + (A - 1.0) * cos_w0 + 2.0 * sqrt_A * alpha_shelf;
                a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cos_w0);
                a2 = (A + 1.0) + (A - 1.0) * cos_w0 - 2.0 * sqrt_A * alpha_shelf;
                break;
            }
            case FilterTopology::HighShelf: {
                const double sqrt_A = std::sqrt(A);
                const double alpha_shelf = sin_w0 / 2.0 * std::sqrt((A + 1.0 / A) * (1.0 / spec.quality_factor_q - 1.0) + 2.0);
                b0 = A * ((A + 1.0) + (A - 1.0) * cos_w0 + 2.0 * sqrt_A * alpha_shelf);
                b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cos_w0);
                b2 = A * ((A + 1.0) + (A - 1.0) * cos_w0 - 2.0 * sqrt_A * alpha_shelf);
                a0 = (A + 1.0) - (A - 1.0) * cos_w0 + 2.0 * sqrt_A * alpha_shelf;
                a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cos_w0);
                a2 = (A + 1.0) - (A - 1.0) * cos_w0 - 2.0 * sqrt_A * alpha_shelf;
                break;
            }
        }

        constexpr double kQ30Scale = 1073741824.0;
        return {
            .b0 = static_cast<int32_t>(std::clamp((b0 / a0) * kQ30Scale, -2147483648.0, 2147483647.0)),
            .b1 = static_cast<int32_t>(std::clamp((b1 / a0) * kQ30Scale, -2147483648.0, 2147483647.0)),
            .b2 = static_cast<int32_t>(std::clamp((b2 / a0) * kQ30Scale, -2147483648.0, 2147483647.0)),
            .a1 = static_cast<int32_t>(std::clamp((-a1 / a0) * kQ30Scale, -2147483648.0, 2147483647.0)),
            .a2 = static_cast<int32_t>(std::clamp((-a2 / a0) * kQ30Scale, -2147483648.0, 2147483647.0))
        };
    }

    static std::array<uint8_t, 64> SerializeFreeDspBiquad(uint8_t band_index, const NormalizedBiquadQ30& c) noexcept {
        std::array<uint8_t, 64> report{};
        report[0] = 0x08;
        report[1] = 0x57;
        report[2] = band_index;
        report[3] = 20;

        auto write_le = [&report](size_t offset, int32_t v) {
            report[offset + 0] = static_cast<uint8_t>(v & 0xFF);
            report[offset + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
            report[offset + 2] = static_cast<uint8_t>((v >> 16) & 0xFF);
            report[offset + 3] = static_cast<uint8_t>((v >> 24) & 0xFF);
        };
        write_le(4, c.b0);
        write_le(8, c.b1);
        write_le(12, c.b2);
        write_le(16, c.a1);
        write_le(20, c.a2);
        return report;
    }

    static std::array<uint8_t, 64> SerializeWalkPlayFrame(uint8_t band_index, const EqualizerBandSpecification& spec) noexcept {
        std::array<uint8_t, 64> payload{};
        payload[0] = 0x5A;
        payload[1] = 0x20;
        payload[2] = band_index;
        payload[3] = (spec.topology == FilterTopology::Peaking) ? 0x01 :
                     (spec.topology == FilterTopology::LowShelf) ? 0x02 : 0x03;

        uint16_t freq = static_cast<uint16_t>(spec.center_frequency_hz);
        payload[4] = static_cast<uint8_t>((freq >> 8) & 0xFF);
        payload[5] = static_cast<uint8_t>(freq & 0xFF);

        int16_t gain_cdb = static_cast<int16_t>(spec.gain_db * 100.0);
        payload[6] = static_cast<uint8_t>((gain_cdb >> 8) & 0xFF);
        payload[7] = static_cast<uint8_t>(gain_cdb & 0xFF);

        uint16_t q_scaled = static_cast<uint16_t>(spec.quality_factor_q * 100.0);
        payload[8] = static_cast<uint8_t>((q_scaled >> 8) & 0xFF);
        payload[9] = static_cast<uint8_t>(q_scaled & 0xFF);
        return payload;
    }

private:
    int fd_;
    uint8_t interface_;
};

} // namespace Audiophile::Drivers
