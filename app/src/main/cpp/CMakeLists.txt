cmake_minimum_required(VERSION 3.22.1)

project(melody_lhdc_patch)

add_library(melody_lhdc_patch SHARED native_lhdc_patch.cpp)

target_compile_options(melody_lhdc_patch PRIVATE -Wall -Wextra -Werror)
