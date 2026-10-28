# Host build of the portable parts of the engine: decoders, sample-format rules,
# DSP measurement suite and the null-test gate. Runs on a plain Linux host, no phone.
# The Android-only core (PlaybackEngine, ExynosThreadGovernor) is not built here.
#
#   cmake -S tests/host -B build/host && cmake --build build/host && ctest --test-dir build/host
cmake_minimum_required(VERSION 3.22)
project(hires_host_tests LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

enable_testing()

option(HIRES_SANITIZE "Build host tests with AddressSanitizer and UBSan" ON)
option(HIRES_REQUIRE_FLAC "Fail configure when libFLAC is missing" ON)

set(SRC_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../src/app/src)
set(CPP_ROOT ${SRC_ROOT}/main/cpp)
set(TEST_ROOT ${SRC_ROOT}/test/cpp)
set(FIXTURE_DIR ${CMAKE_CURRENT_BINARY_DIR}/fixtures)

set(HIRES_WARNINGS -Wall -Wextra -Werror -fno-exceptions)
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    list(APPEND HIRES_WARNINGS -Wno-unused-private-field)
endif()

set(HIRES_SANITIZER_FLAGS "")
if(HIRES_SANITIZE)
    set(HIRES_SANITIZER_FLAGS -fsanitize=address,undefined -fno-omit-frame-pointer)
endif()

find_library(FLAC_LIBRARY NAMES FLAC)
find_path(FLAC_INCLUDE_DIR NAMES FLAC/stream_decoder.h)
set(HAVE_FLAC FALSE)
if(FLAC_LIBRARY AND FLAC_INCLUDE_DIR)
    set(HAVE_FLAC TRUE)
elseif(HIRES_REQUIRE_FLAC)
    message(FATAL_ERROR "libFLAC headers and library not found. Install libflac-dev or set -DHIRES_REQUIRE_FLAC=OFF.")
else()
    message(WARNING "libFLAC not found: flac_decoder_test is skipped.")
endif()

# Builds one test executable. Extra arguments are extra source files relative to CPP_ROOT.
function(hires_test name source)
    add_executable(${name} ${TEST_ROOT}/${source} ${ARGN})
    target_include_directories(${name} PRIVATE
        ${CPP_ROOT}/core
        ${CPP_ROOT}/dsp
        ${CPP_ROOT}/decoders)
    target_compile_options(${name} PRIVATE ${HIRES_WARNINGS} ${HIRES_SANITIZER_FLAGS} -O2)
    target_link_options(${name} PRIVATE ${HIRES_SANITIZER_FLAGS})
    add_test(NAME ${name} COMMAND ${name})
endfunction()

hires_test(pcm_encoding_test pcm_encoding_test.cpp)
hires_test(wav_decoder_test wav_decoder_test.cpp ${CPP_ROOT}/decoders/WavDecoder.cpp)
hires_test(dsd_decoder_test dsd_decoder_test.cpp ${CPP_ROOT}/decoders/DsdDecoder.cpp)
hires_test(null_test null_test.cpp ${CPP_ROOT}/decoders/WavDecoder.cpp)
hires_test(dsp_suite_test dsp_suite_test.cpp)

if(HAVE_FLAC)
    add_executable(flac_decoder_test
        ${TEST_ROOT}/flac_decoder_test.cpp
        ${CPP_ROOT}/decoders/FlacDecoder.cpp)
    target_include_directories(flac_decoder_test PRIVATE ${CPP_ROOT}/decoders ${FLAC_INCLUDE_DIR})
    target_link_libraries(flac_decoder_test PRIVATE ${FLAC_LIBRARY})
    target_compile_options(flac_decoder_test PRIVATE ${HIRES_WARNINGS} ${HIRES_SANITIZER_FLAGS} -O2)
    target_link_options(flac_decoder_test PRIVATE ${HIRES_SANITIZER_FLAGS})

    # Reference PCM is made by ffmpeg's own FLAC decoder, so the check does not depend on libFLAC.
    add_test(NAME hires_flac_fixtures
        COMMAND ${CMAKE_COMMAND} -E env bash ${CMAKE_CURRENT_SOURCE_DIR}/make_flac_fixtures.sh ${FIXTURE_DIR})
    set_tests_properties(hires_flac_fixtures PROPERTIES FIXTURES_SETUP hires_flac_fixtures)

    # add_flac_case(<test name> <flac file> <reference raw> <bytes per sample> <valid bits>)
    function(add_flac_case name flac_file raw_file bytes_per_sample valid_bits)
        add_test(NAME ${name}
            COMMAND flac_decoder_test
                ${FIXTURE_DIR}/${flac_file} ${FIXTURE_DIR}/${raw_file} ${bytes_per_sample} ${valid_bits})
        set_tests_properties(${name} PROPERTIES FIXTURES_REQUIRED hires_flac_fixtures)
    endfunction()

    add_flac_case(flac_16_44k t16.flac t16.raw 2 16)
    add_flac_case(flac_24_96k t24.flac t24.raw 3 24)
    add_flac_case(flac_24_192k h.flac h.raw 3 24)
endif()
