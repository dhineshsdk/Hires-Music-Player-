#pragma once

// Logging for code that builds on Android and on a desktop host. Not for the real-time thread.
#if defined(__ANDROID__)
#include <android/log.h>
#define HRC_LOGI(tag, ...) __android_log_print(ANDROID_LOG_INFO, tag, __VA_ARGS__)
#define HRC_LOGE(tag, ...) __android_log_print(ANDROID_LOG_ERROR, tag, __VA_ARGS__)
#else
#include <cstdio>
#define HRC_LOGI(tag, ...) (std::fprintf(stderr, "I/%s: ", tag), std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#define HRC_LOGE(tag, ...) (std::fprintf(stderr, "E/%s: ", tag), std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif
