#pragma once
// The seam between the USB audio logic and the kernel. Everything above this interface (descriptor
// parsing, format selection, pacing, the pump) runs unchanged on the host against a simulated DAC;
// LinuxUsbfsIo.hpp is the one implementation that talks to a real device through usbfs.
#include <array>
#include <cstdint>
#include <vector>

namespace Audiophile::Drivers {

/** One isochronous transfer: up to kMaxPackets packets on one endpoint. Owned by the pump. */
struct IsoUrb {
    static constexpr int kMaxPackets = 32;
    struct Packet {
        uint32_t length{0};   // bytes to send / room to receive
        uint32_t actual{0};   // bytes that really moved
        int32_t status{0};    // 0 or -errno
    };

    uint8_t endpoint{0};      // address including the direction bit
    uint8_t* buffer{nullptr};
    uint32_t bufferBytes{0};
    int packetCount{0};
    std::array<Packet, kMaxPackets> packets{};
    int32_t status{0};        // whole-transfer result, filled in by Reap()
    bool feedback{false};     // IN transfer carrying feedback / implicit-feedback data
    int index{0};
    alignas(8) unsigned char native[512]{};   // implementation scratch (the kernel's urb + packet descriptors)
};

class UsbIo {
public:
    enum class Speed { Full, High };

    virtual ~UsbIo() = default;

    virtual Speed GetSpeed() noexcept = 0;
    // Device descriptor followed by every configuration descriptor, raw. Not real-time.
    virtual bool ReadDescriptors(std::vector<uint8_t>& out) noexcept = 0;
    virtual bool ClaimInterface(int interface) noexcept = 0;
    virtual bool ReleaseInterface(int interface) noexcept = 0;
    virtual bool SetAlternate(int interface, int alternate) noexcept = 0;

    // USBDEVFS_CONTROL. Returns the byte count, or -errno. Used by UacControl and the HID PEQ controller;
    // never by the streaming path.
    virtual int Control(uint8_t requestType, uint8_t request, uint16_t value, uint16_t index, void* data,
                        uint16_t length, unsigned timeoutMs) noexcept = 0;

    virtual bool Submit(IsoUrb* urb) noexcept = 0;
    // Blocks until a transfer completes or is discarded. nullptr + error != 0: the device is gone.
    virtual IsoUrb* Reap(int& error) noexcept = 0;
    virtual void Discard(IsoUrb* urb) noexcept = 0;
};

}  // namespace Audiophile::Drivers
