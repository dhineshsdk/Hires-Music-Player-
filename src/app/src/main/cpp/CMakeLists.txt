cmake_minimum_required(VERSION 3.22.1)
project("audiophile_engine" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# minSdk is 29, but ADPF (APerformanceHint) is API 33+. Weak symbols plus
# __builtin_available guards let one APK run on both.
add_compile_definitions(__ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__)

include(FetchContent)
FetchContent_Declare(
    oboe
    GIT_REPOSITORY https://github.com/google/oboe.git
    GIT_TAG 1.9.0
)
FetchContent_MakeAvailable(oboe)

# libFLAC (BSD-3-Clause): our FLAC decoder. Library only, no Ogg, linked statically.
set(BUILD_CXXLIBS OFF CACHE BOOL "" FORCE)
set(BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_DOCS OFF CACHE BOOL "" FORCE)
set(INSTALL_MANPAGES OFF CACHE BOOL "" FORCE)
set(WITH_OGG OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
FetchContent_Declare(
    flac
    GIT_REPOSITORY https://github.com/xiph/flac.git
    GIT_TAG 1.4.3
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(flac)

add_library(audiophile_engine SHARED
    core/ExynosThreadGovernor.cpp
    core/PlaybackEngine.cpp
    core/OboeOutput.cpp
    core/HiresCore.cpp
    core/hires_core_abi.c
    decoders/WavDecoder.cpp
    decoders/FlacDecoder.cpp
    decoders/DsdDecoder.cpp
    decoders/ReadAheadSource.cpp
    jni/KotlinByteSource.cpp
    jni/AudioEngineJniBridge.cpp
    jni/PlaybackEngineJni.cpp
)

# Warnings and tuning apply to our code only; Oboe builds with its own -Werror set.
target_compile_options(audiophile_engine PRIVATE
    -O3
    -march=armv8.2-a+fp16+dotprod
    -ftree-vectorize
    -Wall
    -Wextra
    -fno-exceptions
    -fvisibility=hidden
    -Werror=unguarded-availability
)

target_include_directories(audiophile_engine PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/core
    ${CMAKE_CURRENT_SOURCE_DIR}/dsp
    ${CMAKE_CURRENT_SOURCE_DIR}/drivers
    ${CMAKE_CURRENT_SOURCE_DIR}/jni
)

# FFmpeg (LGPL) as dynamic .so files, for ALAC/APE/WavPack/MP3/AAC/Opus/Vorbis. Build them once with
# third_party/ffmpeg/build_android.sh; without them the engine builds and simply lacks those formats.
set(HIRES_FFMPEG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/ffmpeg/out" CACHE PATH "FFmpeg include/ and lib/<abi>/ directory")
if(EXISTS "${HIRES_FFMPEG_DIR}/include/libavformat/avformat.h" AND EXISTS "${HIRES_FFMPEG_DIR}/lib/${ANDROID_ABI}/libavformat.so")
    foreach(ff avutil swresample avcodec avformat)
        add_library(ff_${ff} SHARED IMPORTED)
        set_target_properties(ff_${ff} PROPERTIES IMPORTED_LOCATION "${HIRES_FFMPEG_DIR}/lib/${ANDROID_ABI}/lib${ff}.so")
    endforeach()
    target_sources(audiophile_engine PRIVATE decoders/FfmpegDecoder.cpp)
    target_include_directories(audiophile_engine PRIVATE "${HIRES_FFMPEG_DIR}/include")
    target_compile_definitions(audiophile_engine PRIVATE HIRES_HAVE_FFMPEG=1)
    target_link_libraries(audiophile_engine PRIVATE ff_avformat ff_avcodec ff_swresample ff_avutil)
    message(STATUS "FFmpeg decoders enabled from ${HIRES_FFMPEG_DIR}")
else()
    message(STATUS "FFmpeg not found in ${HIRES_FFMPEG_DIR}: building without the FFmpeg decoders")
endif()

find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)
find_library(mediandk-lib mediandk)

target_link_libraries(audiophile_engine
    PRIVATE
    oboe
    FLAC
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
    ${mediandk-lib}
)

# 16 KB page-size alignment for Android 15+ (Exynos 2400 / ARMv9)
target_link_options(audiophile_engine PRIVATE "-Wl,-z,max-page-size=16384")
