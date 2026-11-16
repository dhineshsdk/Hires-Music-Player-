cmake_minimum_required(VERSION 3.22.1)

add_library(soxr SHARED
    soxr.c
    data-io.c
    dbesi0.c
    filter.c
    fft4g64.c
    fft4g32.c
    cr.c
    cr32.c
    cr64.c
    vr32.c
    cr32s.c
    pffft32s.c
    util32s.c
)

target_include_directories(soxr PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})

target_compile_definitions(soxr PRIVATE
    SOXR_LIB=1
    _POSIX_C_SOURCE=200809L
)

target_link_libraries(soxr PRIVATE m)
