#pragma once
#include <cstddef>
#include <cstdint>

namespace Audiophile::DSP {

inline constexpr uint32_t kUpolsConvolverSpv[] =
#include "upols_convolver_spv.h"
;

inline constexpr size_t kUpolsConvolverSpvSizeBytes = sizeof(kUpolsConvolverSpv);

} // namespace Audiophile::DSP
