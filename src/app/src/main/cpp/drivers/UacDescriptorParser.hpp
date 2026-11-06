#pragma once
#include <array>
#include <cstdint>
#include <cstddef>
#include <span>
#include <vector>
#include <optional>

namespace Audiophile::Drivers {

#pragma pack(push, 1)
struct UsbDescriptorHeader {
    uint8_t bLength;
    uint8_t bDescriptorType;
};

struct UsbInterfaceDescriptor {
    uint8_t bLength;
    uint8_t bDescriptorType;
    uint8_t bInterfaceNumber;
    uint8_t bAlternateSetting;
    uint8_t bNumEndpoints;
    uint8_t bInterfaceClass;
    uint8_t bInterfaceSubClass;
    uint8_t bInterfaceProtocol;
    uint8_t iInterface;
};

struct UsbAudioEndpointDescriptor {
    uint8_t bLength;
    uint8_t bDescriptorType;
    uint8_t bEndpointAddress;
    uint8_t bmAttributes;
    uint16_t wMaxPacketSize;
    uint8_t bInterval;
    uint8_t bRefresh;
    uint8_t bSynchAddress;
};

struct Uac1FormatTypeIDescriptor {
    uint8_t bLength;
    uint8_t bDescriptorType;
    uint8_t bDescriptorSubtype;
    uint8_t bFormatType;
    uint8_t bNrChannels;
    uint8_t bSubframeSize;
    uint8_t bBitResolution;
    uint8_t bSamFreqType;
};

struct Uac2ClockSourceDescriptor {
    uint8_t bLength;
    uint8_t bDescriptorType;
    uint8_t bDescriptorSubtype;
    uint8_t bClockID;
    uint8_t bmAttributes;
    uint8_t bmControls;
    uint8_t bAssocTerminal;
    uint8_t iClockSource;
};

struct Uac2FormatTypeIDescriptor {
    uint8_t bLength;
    uint8_t bDescriptorType;
    uint8_t bDescriptorSubtype;
    uint8_t bFormatType;
    uint8_t bSubslotSize;
    uint8_t bBitResolution;
};
#pragma pack(pop)

struct AudioStreamingEndpointConfiguration {
    uint8_t interface_number{0};
    uint8_t alternate_setting{0};
    uint8_t protocol_version{0}; // 0x00 = UAC1, 0x20 = UAC2
    uint8_t subslot_size{0};
    uint8_t bit_resolution{0};
    uint8_t channels{0};
    uint8_t data_endpoint_address{0};
    uint8_t feedback_endpoint_address{0};
    uint16_t max_packet_size{0};
    uint8_t clock_source_id{0};
    bool is_asynchronous{false};
    bool is_implicit_feedback{false};
    // Added for Output B:
    bool is_input{false};               // the data endpoint is IN (a microphone stream), not playback
    uint8_t control_interface{0};       // the AudioControl interface (UAC2 clock requests go there)
    uint8_t sample_rate_count{0};       // UAC1: the rates the format descriptor lists (or 2 for a range)
    bool sample_rates_continuous{false};
    std::array<uint32_t, 16> sample_rates{};
};

class AudioDescriptorParser {
public:
    static std::vector<AudioStreamingEndpointConfiguration> Parse(std::span<const uint8_t> raw_config) {
        std::vector<AudioStreamingEndpointConfiguration> active_streams;
        size_t cursor = 0;

        std::optional<UsbInterfaceDescriptor> current_interface;
        std::optional<AudioStreamingEndpointConfiguration> active_stream_profile;
        uint8_t current_protocol = 0;
        uint8_t active_clock_source_id = 0;
        uint8_t control_interface = 0;

        while (cursor + sizeof(UsbDescriptorHeader) <= raw_config.size()) {
            auto header = reinterpret_cast<const UsbDescriptorHeader*>(&raw_config[cursor]);
            if (header->bLength == 0 || (cursor + header->bLength) > raw_config.size()) {
                break;
            }
            std::span<const uint8_t> descriptor_bytes(&raw_config[cursor], header->bLength);

            switch (header->bDescriptorType) {
                case 0x04: { // USB_DT_INTERFACE
                    if (descriptor_bytes.size() >= sizeof(UsbInterfaceDescriptor)) {
                        current_interface = *reinterpret_cast<const UsbInterfaceDescriptor*>(descriptor_bytes.data());
                        if (current_interface->bInterfaceClass == 0x01) {
                            current_protocol = current_interface->bInterfaceProtocol;
                            if (current_interface->bInterfaceSubClass == 0x01) control_interface = current_interface->bInterfaceNumber;
                            if (current_interface->bInterfaceSubClass == 0x02) {
                                if (active_stream_profile.has_value()) {
                                    active_streams.push_back(*active_stream_profile);
                                }
                                active_stream_profile = AudioStreamingEndpointConfiguration{
                                    .interface_number = current_interface->bInterfaceNumber,
                                    .alternate_setting = current_interface->bAlternateSetting,
                                    .protocol_version = current_protocol,
                                    .clock_source_id = active_clock_source_id
                                };
                                active_stream_profile->control_interface = control_interface;
                            }
                        }
                    }
                    break;
                }
                case 0x24: { // CS_INTERFACE
                    if (descriptor_bytes.size() < 3) break;
                    uint8_t subtype = descriptor_bytes[2];
                    if (current_interface && current_interface->bInterfaceSubClass == 0x01) {
                        if (current_protocol == 0x20 && subtype == 0x0B &&
                            descriptor_bytes.size() >= sizeof(Uac2ClockSourceDescriptor)) {
                            auto clk = reinterpret_cast<const Uac2ClockSourceDescriptor*>(descriptor_bytes.data());
                            active_clock_source_id = clk->bClockID;
                        }
                    } else if (current_interface && current_interface->bInterfaceSubClass == 0x02) {
                        if (subtype == 0x01 && active_stream_profile && current_protocol == 0x20 && descriptor_bytes.size() >= 11) {
                            active_stream_profile->channels = descriptor_bytes[10];   // UAC2 AS_GENERAL bNrChannels
                        }
                        if (subtype == 0x02 && active_stream_profile) {
                            if (current_protocol == 0x00 && descriptor_bytes.size() >= sizeof(Uac1FormatTypeIDescriptor)) {
                                auto fmt = reinterpret_cast<const Uac1FormatTypeIDescriptor*>(descriptor_bytes.data());
                                active_stream_profile->subslot_size = fmt->bSubframeSize;
                                active_stream_profile->bit_resolution = fmt->bBitResolution;
                                active_stream_profile->channels = fmt->bNrChannels;
                                // bSamFreqType 0: a continuous range (min, max); n: n discrete 3-byte rates.
                                const size_t listed = fmt->bSamFreqType == 0 ? 2 : fmt->bSamFreqType;
                                active_stream_profile->sample_rates_continuous = fmt->bSamFreqType == 0;
                                uint8_t stored = 0;
                                for (size_t i = 0; i < listed && stored < active_stream_profile->sample_rates.size() &&
                                                   8 + (i + 1) * 3 <= descriptor_bytes.size(); ++i) {
                                    const uint8_t* r = descriptor_bytes.data() + 8 + i * 3;
                                    active_stream_profile->sample_rates[stored++] =
                                        r[0] | (static_cast<uint32_t>(r[1]) << 8) | (static_cast<uint32_t>(r[2]) << 16);
                                }
                                active_stream_profile->sample_rate_count = stored;
                            } else if (current_protocol == 0x20 && descriptor_bytes.size() >= sizeof(Uac2FormatTypeIDescriptor)) {
                                auto fmt = reinterpret_cast<const Uac2FormatTypeIDescriptor*>(descriptor_bytes.data());
                                active_stream_profile->subslot_size = fmt->bSubslotSize;
                                active_stream_profile->bit_resolution = fmt->bBitResolution;
                            }
                        }
                    }
                    break;
                }
                case 0x05: { // USB_DT_ENDPOINT
                    if (descriptor_bytes.size() >= 7 && active_stream_profile) {
                        auto ep = reinterpret_cast<const UsbAudioEndpointDescriptor*>(descriptor_bytes.data());
                        if ((ep->bmAttributes & 0x03) == 0x01) {
                            uint8_t sync_type = (ep->bmAttributes >> 2) & 0x03;
                            uint8_t usage_type = (ep->bmAttributes >> 4) & 0x03;
                            if (usage_type == 0x01) {
                                active_stream_profile->feedback_endpoint_address = ep->bEndpointAddress;
                            } else if (usage_type == 0x00) {
                                active_stream_profile->data_endpoint_address = ep->bEndpointAddress;
                                active_stream_profile->is_input = (ep->bEndpointAddress & 0x80) != 0;
                                active_stream_profile->max_packet_size = ep->wMaxPacketSize & 0x07FF;
                                active_stream_profile->is_asynchronous = (sync_type == 0x01);
                                if (descriptor_bytes.size() >= sizeof(UsbAudioEndpointDescriptor) &&
                                    sync_type == 0x01 && ep->bSynchAddress != 0) {
                                    active_stream_profile->feedback_endpoint_address = ep->bSynchAddress;
                                }
                            } else if (usage_type == 0x02) {
                                active_stream_profile->is_implicit_feedback = true;
                                active_stream_profile->feedback_endpoint_address = ep->bEndpointAddress;
                            }
                        }
                    }
                    break;
                }
                default: break;
            }
            cursor += header->bLength;
        }
        if (active_stream_profile.has_value()) {
            active_streams.push_back(*active_stream_profile);
        }
        return active_streams;
    }
};

} // namespace Audiophile::Drivers
