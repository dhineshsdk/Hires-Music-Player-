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

add_library(audiophile_engine SHARED
    core/ExynosThreadGovernor.cpp
    core/PlaybackEngine.cpp
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

find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)
find_library(mediandk-lib mediandk)

target_link_libraries(audiophile_engine
    PRIVATE
    oboe
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
    ${mediandk-lib}
)
