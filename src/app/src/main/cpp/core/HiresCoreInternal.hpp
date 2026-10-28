#pragma once
#include <memory>
#include "AudioOutput.hpp"
#include "hires_core.h"

// For tests and for the desktop host: create an engine on a given output (e.g. ManualOutput,
// which the caller drives). Not part of the stable C API.
hrc_result hrc_engine_create_with_output(const hrc_engine_config* config,
                                         std::unique_ptr<Audiophile::Core::AudioOutput> output,
                                         hrc_engine** out_engine);
