/*
 * Portable parts of the C API (version and result names) plus layout checks.
 * Built as C so the header is proven to be plain C in every build. The size checks pin
 * the 64-bit layout that Kotlin bindings and tests rely on; a failure here means a
 * struct changed and needs a version bump and a hand-over note.
 */
#include "hires_core.h"

#if defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
#define HRC_LAYOUT(type, size) _Static_assert(sizeof(type) == (size), #type " layout changed")
#else
#define HRC_LAYOUT(type, size) typedef char hrc_layout_##type[(sizeof(type) == (size)) ? 1 : -1]
#endif

/* Structs without pointers: identical on every target. */
HRC_LAYOUT(hrc_engine_config, 16);
HRC_LAYOUT(hrc_output_config, 32);
HRC_LAYOUT(hrc_stage_telemetry, 16);
HRC_LAYOUT(hrc_source_telemetry, 72);
HRC_LAYOUT(hrc_output_telemetry, 72);
HRC_LAYOUT(hrc_receipt, 56);
HRC_LAYOUT(hrc_levels, 104);
HRC_LAYOUT(hrc_clock, 8);
HRC_LAYOUT(hrc_counters, 32);
HRC_LAYOUT(hrc_telemetry, 512);
HRC_LAYOUT(hrc_event, 32);

/* Structs with pointers: checked on 64-bit targets (arm64-v8a, x86_64 host). */
#if UINTPTR_MAX == 0xFFFFFFFFFFFFFFFFu
HRC_LAYOUT(hrc_byte_source_callbacks, 24);
HRC_LAYOUT(hrc_source, 40);
#endif

uint32_t hrc_api_version(void) {
    return HRC_API_VERSION;
}

const char* hrc_result_name(hrc_result result) {
    switch (result) {
        case HRC_OK: return "HRC_OK";
        case HRC_E_INVALID_ARG: return "HRC_E_INVALID_ARG";
        case HRC_E_STRUCT_SIZE: return "HRC_E_STRUCT_SIZE";
        case HRC_E_BAD_STATE: return "HRC_E_BAD_STATE";
        case HRC_E_UNSUPPORTED: return "HRC_E_UNSUPPORTED";
        case HRC_E_CORRUPT: return "HRC_E_CORRUPT";
        case HRC_E_IO: return "HRC_E_IO";
        case HRC_E_NO_DEVICE: return "HRC_E_NO_DEVICE";
        case HRC_E_OUTPUT_FAILED: return "HRC_E_OUTPUT_FAILED";
        case HRC_E_NOT_BUILT: return "HRC_E_NOT_BUILT";
        case HRC_E_NO_MEMORY: return "HRC_E_NO_MEMORY";
        case HRC_E_INTERNAL: return "HRC_E_INTERNAL";
        case HRC_NO_EVENT: return "HRC_NO_EVENT";
        default: return "HRC_E_UNKNOWN";
    }
}
