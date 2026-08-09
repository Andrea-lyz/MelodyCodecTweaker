cmake_minimum_required(VERSION 3.22.1)

project(melody_lhdc_patch)

option(MELODY_FIXED_1000_AB "Keep quality-priority fixed at 1000 kbps for A/B evidence" OFF)
option(MELODY_DYN_OBSERVE "Observe-only dynamic adapter probe for future ROM-family research" OFF)

add_library(melody_lhdc_patch SHARED native_lhdc_patch.cpp)

if(MELODY_FIXED_1000_AB)
    target_compile_definitions(melody_lhdc_patch PRIVATE MELODY_FIXED_1000_AB=1)
endif()

if(MELODY_DYN_OBSERVE)
    target_compile_definitions(melody_lhdc_patch PRIVATE MELODY_DYN_OBSERVE=1)
endif()

target_compile_options(melody_lhdc_patch PRIVATE -Wall -Wextra -Werror)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(melody_lhdc_patch PRIVATE -mbranch-protection=standard)
endif()

find_library(log-lib log)
find_library(dl-lib dl)
target_link_libraries(melody_lhdc_patch ${log-lib} ${dl-lib})
