cmake_minimum_required(VERSION 3.22.1)
project(amper_amne LANGUAGES CXX)

add_library(
    amne
    SHARED
    amne_kernels.cpp
    amne_jni.cpp
)

target_compile_features(amne PRIVATE cxx_std_20)
target_compile_options(
    amne
    PRIVATE
    -O3
    -Wall
    -Wextra
    -Werror
    -ffp-contract=off
)

target_link_libraries(
    amne
    android
    log
)
