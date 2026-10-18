#pragma once
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace Audiophile::Core {

/**
 * Decoders do not always output the sample format their output-format key claims
 * (a requested "pcm-encoding" can be echoed back without being honoured). Reading
 * 16-bit samples as float, or the reverse, plays as loud noise.
 *
 * LooksLikeFloatPcm() checks the bytes themselves: genuine float audio is almost
 * entirely finite values within [-1, 1] that are not vanishingly small. Pairs of
 * 16-bit samples reinterpreted as float land far outside that (exponents near 2^-100
 * or 2^+100, NaN/Inf), as do 32-bit integer samples.
 *
 * Returns +1 for float, -1 for "not float", 0 when the buffer is too quiet to tell.
 */
inline int LooksLikeFloatPcm(const uint8_t* data, size_t bytes) noexcept {
    const size_t count = bytes / 4;
    size_t nonZero = 0;
    size_t plausible = 0;
    bool anyNonFinite = false;
    float maxAbs = 0.0f;
    for (size_t i = 0; i < count; ++i) {
        float v;
        std::memcpy(&v, data + i * 4, 4);
        uint32_t bits;
        std::memcpy(&bits, &v, 4);
        if ((bits & 0x7fffffffu) == 0) continue;  // exact zero (silence) says nothing
        ++nonZero;
        const float a = std::fabs(v);
        if (!std::isfinite(v)) {
            anyNonFinite = true;
            continue;
        }
        if (a > maxAbs) maxAbs = a;
        if (a <= 1.01f && a >= 1.0e-7f) ++plausible;
    }
    if (nonZero < 64) return 0;
    // A fade-out tail in float can be all tiny values; that is too quiet to judge.
    if (!anyNonFinite && maxAbs < 1.0e-3f) return 0;
    return plausible * 100 >= nonZero * 95 ? 1 : -1;
}

} // namespace Audiophile::Core
