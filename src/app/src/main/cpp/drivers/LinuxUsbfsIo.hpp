#pragma once
// UsbIo over a usbfs file descriptor (the one Android's UsbDeviceConnection hands out). Linux and
// Android only. Not owned: the caller keeps the descriptor open for the life of this object.
#include <errno.h>
#include <linux/usb/ch9.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <cstring>
#include "UsbIo.hpp"

namespace Audiophile::Drivers {

class LinuxUsbfsIo final : public UsbIo {
public:
    explicit LinuxUsbfsIo(int fd) noexcept : fd_(fd) {}

    Speed GetSpeed() noexcept override {
        const int s = ioctl(fd_, USBDEVFS_GET_SPEED, 0);
        return s >= USB_SPEED_HIGH ? Speed::High : Speed::Full;
    }

    bool ReadDescriptors(std::vector<uint8_t>& out) noexcept override {
        out.clear();
        if (lseek(fd_, 0, SEEK_SET) < 0) return false;
        uint8_t chunk[512];
        for (;;) {
            const ssize_t n = read(fd_, chunk, sizeof(chunk));
            if (n < 0) return false;
            if (n == 0) break;
            out.insert(out.end(), chunk, chunk + n);
            if (out.size() > 65536) break;
        }
        // usbfs returns the 18-byte device descriptor first; the parser walks the rest.
        if (out.size() <= 18) return false;
        out.erase(out.begin(), out.begin() + 18);
        return true;
    }

    bool ClaimInterface(int interface) noexcept override {
        unsigned int i = static_cast<unsigned int>(interface);
        return ioctl(fd_, USBDEVFS_CLAIMINTERFACE, &i) == 0 || errno == EBUSY;   // EBUSY: Java already claimed it
    }
    bool ReleaseInterface(int interface) noexcept override {
        unsigned int i = static_cast<unsigned int>(interface);
        return ioctl(fd_, USBDEVFS_RELEASEINTERFACE, &i) == 0;
    }
    bool SetAlternate(int interface, int alternate) noexcept override {
        usbdevfs_setinterface si{};
        si.interface = static_cast<unsigned int>(interface);
        si.altsetting = static_cast<unsigned int>(alternate);
        return ioctl(fd_, USBDEVFS_SETINTERFACE, &si) == 0;
    }

    int Control(uint8_t requestType, uint8_t request, uint16_t value, uint16_t index, void* data, uint16_t length,
                unsigned timeoutMs) noexcept override {
        usbdevfs_ctrltransfer t{};
        t.bRequestType = requestType;
        t.bRequest = request;
        t.wValue = value;
        t.wIndex = index;
        t.wLength = length;
        t.timeout = timeoutMs;
        t.data = data;
        const int r = ioctl(fd_, USBDEVFS_CONTROL, &t);
        return r < 0 ? -errno : r;
    }

    bool Submit(IsoUrb* urb) noexcept override {
        static_assert(sizeof(usbdevfs_urb) + IsoUrb::kMaxPackets * sizeof(usbdevfs_iso_packet_desc) <= sizeof(IsoUrb::native),
                      "native scratch too small");
        auto* k = reinterpret_cast<usbdevfs_urb*>(urb->native);
        std::memset(k, 0, sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc) * static_cast<size_t>(urb->packetCount));
        k->type = USBDEVFS_URB_TYPE_ISO;
        k->endpoint = urb->endpoint;
        k->flags = USBDEVFS_URB_ISO_ASAP;
        k->buffer = urb->buffer;
        uint32_t total = 0;
        for (int p = 0; p < urb->packetCount; ++p) {
            k->iso_frame_desc[p].length = urb->packets[p].length;
            total += urb->packets[p].length;
        }
        // IN transfers reserve every packet's room; OUT transfers send exactly what was filled.
        k->buffer_length = static_cast<int>((urb->endpoint & 0x80) ? urb->bufferBytes : total);
        k->number_of_packets = urb->packetCount;
        k->usercontext = urb;
        return ioctl(fd_, USBDEVFS_SUBMITURB, k) == 0;
    }

    IsoUrb* Reap(int& error) noexcept override {
        error = 0;
        usbdevfs_urb* k = nullptr;
        if (ioctl(fd_, USBDEVFS_REAPURB, &k) < 0) {
            if (errno == EINTR) return nullptr;
            error = errno;
            return nullptr;
        }
        auto* urb = static_cast<IsoUrb*>(k->usercontext);
        urb->status = k->status;   // 0 or -errno
        for (int p = 0; p < urb->packetCount; ++p) {
            urb->packets[p].actual = k->iso_frame_desc[p].actual_length;
            urb->packets[p].status = static_cast<int32_t>(k->iso_frame_desc[p].status);   // 0 or -errno
        }
        return urb;
    }

    void Discard(IsoUrb* urb) noexcept override {
        ioctl(fd_, USBDEVFS_DISCARDURB, urb->native);   // EINVAL when it is not in flight
    }

private:
    int fd_;
};

}  // namespace Audiophile::Drivers
