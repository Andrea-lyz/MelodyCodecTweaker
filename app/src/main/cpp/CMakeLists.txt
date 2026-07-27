cmake_minimum_required(VERSION 3.22.1)

project(melody_lhdc_patch)

add_library(melody_lhdc_patch SHARED native_lhdc_patch.cpp)

target_compile_options(melody_lhdc_patch PRIVATE -Wall -Wextra -Werror)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(melody_lhdc_patch PRIVATE -mbranch-protection=standard)
endif()

find_library(log-lib log)
find_library(dl-lib dl)
target_link_libraries(melody_lhdc_patch ${log-lib} ${dl-lib})
